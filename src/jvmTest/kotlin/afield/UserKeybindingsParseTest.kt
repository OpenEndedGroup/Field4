package afield

import org.yaml.snakeyaml.Yaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The shapes a hand-edited `keybindings.yaml` actually arrives in.
 *
 * [UserKeybindingsManager] reads the real file in `~/.afield`, so these exercise the same parsing
 * decisions against the same YAML loader rather than the singleton: what a list vs. a bare string
 * means, that `""` unbinds rather than reverting to the default, and that a `cmd+,` binding survives
 * a loader that is perfectly entitled to hand back a comma-containing scalar.
 */
class UserKeybindingsParseTest {

    /** Mirrors the manager's `bindings:`-or-bare-mapping rule and its per-value canonicalization. */
    private fun parse(yaml: String): Pair<Map<String, List<String>>, List<String>> {
        val problems = mutableListOf<String>()
        val root = Yaml().load<Any?>(yaml)
        @Suppress("UNCHECKED_CAST")
        val map = (root as? Map<Any?, Any?>)?.let { outer ->
            (outer["bindings"] as? Map<Any?, Any?>) ?: outer
        } ?: emptyMap()

        val bindings = map.entries.associate { (rawKey, rawValue) ->
            val id = rawKey?.toString()?.trim().orEmpty()
            val candidates = when (rawValue) {
                null -> emptyList()
                is String -> if (rawValue.isBlank()) emptyList() else listOf(rawValue)
                is List<*> -> rawValue.mapNotNull { it?.toString() }.filter { it.isNotBlank() }
                else -> listOf(rawValue.toString())
            }
            id to candidates.mapNotNull { candidate ->
                KeySpec.canonicalize(candidate).also {
                    if (it == null) problems.add("'$candidate' (for '$id') is not a shortcut Field understands")
                }
            }
        }
        return bindings to problems
    }

    @Test
    fun readsTheGeneratedShape() {
        val (bindings, problems) = parse(
            """
            bindings:
              new-box: "n"
              delete-box: ["cmd+backspace", "cmd+delete"]
              back-next-property: "cmd+,"
              export-ipynb: ""
            """.trimIndent()
        )
        assertTrue(problems.isEmpty(), "unexpected problems: $problems")
        assertEquals(listOf("n"), bindings["new-box"])
        assertEquals(listOf("cmd+backspace", "cmd+delete"), bindings["delete-box"])
        // The comma key has to survive the round trip — it is also the separator character.
        assertEquals(listOf("cmd+,"), bindings["back-next-property"])
        // An explicit "" means unbound, which is different from the line being absent.
        assertEquals(emptyList(), bindings["export-ipynb"])
    }

    @Test
    fun acceptsABareMappingWithoutTheBindingsWrapper() {
        val (bindings, problems) = parse("new-box: cmd+alt+b\nedit-hashtag: cmd+h\n")
        assertTrue(problems.isEmpty(), "unexpected problems: $problems")
        assertEquals(listOf("cmd+alt+b"), bindings["new-box"])
        assertEquals(listOf("cmd+h"), bindings["edit-hashtag"])
    }

    @Test
    fun unquotedYamlScalarsStillParse() {
        // Someone hand-editing will not bother with quotes, and YAML hands some of these back as
        // non-strings — `f7` stays a string but a bare `3` would arrive as an Int.
        val (bindings, problems) = parse("bindings:\n  search-docs-and-code: F7\n  ai-chat: cmd+3\n")
        assertTrue(problems.isEmpty(), "unexpected problems: $problems")
        assertEquals(listOf("f7"), bindings["search-docs-and-code"])
        assertEquals(listOf("cmd+3"), bindings["ai-chat"])
    }

    @Test
    fun reportsUnparseableShortcutsAndKeepsTheRest() {
        val (bindings, problems) = parse(
            """
            bindings:
              new-box: cmd+shift+nope
              edit-hashtag: cmd+h
            """.trimIndent()
        )
        assertEquals(1, problems.size, "expected exactly one problem, got $problems")
        assertTrue(problems.single().contains("cmd+shift+nope"))
        // A bad line must not take a good one down with it.
        assertEquals(listOf("cmd+h"), bindings["edit-hashtag"])
        assertEquals(emptyList(), bindings["new-box"])
    }

    @Test
    fun aNullValueUnbinds() {
        val (bindings, problems) = parse("bindings:\n  new-box:\n")
        assertTrue(problems.isEmpty(), "unexpected problems: $problems")
        assertEquals(emptyList(), bindings["new-box"])
    }

    @Test
    fun everyGeneratedDefaultValueIsValidYamlAndParsesBack() {
        // The manager double-quotes each spec; make sure the quoting survives a real YAML round trip
        // for the awkward ones (a quote character, a backslash, a bracket, a comma).
        val specs = listOf("cmd+'", "cmd+\\", "alt+]", "cmd+,", "shift+/")
        val yaml = buildString {
            append("bindings:\n")
            for ((index, spec) in specs.withIndex()) {
                val quoted = "\"" + spec.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                append("  command-$index: $quoted\n")
            }
        }
        val (bindings, problems) = parse(yaml)
        assertTrue(problems.isEmpty(), "unexpected problems: $problems")
        for ((index, spec) in specs.withIndex()) {
            assertEquals(listOf(spec), bindings["command-$index"], "spec '$spec' did not survive YAML")
        }
    }

    @Test
    fun commandIdsThatStartWithADashSurviveQuoting() {
        // `-copy` is a real command id, and a bare `-copy:` key is a YAML trap.
        val yaml = "bindings:\n  \"-copy\": cmd+shift+d\n"
        val (bindings, problems) = parse(yaml)
        assertTrue(problems.isEmpty(), "unexpected problems: $problems")
        assertEquals(listOf("cmd+shift+d"), bindings["-copy"])
        assertNotNull(bindings["-copy"])
    }
}
