#!/usr/bin/env python3
"""Count body-bearing Kotlin functions in the Android production source tree.

Requires tree-sitter==0.25.2 and tree-sitter-kotlin==1.1.0. Use
``analyze_files(root: Path, files: list[Path]) -> list[dict]`` with the
repository root and explicit production ``*.kt`` paths (root-relative or
absolute). Paths are deduplicated and sorted; no discovery or output writes.

Each row has file (root-relative POSIX path), owner (named class/object
chain, empty at top level), name, receiver (empty if absent), parameter_types
(list of normalized type strings), id, start_line/end_line (1-based), ccn,
nloc, and analyzer='tree-sitter-kotlin'. ID grammar: ``file::link.link...``;
links appear in lexical nesting order, not grouped by kind. A named class,
object, or companion object contributes its literal name (unnamed companion:
``Companion``); a function contributes ``name@Receiver(T1,T2)`` (omit
``@Receiver`` when absent); a secondary constructor contributes
``constructor(T1)``. Properties and parameters (including primary-constructor
and function-parameter defaults) contribute ``<prop:name>`` and
``<param:name>``; accessors add ``<get>`` or ``<set>``;
initializers add ``<init>`` and enum entries ``<enum:name>``. Anonymous
objects add ``<object:Super1,Super2>`` (omit supertypes when absent), lambdas
``<lambda:callee>`` (or ``<lambda>`` without a call), and anonymous functions
``<anonymous-fun>``. Call arguments add ``<arg:name>`` for named arguments or
``<arg:1>`` etc. for positional arguments. Control-flow scopes add ``<if>``
and ``<then>``/``<else>``, ``<when>``, ``<try>``/``<catch>``/``<finally>``,
``<for>``/``<while>``/``<do>``. Types and callees have comments and
non-escaped whitespace removed. For example:
``file::outer(Int).Local.call()`` differs from
``file::Local.outer(Int).call()``. Indistinguishable non-function sibling
scopes get #2, #3, ... on their scope link (notably multiple ``init`` blocks);
identical function base IDs get #2, #3, ... at the end. Distinct named sibling
reordering, body edits, and inserted lines do not change IDs; only identical
scope/signature duplicates and positional call arguments depend on order.
``owner`` excludes functions and anonymous objects. Local named functions have
their own rows, and their bodies and terminating semicolons do not add to
their parent's metrics.
CCN counts if/loops/catch/non-else when arms/&&/||/?:, plus one. NLOC counts
nonblank physical declaration lines after masking comments and nested named
declarations (including their trailing semicolons) bytewise, preserving parent
code that shares a line with a child.
Invalid syntax (ERROR or MISSING, except the grammar's known zero-width
same-line class-member separator), invalid UTF-8, and unreadable files fail
with a file:line diagnostic; declaration-only files legitimately yield [].
"""
from __future__ import annotations

from pathlib import Path

from tree_sitter import Language, Parser
import tree_sitter_kotlin


def _text(source, node):
    return source[node.start_byte:node.end_byte].decode("utf-8")


def _type(source, node):
    # Comments and layout outside escaped identifiers are not part of a type.
    segment = bytearray(source[node.start_byte:node.end_byte])
    for descendant in _walk(node):
        if descendant.type in {"line_comment", "multiline_comment", "block_comment"}:
            start = descendant.start_byte - node.start_byte
            end = descendant.end_byte - node.start_byte
            segment[start:end] = b" " * (end - start)
    escaped = False
    result = []
    for char in segment.decode("utf-8"):
        if char == "`":
            escaped = not escaped
        if escaped or not char.isspace() or char == "`":
            result.append(char)
    return "".join(result)


def _signature(source, node):
    parameters = next(c for c in node.named_children if c.type == "function_value_parameters")
    types = []
    for param in parameters.named_children:
        if param.type == "parameter":
            colon = next(i for i, c in enumerate(param.children) if c.type == ":")
            type_node = next(c for c in param.children[colon + 1:]
                             if c.is_named and c.type not in {"line_comment", "multiline_comment", "block_comment"})
            types.append(_type(source, type_node))
    dot = next((i for i, c in enumerate(node.children) if c.type == "."), None)
    receiver = ""
    if dot is not None:
        type_node = next(c for c in reversed(node.children[:dot])
                         if c.is_named and c.type not in {"line_comment", "multiline_comment", "block_comment"})
        receiver = _type(source, type_node)
    return receiver, types


