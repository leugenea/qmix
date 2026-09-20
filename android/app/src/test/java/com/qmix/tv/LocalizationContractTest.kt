package com.qmix.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory

class LocalizationContractTest {
    @Test
    fun russian_resources_match_every_translatable_default_name_and_placeholder() {
        val defaults = readStrings(resourceFile("values/strings.xml"))
            .filterValues { it.translatable }
        val russian = readStrings(resourceFile("values-ru/strings.xml"))

        assertEquals(defaults.keys, russian.keys)
        defaults.forEach { (name, entry) ->
            assertEquals("placeholder mismatch for $name", placeholders(entry.text), placeholders(russian.getValue(name).text))
            assertFalse("Russian translation for $name repeats English", entry.text == russian.getValue(name).text && name != "app_name")
        }
    }

    @Test
    fun formatted_dynamic_resources_cover_room_code_guest_url_and_http_status() {
        val defaults = readStrings(resourceFile("values/strings.xml"))

        assertEquals(listOf("%1\$s"), placeholders(defaults.getValue("room_title").text))
        assertEquals(listOf("%1\$s"), placeholders(defaults.getValue("invitation_qr_description").text))
        assertEquals(listOf("%1\$d"), placeholders(defaults.getValue("playback_stream_request_failed_http").text))
        assertEquals(listOf("%1\$d"), placeholders(defaults.getValue("playback_stream_seek_failed_http").text))
    }

    @Test
    fun literal_inventory_catches_user_copy_assigned_to_an_indirect_variable() {
        val source = """
            @Composable
            fun Screen() {
                val label = "Open settings"
                Text(label)
            }
        """.trimIndent()

        assertEquals(listOf("Open settings"), unclassifiedEnglishLiterals(source))
    }

    @Test
    fun literal_inventory_catches_user_copy_returned_from_a_helper_and_semantics() {
        val source = """
            fun label() = "Try another server"
            @Composable
            fun Screen() = Box(Modifier.semantics { contentDescription = label() })
        """.trimIndent()

        assertEquals(listOf("Try another server"), unclassifiedEnglishLiterals(source))
    }

    @Test
    fun exact_literal_inventory_rejects_allowlisted_internal_copy_reused_by_a_helper() {
        val source = """
            private const val apiField = "current"
            fun label() = "current"
        """.trimIndent()
        val expected = listOf(LiteralInventoryEntry("Example.kt", "current", 1, "identifiers and API fields"))

        assertEquals(
            listOf("Example.kt: current occurs 2 times, expected 1 (identifiers and API fields)"),
            inventoryViolations(mapOf("Example.kt" to source), expected),
        )
    }

    @Test
    fun exact_literal_inventory_rejects_allowlisted_internal_copy_reused_in_another_file() {
        val expected = listOf(LiteralInventoryEntry("Api.kt", "current", 1, "identifiers and API fields"))

        assertEquals(
            listOf("Screen.kt: unexpected literal current"),
            inventoryViolations(
                mapOf(
                    "Api.kt" to "private const val field = \"current\"",
                    "Screen.kt" to "fun label() = \"current\"",
                ),
                expected,
            ),
        )
    }

    @Test
    fun source_inventory_preserves_duplicate_basenames_and_their_literals() {
        val sourceRoot = Files.createTempDirectory("qmix-localization-inventory").toFile()
        try {
            File(sourceRoot, "feature/Shared.kt").apply {
                requireNotNull(parentFile).mkdirs()
                writeText("private const val field = \"current\"\nfun label() = \"Visible label\"")
            }
            File(sourceRoot, "network/Shared.kt").apply {
                requireNotNull(parentFile).mkdirs()
                writeText("private const val field = \"current\"")
            }
            val expected = listOf(
                LiteralInventoryEntry("feature/Shared.kt", "current", 1, "identifiers and API fields"),
                LiteralInventoryEntry("network/Shared.kt", "current", 1, "identifiers and API fields"),
            )

            val sources = sourceInventory(sourceRoot)

            assertEquals(setOf("feature/Shared.kt", "network/Shared.kt"), sources.keys)
            assertEquals(
                listOf("feature/Shared.kt: unexpected literal Visible label"),
                inventoryViolations(sources, expected),
            )
        } finally {
            sourceRoot.deleteRecursively()
        }
    }

