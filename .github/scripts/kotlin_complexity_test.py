#!/usr/bin/env python3
"""Contract tests for the tree-sitter Kotlin function counter."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("kotlin_complexity.py")


def analyzer():
    spec = importlib.util.spec_from_file_location("kotlin_complexity", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class KotlinComplexityTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.path = Path("android/app/src/main/java/Sample.kt")
        (self.root / self.path).parent.mkdir(parents=True)

    def scan(self, source):
        (self.root / self.path).write_text(source, encoding="utf-8")
        return analyzer().analyze_files(self.root, [self.path])

    def test_expression_block_and_bodyless_functions(self):
        rows = self.scan("fun expression(x: Int) = if (x > 0) x else 0\n"
                         "fun block() { // comment\n val x = 1 /* comment\n comment */\n}\n"
                         "fun bodyless(x: Int): Int\n")
        self.assertEqual([(r["name"], r["ccn"], r["nloc"]) for r in rows],
                         [("expression", 2, 1), ("block", 1, 3)])
        self.assertEqual(rows[0], {"file": self.path.as_posix(), "owner": "",
                                    "name": "expression", "receiver": "",
                                    "parameter_types": ["Int"],
                                    "id": self.path.as_posix() + "::expression(Int)",
                                    "start_line": 1, "end_line": 1,
                                    "ccn": 2, "nloc": 1,
                                    "analyzer": "tree-sitter-kotlin"})

    def test_escaped_type_names_do_not_collide_with_unescaped_types(self):
        rows = self.scan('fun use(x: `my type`) = x\nfun use(x: mytype) = x\n')
        self.assertEqual([row['parameter_types'] for row in rows], [['`my type`'], ['mytype']])
        self.assertNotEqual(rows[0]['id'], rows[1]['id'])

    def test_ids_ignore_unrelated_insertions_and_body_edits(self):
        before = self.scan('fun render(x: Int) = if (x > 0) 1 else 0\n'
                           'fun render(x: Int) = x\n')
        after = self.scan('fun inserted() = true\n'
                          'fun render(x: Int) = if (x > 1) 2 else 0\n'
                          'fun render(x: Int) = x + 1\n')
        self.assertEqual([row['id'] for row in before], [row['id'] for row in after[1:]])
        self.assertEqual([row['id'].rsplit('::', 1)[1] for row in before],
                         ['render(Int)', 'render(Int)#2'])

    def test_real_production_functions_match_independent_ast_count_on_repeated_passes(self):
        from tree_sitter import Language, Parser
        import tree_sitter_kotlin

        root = SCRIPT.parents[2]
        paths = sorted((root / 'android/app/src/main').rglob('*.kt'))
        relative = [path.relative_to(root) for path in paths]
        parser = Parser(Language(tree_sitter_kotlin.language()))

        def count_bodies(node):
            return int(node.type == 'function_body' and node.parent.type == 'function_declaration') + sum(
                count_bodies(child) for child in node.named_children)

        independent = sum(count_bodies(parser.parse(path.read_bytes()).root_node) for path in paths)
        self.assertGreater(independent, 0)
        module = analyzer()
        first = module.analyze_files(root, relative)
        second = module.analyze_files(root, list(reversed(relative)))
        self.assertEqual(len(first), independent)
        self.assertEqual(first, second)
        self.assertEqual(len({row['id'] for row in first}), independent)

    def test_input_paths_are_root_relative_deduplicated_and_production_only(self):
        first = self.root / self.path
        first.write_text('fun f() = 1\n', encoding='utf-8')
        second = self.path.with_name('Second.kt')
        (self.root / second).write_text('fun g() = 2\n', encoding='utf-8')
        empty = self.path.with_name('OnlyDeclarations.kt')
        (self.root / empty).write_text('class OnlyDeclarations\n', encoding='utf-8')
        module = analyzer()
        rows = module.analyze_files(self.root, [self.root / second, self.path, first, empty])
        self.assertEqual([(r['file'], r['name']) for r in rows],
                         [(self.path.as_posix(), 'f'), (second.as_posix(), 'g')])
        other = Path('android/app/src/test/java/Outside.kt')
        with self.assertRaisesRegex(ValueError, r'Outside\.kt:1:'):
            module.analyze_files(self.root, [other])

    def test_compose_lambdas_getter_and_initializer_do_not_create_rows(self):
        rows = self.scan('''class Context {
 val x: Int get() = if (true) 1 else 2
 init { if (true) println(x) }
 fun render() {
  Column { if (true) Text("a"); listOf(1).map { if (it > 0) it else 0 }; Row { if (true) Text("b") } }
  val proxy = object : Runnable {
   override fun run() { if (true) println(x) }
  }
 }
}
''')
        self.assertEqual([(r['owner'], r['name'], r['ccn']) for r in rows],
                         [('Context', 'render', 4), ('Context', 'run', 2)])

    def test_parse_error_and_missing_node_fail_with_file_and_line(self):
        for bad in ('fun broken( = 1\n', 'fun broken(x: Int) {\n  if (x > 0) 1\n'):
            with self.subTest(bad=bad):
                with self.assertRaisesRegex(ValueError, r'Sample\.kt:\d+:'):
                    self.scan(bad)

    def test_single_line_interface_member_without_body_is_valid(self):
        self.assertEqual(self.scan("interface Contract { fun invoke(): Unit }\n"), [])

    def test_single_line_class_expression_member_has_a_row(self):
        rows = self.scan("class X { fun f() = 1 }\n")
        self.assertEqual([(r["id"], r["ccn"]) for r in rows],
                         [(self.path.as_posix() + "::X.f()", 1)])

    def test_nested_single_line_class_inside_multiline_class_is_valid(self):
        rows = self.scan('class Outer {\n    class Inner { fun local() = 1 }\n}\n')
        self.assertEqual([row['id'] for row in rows],
                         [self.path.as_posix() + '::Outer.Inner.local()'])

    def test_non_function_scope_links_survive_distinct_sibling_reordering(self):
        fragments = [
            '    fun local() = 1\n',
            '    init { fun local() = if (true) 2 else 3 }\n',
            '    val initialized = run { fun local() = 4; local() }\n',
            '    val delegated by lazy { fun local() = 5; local() }\n',
            '    val accessed: Int get() { fun local() = 6; return local() }\n',
            '    var modified: Int = 1\n        set(value) { fun local() = 7; field = value }\n',
            '    val rendered = { fun local() = 8; local() }\n',
            '    val anonymous = object {\n fun local() = 9\n }\n',
            '    constructor(s: String): this(1) { fun local() = 10 }\n',
            '    companion object {\n init { fun local() = 11 }\n }\n',
            '    object Nested {\n init { fun local() = 12 }\n }\n',
        ]
        def source(parts):
            return ('val top = run { fun local() = 13; local() }\n'
                    'class C(val initial: Int = run { fun local() = 14; local() }) {\n'
                    + ''.join(parts) + '}\n'
                    'enum class E {\n A { fun local() = 15; },\n B { fun local() = 16; }\n}\n')
        prefix = self.path.as_posix() + '::'
        original = self.scan(source(fragments))
        ids = {row['id'] for row in original}
        self.assertEqual(ids, {prefix + suffix for suffix in (
            '<prop:top>.<lambda:run>.local()',
            'C.<param:initial>.<lambda:run>.local()',
            'C.local()', 'C.<init>.local()',
            'C.<prop:initialized>.<lambda:run>.local()',
            'C.<prop:delegated>.<lambda:lazy>.local()',
            'C.<prop:accessed>.<get>.local()',
            'C.<prop:modified>.<set>.local()',
            'C.<prop:rendered>.<lambda>.local()',
            'C.<prop:anonymous>.<object>.local()',
            'C.constructor(String).local()',
            'C.Companion.<init>.local()', 'C.Nested.<init>.local()',
            'E.<enum:A>.local()', 'E.<enum:B>.local()',
        )})
        self.assertEqual(len(original), len(ids))
        self.assertTrue(all('#' not in identity for identity in ids))
        reordered = self.scan(source(list(reversed(fragments))))
        self.assertEqual(ids, {row['id'] for row in reordered})
        self.assertEqual({row['id']: (row['ccn'], row['nloc']) for row in original},
                         {row['id']: (row['ccn'], row['nloc']) for row in reordered})
        self.assertEqual({prefix + 'C.local()': 1, prefix + 'C.<init>.local()': 2},
                         {row['id']: row['ccn'] for row in original
                          if row['id'] in {prefix + 'C.local()', prefix + 'C.<init>.local()'}})

    def test_control_flow_and_call_arguments_qualify_local_scopes(self):
        rows = self.scan('''fun outer() {
    if (true) { fun local() = 1 } else { fun local() = 2 }
    when (1) {
        1 -> { fun local() = 3 }
        else -> { fun local() = 4 }
    }
    try { fun local() = 5 }
    catch (e: Exception) { fun local() = 6 } finally { fun local() = 7 }
    use(object { fun local() = 8; }, object { fun local() = 9; })
}
''')
        suffixes = [row['id'].split('::', 1)[1] for row in rows]
        self.assertEqual(suffixes, [
            'outer()', 'outer().<if>.<then>.local()', 'outer().<if>.<else>.local()',
            'outer().<when>.local()', 'outer().<when>#2.local()',
            'outer().<try>.local()', 'outer().<try>.<catch>.local()',
            'outer().<try>.<finally>.local()',
            'outer().<arg:1>.<object>.local()', 'outer().<arg:2>.<object>.local()',
        ])
        self.assertEqual(len(suffixes), len(set(suffixes)))

    def test_named_argument_object_links_survive_argument_reorder(self):
        first = 'fun outer() { use(first = object { fun local() = 1; }, second = object { fun local() = 2; }) }'
        second = 'fun outer() { use(second = object { fun local() = 2; }, first = object { fun local() = 1; }) }'
        expected = {'outer().<arg:first>.<object>.local()',
                    'outer().<arg:second>.<object>.local()'}
        for source in (first, second):
            self.assertEqual({row['id'].split('::', 1)[1] for row in self.scan(source)
                              if row['name'] == 'local'}, expected)

    def test_parameter_defaults_and_anonymous_functions_have_named_scopes(self):
        first = ('fun outer(x: Int = run { fun local() = 1; local() }, '
                 'y: Int = run { fun local() = 2; local() }) = 0\n'
                 'val callback = fun() { fun local() = 3 }\n')
        second = ('fun outer(y: Int = run { fun local() = 2; local() }, '
                  'x: Int = run { fun local() = 1; local() }) = 0\n'
                  'val callback = fun() { fun local() = 3 }\n')
        expected = {'outer(Int,Int).<param:x>.<lambda:run>.local()',
                    'outer(Int,Int).<param:y>.<lambda:run>.local()',
                    '<prop:callback>.<anonymous-fun>.local()'}
        for source in (first, second):
            self.assertEqual({row['id'].split('::', 1)[1] for row in self.scan(source)
                              if row['name'] == 'local'}, expected)

    def test_multiple_initializers_only_identical_scopes_use_ordinals(self):
        rows = self.scan('class C {\n init { fun local() = 1 }\n'
                         ' fun local() = 2\n init { fun local() = 3 }\n}\n')
        self.assertEqual([row['id'].split('::', 1)[1] for row in rows],
                         ['C.<init>.local()', 'C.local()', 'C.<init>#2.local()'])

    def test_real_error_in_class_body_still_fails(self):
        with self.assertRaisesRegex(ValueError, r"Sample\.kt:1: Kotlin parse error"):
            self.scan("class X { fun f(x: ) = 1 }\n")

    def test_other_missing_tokens_still_fail(self):
        with self.assertRaisesRegex(ValueError, r"Sample\.kt:1: Kotlin parse error"):
            self.scan("class X { fun f(x:) = 1; }\n")

    def test_type_comments_do_not_change_overload_or_receiver_ids(self):
        rows = self.scan("fun f(x: Int) = x\n"
                         "fun f(x: /* note */ Int) = x\n"
                         "fun f(x: /* note */ String) = x\n"
                         "fun List<Int>.g() = 1\n"
                         "fun List</* note */ Int>.g() = 1\n")
        prefix = self.path.as_posix() + "::"
        self.assertEqual([row["id"] for row in rows],
                         [prefix + "f(Int)", prefix + "f(Int)#2", prefix + "f(String)",
                          prefix + "g@List<Int>()", prefix + "g@List<Int>()#2"])
        self.assertEqual([row["parameter_types"] for row in rows[:3]],
                         [["Int"], ["Int"], ["String"]])
        self.assertEqual([row["receiver"] for row in rows[3:]], ["List<Int>", "List<Int>"])

    def test_local_function_ids_include_enclosing_function_chain(self):
        rows = self.scan("class C {\n"
                         "fun alpha() { fun local() = 1 }\n"
                         "fun beta() { fun local() = 2 }\n"
                         "}\n")
        prefix = self.path.as_posix() + "::C."
        self.assertEqual([row["id"] for row in rows],
                         [prefix + "alpha()", prefix + "alpha().local()",
                          prefix + "beta()", prefix + "beta().local()"])
        self.assertEqual([row["owner"] for row in rows], ["C"] * 4)

    def test_overloaded_enclosing_signatures_keep_local_ids_stable_on_reorder(self):
        first = '''fun outer(x: Int) {
    fun local() = x
    fun List</* receiver */ Int>.middle(y: String) {
        fun leaf() = y
    }
    val helper = object : Runnable {
        override fun run() = println(x)
    }
}
fun outer(x: String) {
    fun local() = x
    val helper = object : Runnable {
        override fun run() = println(x)
    }
}
'''
        int_overload, string_overload = first.split('fun outer(x: String)')
        reordered = 'fun outer(x: String)' + string_overload + int_overload
        prefix = self.path.as_posix() + '::'
        original = self.scan(first)
        self.assertEqual([row['id'] for row in original],
                         [prefix + name for name in (
                             'outer(Int)', 'outer(Int).local()',
                             'outer(Int).middle@List<Int>(String)',
                             'outer(Int).middle@List<Int>(String).leaf()',
                             'outer(Int).<prop:helper>.<object:Runnable>.run()', 'outer(String)',
                             'outer(String).local()',
                             'outer(String).<prop:helper>.<object:Runnable>.run()')])
        self.assertEqual({row['id'] for row in original},
                         {row['id'] for row in self.scan(reordered)})
        commented = first.replace('outer(x: Int)', 'outer(x: /* parameter */ Int)')
        commented = commented.replace('middle(y: String)', 'middle(y: /* parameter */ String)')
        self.assertEqual([row['id'] for row in original],
                         [row['id'] for row in self.scan(commented)])

    def test_secondary_constructor_signatures_qualify_local_functions(self):
        source = '''class C {
    constructor(x: Int) { fun local() = x }
    constructor(x: String) { fun local() = x }
}
'''
        rows = self.scan(source)
        self.assertEqual([row['id'] for row in rows],
                         [self.path.as_posix() + '::C.constructor(Int).local()',
                          self.path.as_posix() + '::C.constructor(String).local()'])
        reordered = source.replace('    constructor(x: Int) { fun local() = x }\n'
                                   '    constructor(x: String) { fun local() = x }',
                                   '    constructor(x: String) { fun local() = x }\n'
                                   '    constructor(x: /* note */ Int) { fun local() = x }')
        self.assertEqual({row['id'] for row in rows},
                         {row['id'] for row in self.scan(reordered)})

    def test_unreadable_or_invalid_utf8_file_fails_with_context(self):
        module = analyzer()
        with self.assertRaisesRegex(ValueError, r'Sample\.kt:1:'):
            module.analyze_files(self.root, [self.path])
        (self.root / self.path).write_bytes(b'fun ok() = 1\n\xff')
        with self.assertRaisesRegex(ValueError, r'Sample\.kt:2:'):
            module.analyze_files(self.root, [self.path])

    def test_qualified_owner_receiver_parameters_and_duplicate_ordinals(self):
        source = '''class A {
    fun f(x: Int) = x
    fun f(x: String) = x
    companion object {
        fun f(x: Int) = x
    }
    object Inner {
        fun f(x: Int) = x
    }
}
object B {
    fun f(x: Int) = x
}
fun <T> List<T>.takeTwo(vararg xs: T, block: (T) -> Boolean = { true }) = 1
fun f(x: Int) = x
fun f(x: Int) = x
'''
        rows = self.scan(source)
        self.assertEqual([(r['owner'], r['name'], r['receiver'], r['parameter_types'])
                          for r in rows[:6]],
                         [('A', 'f', '', ['Int']), ('A', 'f', '', ['String']),
                          ('A.Companion', 'f', '', ['Int']),
                          ('A.Inner', 'f', '', ['Int']), ('B', 'f', '', ['Int']),
                          ('', 'takeTwo', 'List<T>', ['T', '(T)->Boolean'])])
        prefix = self.path.as_posix() + '::'
        self.assertEqual([r['id'] for r in rows],
                         [prefix + name for name in ('A.f(Int)', 'A.f(String)',
                          'A.Companion.f(Int)', 'A.Inner.f(Int)', 'B.f(Int)',
                          'takeTwo@List<T>(T,(T)->Boolean)', 'f(Int)', 'f(Int)#2')])
        shifted = self.scan('\n\n' + source)
        self.assertEqual([r['id'] for r in rows], [r['id'] for r in shifted])
        self.assertEqual([r['start_line'] + 2 for r in rows],
                         [r['start_line'] for r in shifted])

    def test_interleaved_class_and_function_owners_do_not_collide(self):
        rows = self.scan('''fun outer(x: Int) {
    class Local {
        fun call() = x
    }
}
class Local {
    fun outer(x: Int) {
        fun call() = x
    }
}
''')
        calls = [row for row in rows if row['name'] == 'call']
        prefix = self.path.as_posix() + '::'
        self.assertEqual([row['id'] for row in calls],
                         [prefix + 'outer(Int).Local.call()',
                          prefix + 'Local.outer(Int).call()'])
        self.assertEqual([row['owner'] for row in calls], ['Local', 'Local'])

    def test_owner_chain_ids_are_unique_and_stable_under_sibling_reorder(self):
        # Each fragment is a sibling declaration. Reorder both fragments and
        # nested sibling declarations without changing any declaration's ID.
        fragments = [
            '''class Host {
    fun outer(x: Int) {
        fun local() = x
        class Local {
            fun call() = x
        }
    }
    companion object {
        fun call() = 1
    }
}
''',
            '''fun outer(x: Int) {
    class Local {
        fun call() = x
    }
    fun call() = x
    val delegate = object : Runnable {
        override fun call() = x
    }
    val alternate = object : Runnable {
        override fun call() = x
    }
    val plain = object {
        fun call() = x
    }
}
''',
            '''class Local {
    fun outer(x: Int) {
        fun call() = x
    }
}
''',
            '''object Root {
    fun outer(x: Int) {
        fun call() = x
    }
}
''',
            '''fun outer(x: String) {
    fun call() = x
}
''',
            '''class Built {
    constructor(x: Int) { fun call() = x }
    constructor(x: String) { fun call() = x }
}
''',
            '''fun List<Int>.outer(x: Int) {
    fun call() = x
}
''',
        ]
        expected = {
            'Host.outer(Int)', 'Host.outer(Int).local()',
            'Host.outer(Int).Local.call()', 'Host.Companion.call()',
            'outer(Int)', 'outer(Int).Local.call()', 'outer(Int).call()',
            'outer(Int).<prop:delegate>.<object:Runnable>.call()',
            'outer(Int).<prop:alternate>.<object:Runnable>.call()',
            'outer(Int).<prop:plain>.<object>.call()',
            'Local.outer(Int)', 'Local.outer(Int).call()',
            'Root.outer(Int)', 'Root.outer(Int).call()',
            'outer(String)', 'outer(String).call()',
            'Built.constructor(Int).call()', 'Built.constructor(String).call()',
            'outer@List<Int>(Int)', 'outer@List<Int>(Int).call()',
        }
        original = self.scan(''.join(fragments))
        prefix = self.path.as_posix() + '::'
        self.assertEqual({row['id'] for row in original},
                         {prefix + suffix for suffix in expected})
        self.assertEqual(len(original), len({row['id'] for row in original}))
        self.assertTrue(all('#' not in row['id'] for row in original))
        shuffled = list(reversed(fragments))
        shuffled[5] = shuffled[5].replace(
            '    fun call() = x\n    val delegate = object',
            '    val delegate = object').replace(
            '    }\n}\n', '    }\n    fun call() = x\n}\n')
        self.assertEqual({row['id'] for row in self.scan('\n\n' + ''.join(shuffled))},
                         {row['id'] for row in original})

    def test_nested_function_separator_does_not_create_parent_nloc(self):
        without = self.scan('fun parent() {\n fun child() = 1\n val x = 2\n}\n')
        with_separator = self.scan('fun parent() {\n fun child() = 1;\n val x = 2\n}\n')
        self.assertEqual([row['nloc'] for row in with_separator],
                         [row['nloc'] for row in without])
        self.assertEqual([row['nloc'] for row in with_separator], [3, 1])
        shared = self.scan('fun parent() {\n fun child() = 1; val x = 2\n}\n')
        self.assertEqual([row['nloc'] for row in shared], [3, 1])

    def test_nested_named_function_excludes_parent_metrics_but_shared_line_survives(self):
        rows = self.scan('''fun outer() {
    fun inner() {
        if (true) println(1)
    }
    val x = 1
    fun declared(y: Int): Int
    return x
}
fun same() { fun nested() = if (true) 1 else 0; if (true) println(1) }
''')
        self.assertEqual([(r["name"], r["owner"], r["ccn"], r["nloc"]) for r in rows],
                         [("outer", "", 1, 4), ("inner", "", 2, 3),
                          ("same", "", 2, 1), ("nested", "", 2, 1)])

    def test_all_decision_nodes_and_lambda_belong_to_parent(self):
        rows = self.scan('''fun decisions(xs: List<Int>, a: Boolean, b: Boolean): Int {
    var total = 0
    if (a && b || !a) total++
    for (x in xs) total += x
    while (total < 10) total++
    do { total-- } while (total > 10)
    try { total++ } catch (e: Exception) { total-- }
    when (total) { 0, 1 -> total++; 2 -> total--; else -> total++ }
    val fallback = xs.firstOrNull() ?: 0
    xs.map { if (it > 0) it else 0 }
    return fallback
}''')
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["ccn"], 12)


if __name__ == "__main__":
    unittest.main()