def _benign_class_member_separators(root, source):
    """Recognize only the grammar's invisible separator immediately before }."""
    rendered = str(root)
    marker = "(MISSING _class_member_semi)"
    if "(ERROR" in rendered or "(MISSING " not in rendered:
        return False
    benign = 0
    for node in _walk(root):
        # A descendant's marker does not belong to this body. The grammar
        # prints an immediate missing separator as the final child of its body.
        if node.type != "class_body" or not str(node).endswith(" " + marker + ")"):
            continue
        children = node.children
        members = node.named_children
        if (not children or
                children[-1].type != "}" or not members or
                members[-1].type != "function_declaration" or
                node.start_point.row != children[-1].start_point.row or
                members[-1].end_point.row != children[-1].start_point.row or
                source[members[-1].end_byte:children[-1].start_byte].strip()):
            return False
        benign += 1
    return benign > 0 and rendered.count("(MISSING ") == rendered.count(marker) == benign


def _first_error(root):
    """Find ERROR/MISSING, or the deepest errored ancestor of a virtual token."""
    pending = [root]
    fallback = root
    while pending:
        node = pending.pop()
        if node.is_error or node.is_missing:
            return node
        if node.has_error:
            fallback = node
        pending.extend(reversed([child for child in node.children if child.has_error or child.is_missing]))
    # Some MISSING tokens appear in the printed syntax tree but are not exposed
    # by Node.children in 0.25.2. The ancestor still carries has_error.
    return fallback