    @Test
    fun production_kotlin_has_no_unclassified_english_like_literals() {
        val sourceRoot = projectFile("src/main/java/com/qmix/tv")
        val sources = sourceInventory(sourceRoot)
        val violations = inventoryViolations(sources, INTERNAL_LITERAL_INVENTORY)

        assertTrue("unclassified production literals: $violations", violations.isEmpty())
    }

    private fun sourceInventory(sourceRoot: File): Map<String, String> =
        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(sourceRoot).invariantSeparatorsPath to it.readText() }

    private fun unclassifiedEnglishLiterals(source: String): List<String> =
        kotlinStringLiterals(source)
            .filter { ENGLISH_LIKE.containsMatchIn(it) }
            .filterNot { literal -> INTERNAL_LITERAL_INVENTORY.any { it.literal == literal } }

    private fun inventoryViolations(
        sources: Map<String, String>,
        expected: List<LiteralInventoryEntry>,
    ): List<String> {
        val duplicateKeys = expected.groupingBy { it.file to it.literal }.eachCount().filterValues { it != 1 }
        require(duplicateKeys.isEmpty()) { "duplicate literal inventory keys: ${duplicateKeys.keys}" }
        val expectedByKey = expected.associateBy { it.file to it.literal }
        val actual = sources.flatMap { (file, source) ->
            kotlinStringLiterals(source)
                .filter { ENGLISH_LIKE.containsMatchIn(it) }
                .map { (file to it) }
        }.groupingBy { it }.eachCount()
        val unexpected = actual.keys
            .filterNot { it in expectedByKey }
            .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
            .map { (file, literal) -> "$file: unexpected literal $literal" }
        val countMismatches = expected.mapNotNull { entry ->
            val count = actual[entry.file to entry.literal] ?: 0
            if (count == entry.count) null else {
                "${entry.file}: ${entry.literal} occurs $count times, expected ${entry.count} (${entry.category})"
            }
        }
        return unexpected + countMismatches
    }

    private fun kotlinStringLiterals(source: String): List<String> {
        val literals = mutableListOf<String>()
        var index = 0
        while (index < source.length) {
            when {
                source.startsWith("//", index) -> {
                    index = source.indexOf('\n', index + 2).takeIf { it >= 0 } ?: source.length
                }
                source.startsWith("/*", index) -> {
                    index = source.indexOf("*/", index + 2).takeIf { it >= 0 }?.plus(2) ?: source.length
                }
                source.startsWith("\"\"\"", index) -> {
                    val end = source.indexOf("\"\"\"", index + 3)
                    if (end < 0) break
                    literals += source.substring(index + 3, end)
                    index = end + 3
                }
                source[index] == '\"' -> {
                    val start = ++index
                    var escaped = false
                    while (index < source.length && (escaped || source[index] != '\"')) {
                        escaped = !escaped && source[index] == '\\'
                        if (source[index] != '\\') escaped = false
                        index++
                    }
                    if (index >= source.length) break
                    literals += source.substring(start, index)
                    index++
                }
                else -> index++
            }
        }
        return literals
    }

    private fun placeholders(value: String): List<String> =
        Regex("""%(?:\d+\$)?[sd]""").findAll(value).map { it.value }.toList()

    private fun readStrings(file: File): Map<String, Entry> {
        assertTrue("missing resource file: $file", file.isFile)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        return (0 until document.documentElement.childNodes.length)
            .mapNotNull { document.documentElement.childNodes.item(it) as? Element }
            .filter { it.tagName == "string" }
            .associate { element ->
                element.getAttribute("name") to Entry(
                    element.textContent,
                    element.getAttribute("translatable") != "false",
                )
            }
    }

    private fun resourceFile(path: String): File = projectFile("src/main/res/$path")

    private fun projectFile(path: String): File {
        val direct = File(path)
        if (direct.exists()) return direct
        return File("app", path)
    }

    private data class Entry(val text: String, val translatable: Boolean)
    private data class LiteralInventoryEntry(
        val file: String,
        val literal: String,
        val count: Int,
        val category: String,
    )

    private companion object {
        val ENGLISH_LIKE = Regex("[A-Za-z]{2}")
        val INTERNAL_LITERAL_INVENTORY = buildList {
            addInventory(
                "EndpointSettings.kt",
                "URLs and schemes",
                "http://" to 2,
                "http" to 1,
                "https" to 1,
            )
            addInventory(
                "EndpointSettings.kt",
                "tooling identifiers",
                "UseKtx" to 2,
                "ApplySharedPref" to 2,
            )
            addInventory(
                "EndpointSettings.kt",
                "internal diagnostics",
                "Could not persist endpoint settings" to 1,
                "Could not persist HTTP warning acknowledgement" to 1,
            )
            addInventory(
                "EndpointSettings.kt",
                "identifiers and API fields",
                "qmix_endpoint_settings" to 1,
                "backend_url" to 1,
                "guest_origin" to 1,
                "http_warning_acknowledged" to 1,
            )
            addInventory(
                "ExoPlayerBackend.kt",
                "internal diagnostics",
                "Playback must be created and operated on the main thread" to 1,
                "Media3 playback error \$errorCode" to 2,
            )
            addInventory("Media3PlaybackEngine.kt", "internal diagnostics", "PlaybackEngine is released" to 1)
            addInventory(
                "OkHttpRoomEventStream.kt",
                "protocol values",
                "rooms" to 1,
                "events" to 1,
                "Accept" to 1,
                "text/event-stream" to 1,
            )
            addInventory(
                "QMixApplication.kt",
                "identifiers and API fields",
                "rooms" to 1,
                "current" to 1,
                "stream" to 1,
                "qmix-room-sync" to 1,
                "qmix-room-command" to 1,
            )
            addInventory(
                "QMixApplication.kt",
                "internal diagnostics",
                "An activity host-session provider is already installed" to 1,
                "Main playback thread is unavailable" to 1,
            )
            addInventory(
                "QMixLogging.kt",
                "log categories",
                "QMix" to 1,
                "app/host-session" to 1,
                "room-api/creation" to 1,
                "room-sync/sse/reconnect" to 1,
                "playback/lifecycle" to 1,
                "session-state" to 1,
                "create-room" to 1,
                "observer-notification" to 1,
                "sse-connection" to 1,
                "reconnect" to 1,
                "playback-failure" to 1,
                "network" to 1,
                "http-status" to 1,
                "unknown" to 1,
                "callback-failure" to 1,
                "component=" to 1,
                " operation=" to 1,
                " cause=" to 1,
            )
            addInventory(
                "RoomRepository.kt",
                "protocol values",
                "queue_snapshot" to 1,
                "queue_updated" to 1,
                "track_changed" to 1,
                "player_state" to 1,
            )
            addInventory(
                "LiveRoomScreen.kt",
                "test tags",
                "room-start" to 1,
                "room-next" to 1,
                "room-invite" to 1,
                "playback-play-pause" to 1,
                "playback-seek-back" to 1,
                "playback-seek-forward" to 1,
                "playback-retry" to 1,
                "queue-list" to 1,
                "queue-track-\${track.id}" to 1,
            )
            addInventory(
                "RoomApiClient.kt",
                "internal diagnostics",
                "RoomCredentials(code=\$code, hostToken=<redacted>, relativeGuestUrl=<server-provided>)" to 1,
            )
            addInventory(
                "RoomApiClient.kt",
                "identifiers and API fields",
                "rooms" to 3,
                "code" to 2,
                "host_token" to 1,
                "url" to 2,
                "current" to 3,
                "track_id" to 2,
                "pos_sec" to 2,
                "state" to 2,
                "title" to 3,
                "artist" to 3,
                "queue" to 1,
                "id" to 1,
                "duration_sec" to 1,
                "resolved_by" to 1,
                "skip" to 1,
                "X-Host-Token" to 1,
            )
            addInventory("HostSessionController.kt", "identifiers", "qmix-room-request" to 1)
            addInventory(
                "LiveRoomPresentation.kt",
                "nonlinguistic formatting",
                "\$hours:\${minutes.toString().padStart(2, '0')}:\${seconds.toString().padStart(2, '0')}" to 1,
                "\$minutes:\${seconds.toString().padStart(2, '0')}" to 1,
            )
        }

        fun MutableList<LiteralInventoryEntry>.addInventory(
            file: String,
            category: String,
            vararg literals: Pair<String, Int>,
        ) {
            literals.forEach { (literal, count) -> add(LiteralInventoryEntry(file, literal, count, category)) }
        }
    }
}