def analyze_files(root: Path, files: list[Path]) -> list[dict]:
    """Return one metric row per body-bearing named function in selected files."""
    parser = Parser(Language(tree_sitter_kotlin.language()))
    root = root.resolve()
    selected = set()
    for supplied in files:
        try:
            relative = (root / supplied).resolve().relative_to(root)
        except ValueError as exc:
            raise ValueError(f"{supplied}:1: Kotlin source outside repository") from exc
        if relative.parts[:4] != ("android", "app", "src", "main") or relative.suffix != ".kt":
            raise ValueError(f"{relative.as_posix()}:1: outside Kotlin production scope")
        selected.add(relative)
    rows = []
    duplicates = {}
    for path in sorted(selected):
        try:
            source = (root / path).read_bytes()
        except OSError as exc:
            raise ValueError(f"{path.as_posix()}:1: cannot read Kotlin source: {exc}") from exc
        try:
            source.decode("utf-8")
        except UnicodeDecodeError as exc:
            line = source[:exc.start].count(b"\n") + 1
            raise ValueError(f"{path.as_posix()}:{line}: invalid UTF-8") from exc
        tree = parser.parse(source)
        if tree.root_node.has_error and not _benign_class_member_separators(tree.root_node, source):
            error = _first_error(tree.root_node)
            raise ValueError(f"{path.as_posix()}:{error.start_point.row + 1}: Kotlin parse error ({error.type})")

        scope_duplicates = {}

        def scoped(chain, link):
            """Number only indistinguishable lexical scopes under the same parent."""
            key = (chain, link)
            scope_duplicates[key] = scope_duplicates.get(key, 0) + 1
            ordinal = scope_duplicates[key]
            return (*chain, link + (f"#{ordinal}" if ordinal > 1 else ""))

        def identifier(node):
            return next((_text(source, child) for child in node.named_children
                         if child.type == "identifier"), "")

        def lambda_link(node):
            parent = node.parent
            if parent.type == "annotated_lambda":
                parent = parent.parent
            if parent.type == "call_expression":
                callee = next((child for child in parent.named_children
                               if child.type not in {"annotated_lambda", "value_arguments"}), None)
                if callee is not None:
                    return f"<lambda:{_type(source, callee)}>"
            return "<lambda>"

        def visit(node, owners=(), chain=()):
            # One lexical chain keeps classes and functions in their actual
            # nesting order; owners remains the named class/object display field.
            if node.type in {"class_declaration", "object_declaration", "companion_object"}:
                name_node = node.child_by_field_name("name")
                owner = _text(source, name_node) if name_node is not None else "Companion"
                owners = (*owners, owner)
                chain = (*chain, owner)
            elif node.type in {"property_declaration", "class_parameter"} or (
                    node.type == "parameter" and node.parent.type in
                    {"function_value_parameters", "lambda_parameters"}):
                variable = next((child for child in node.named_children
                                 if child.type == "variable_declaration"), None)
                name = identifier(variable if variable is not None else node)
                kind = "prop" if node.type == "property_declaration" else "param"
                chain = scoped(chain, f"<{kind}:{name}>")
            elif node.type in {"getter", "setter"}:
                chain = (*chain, f"<{'get' if node.type == 'getter' else 'set'}>")
            elif node.type == "enum_entry":
                chain = scoped(chain, f"<enum:{identifier(node)}>")
            elif node.type == "anonymous_initializer":
                chain = scoped(chain, "<init>")
            elif node.type == "lambda_literal":
                chain = scoped(chain, lambda_link(node))
            elif node.type == "anonymous_function":
                chain = scoped(chain, "<anonymous-fun>")
            elif node.type in {"if_expression", "when_entry", "try_expression",
                               "catch_block", "finally_block", "for_statement",
                               "while_statement", "do_while_statement"}:
                scope = {"if_expression": "if", "when_entry": "when",
                         "try_expression": "try", "catch_block": "catch",
                         "finally_block": "finally", "for_statement": "for",
                         "while_statement": "while", "do_while_statement": "do"}[node.type]
                chain = scoped(chain, f"<{scope}>")
            elif node.type == "block" and node.parent.type == "if_expression":
                arms = [child for child in node.parent.named_children if child.type == "block"]
                chain = (*chain, "<then>" if arms[0] == node else "<else>")
            elif node.type == "value_argument":
                before_equals = next((index for index, child in enumerate(node.children)
                                      if child.type == "="), None)
                name = (next((_text(source, child) for child in node.children[:before_equals]
                              if child.type == "identifier"), "")
                        if before_equals is not None else "")
                position = 1 + [child for child in node.parent.named_children
                                if child.type == "value_argument"].index(node)
                chain = (*chain, f"<arg:{name or position}>")
            elif node.type == "object_literal":
                specifiers = next((child for child in node.named_children
                                   if child.type == "delegation_specifiers"), None)
                supertypes = (",".join(_type(source, specifier)
                                       for specifier in specifiers.named_children)
                              if specifiers is not None else "")
                chain = scoped(chain, f"<object{':' + supertypes if supertypes else ''}>")
            if node.type in {"function_declaration", "secondary_constructor"}:
                name = (_text(source, node.child_by_field_name("name"))
                        if node.type == "function_declaration" else "constructor")
                receiver, types = _signature(source, node)
                signature = f"{name}{'@' + receiver if receiver else ''}({','.join(types)})"
                chain = (*chain, signature)
            if node.type == "function_declaration":
                body = next((c for c in node.named_children if c.type == "function_body"), None)
                if body is not None:
                    identity = f"{path.as_posix()}::{'.'.join(chain)}"
                    duplicates[identity] = duplicates.get(identity, 0) + 1
                    identity += f"#{duplicates[identity]}" if duplicates[identity] > 1 else ""
                    segment = bytearray(source[node.start_byte:node.end_byte])
                    nested = [descendant for descendant in _walk(node)
                              if descendant is not node and descendant.type == "function_declaration"]
                    for descendant in _walk(node):
                        if descendant.type in {"line_comment", "multiline_comment", "block_comment"} or descendant in nested:
                            for index in range(descendant.start_byte - node.start_byte,
                                               descendant.end_byte - node.start_byte):
                                if segment[index] not in (10, 13):
                                    segment[index] = 32
                    for descendant in nested:
                        # A semicolon terminating a child declaration is not
                        # executable parent code, even when it stands alone.
                        index = descendant.end_byte - node.start_byte
                        while index < len(segment) and segment[index] in (32, 9):
                            index += 1
                        if index < len(segment) and segment[index] == 59:
                            segment[index] = 32
                    lines = sum(bool(line.strip()) for line in segment.splitlines())
                    ccn = _complexity(body)
                    rows.append({"file": path.as_posix(), "owner": ".".join(owners), "name": name,
                                 "receiver": receiver, "parameter_types": types,
                                 "id": identity,
                                 "start_line": node.start_point.row + 1,
                                 "end_line": node.end_point.row + 1,
                                 "ccn": ccn, "nloc": lines,
                                 "analyzer": "tree-sitter-kotlin"})
            parameter_scope = None
            for child in node.named_children:
                # tree-sitter-kotlin 1.1.0 exposes function parameter defaults
                # as siblings *after* the parameter node, unlike class defaults.
                if node.type == "function_value_parameters":
                    if child.type == "parameter":
                        parameter_scope = f"<param:{identifier(child)}>"
                        visit(child, owners, chain)
                        continue
                    visit(child, owners, (*chain, parameter_scope) if parameter_scope else chain)
                else:
                    visit(child, owners, chain)
        visit(tree.root_node)
    return rows


_BRANCHES = {"if_expression", "for_statement", "while_statement", "do_while_statement", "catch_block"}
_OPERATORS = {"&&", "||", "?:"}


def _complexity(body):
    def count(node):
        if node is not body and node.type == "function_declaration":
            return 0
        score = int(node.type in _BRANCHES)
        if node.type == "when_entry" and not any(child.type == "else" for child in node.children):
            score += 1
        if node.type == "binary_expression":
            score += sum(child.type in _OPERATORS for child in node.children)
        return score + sum(count(child) for child in node.named_children)
    return 1 + count(body)


def _walk(node):
    yield node
    for child in node.named_children:
        yield from _walk(child)
