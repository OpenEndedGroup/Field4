package afield

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the keybinding spec vocabulary shared by `~/.afield/keybindings.yaml`, the Monaco
 * registrations and the canvas key matcher ([KeySpec]).
 *
 * The load-bearing property is that every default Field ships survives a parse → format round-trip
 * unchanged, because the file the user edits is generated from those defaults: a spec that did not
 * round-trip would mean the file said one thing and the app did another.
 */
class KeySpecTest {

    @Test
    fun parsesModifiersAndKeys() {
        val chord = assertNotNull(KeySpec.parse("cmd+shift+d"))
        assertTrue(chord.ctrlCmd)
        assertTrue(chord.shift)
        assertTrue(!chord.alt)
        assertEquals("d", chord.key)
    }

    @Test
    fun acceptsTheSpellingsAHumanMightWrite() {
        // ctrl/control/command/meta/mod are all the same platform-sensitive modifier; option is alt.
        val expected = KeySpec.parse("cmd+alt+p")
        for (spec in listOf("ctrl+alt+p", "control+option+p", "meta+opt+p", "mod+alt+p", "CMD+ALT+P")) {
            assertEquals(expected, KeySpec.parse(spec), "spelling '$spec'")
        }
        // Separators are interchangeable, and may be padded with spaces.
        for (spec in listOf("cmd shift d", "cmd-shift-d", "cmd,shift,d", "CMD + SHIFT + D", "cmd - shift - d")) {
            assertEquals(KeySpec.parse("cmd+shift+d"), KeySpec.parse(spec), "separator in '$spec'")
        }
    }

    @Test
    fun parsesPunctuationKeysIncludingSeparatorCharacters() {
        // `,` `-` and `+` double as separators, so these are the cases a naive split would break.
        assertEquals("," , assertNotNull(KeySpec.parse("cmd+,")).key)
        assertEquals("-" , assertNotNull(KeySpec.parse("cmd+-")).key)
        assertEquals("=" , assertNotNull(KeySpec.parse("cmd+plus")).key)
        assertEquals("]" , assertNotNull(KeySpec.parse("alt+]")).key)
        assertEquals("'" , assertNotNull(KeySpec.parse("cmd+'")).key)
        assertEquals("/" , assertNotNull(KeySpec.parse("cmd+/")).key)
    }

    @Test
    fun aShiftedCharacterMeansShiftPlusItsKey() {
        val question = assertNotNull(KeySpec.parse("?"))
        assertTrue(question.shift)
        assertEquals("/", question.key)
        assertEquals(KeySpec.parse("shift+/"), question)
        // ...and it reports the character itself, so the matcher can also compare against event.key
        // on layouts where `?` is not physically Shift+Slash.
        assertEquals("?", KeySpec.literalChar(question))
    }

    @Test
    fun rejectsNonsense() {
        assertNull(KeySpec.parse(""))
        assertNull(KeySpec.parse("   "))
        assertNull(KeySpec.parse("cmd"), "a modifier with no key is not a shortcut")
        assertNull(KeySpec.parse("cmd+nope"))
        assertNull(KeySpec.parse("f99"))
    }

    @Test
    fun formatIsCanonicalAndOrderIndependent() {
        assertEquals("cmd+alt+shift+k", KeySpec.canonicalize("shift+alt+cmd+k"))
        assertEquals("cmd+alt+shift+k", KeySpec.canonicalize("CMD + SHIFT + ALT + K"))
        assertEquals("alt+enter", KeySpec.canonicalize("Option+Return"))
        assertEquals("f12", KeySpec.canonicalize("F12"))
        assertEquals("shift+/", KeySpec.canonicalize("?"))
    }

    /**
     * Every default that ships in Field's command tables, as written in the source. If one of these
     * stops round-tripping, the generated keybindings.yaml would misreport that command's shortcut.
     */
    private val shippedDefaults = listOf(
        // canvas + palette commands
        "n", "cmd+backspace", "cmd+delete", "cmd+c", "cmd+v", "cmd+t", "cmd+shift+d", "cmd+h",
        "cmd+shift+m", "alt+]", "alt+[", "g", "u",
        // editor actions
        "alt+shift+p", "cmd+3", "cmd+4", "f12", "alt+o", "alt+shift+o", "cmd+'", "cmd+.", "cmd+,",
        "alt+enter", "alt+shift+enter", "alt+0", "alt+1", "alt+up", "alt+shift+up", "alt+down",
        "alt+shift+down",
        // global chords
        "cmd+shift+p", "f1", "cmd+p", "cmd+/", "shift+f1", "cmd+shift+f9"
    )

    @Test
    fun everyShippedDefaultRoundTrips() {
        for (spec in shippedDefaults) {
            assertEquals(spec, KeySpec.canonicalize(spec), "default '$spec' did not round-trip")
        }
    }

    @Test
    fun everyShippedDefaultHasADomCodeAndAMonacoKeyCodeName() {
        for (spec in shippedDefaults) {
            val chord = assertNotNull(KeySpec.parse(spec), "could not parse default '$spec'")
            assertNotNull(KeySpec.domCode(chord), "no DOM code for '$spec' — the canvas could not match it")
            assertTrue(
                KeySpec.monacoKeyCodeNames(chord).isNotEmpty(),
                "no Monaco KeyCode name for '$spec' — the editor could not bind it"
            )
        }
    }

    @Test
    fun domCodesMatchTheBrowsersNames() {
        assertEquals("KeyN", KeySpec.domCode(assertNotNull(KeySpec.parse("n"))))
        assertEquals("Digit3", KeySpec.domCode(assertNotNull(KeySpec.parse("cmd+3"))))
        assertEquals("BracketRight", KeySpec.domCode(assertNotNull(KeySpec.parse("alt+]"))))
        // The browser says ArrowUp where Monaco says UpArrow; both spellings have to be right.
        val up = assertNotNull(KeySpec.parse("alt+up"))
        assertEquals("ArrowUp", KeySpec.domCode(up))
        assertEquals(listOf("UpArrow"), KeySpec.monacoKeyCodeNames(up))
    }

    @Test
    fun labelsFollowThePlatform() {
        val chord = assertNotNull(KeySpec.parse("cmd+alt+shift+d"))
        assertEquals("Cmd+Opt+Shift+D", KeySpec.label(chord, mac = true))
        assertEquals("Ctrl+Alt+Shift+D", KeySpec.label(chord, mac = false))
    }

    @Test
    fun labelsShowShiftedPunctuationAsTheCharacterItTypes() {
        // The cheatsheet should say `?`, not `Shift+/` — but `Shift+D`, never bare `D`.
        assertEquals("?", KeySpec.label(assertNotNull(KeySpec.parse("shift+/")), mac = true))
        assertEquals("?", KeySpec.label(assertNotNull(KeySpec.parse("?")), mac = true))
        assertEquals("Cmd+?", KeySpec.label(assertNotNull(KeySpec.parse("cmd+shift+/")), mac = true))
        assertEquals("Shift+D", KeySpec.label(assertNotNull(KeySpec.parse("shift+d")), mac = true))
        assertEquals("Shift+F4", KeySpec.label(assertNotNull(KeySpec.parse("shift+f4")), mac = true))
        // An unshifted punctuation key still reads as itself.
        assertEquals("Cmd+,", KeySpec.label(assertNotNull(KeySpec.parse("cmd+,")), mac = true))
    }

    /**
     * A spec that names two keys is a two-key chord: each key ends a chord, and the modifiers before
     * it belong to it. Before chords existed these were refused; before *that* they parsed as
     * whichever key came last, so `cmd+k cmd+s` silently became Cmd+S.
     */
    @Test
    fun twoKeyChordsParseAsSequences() {
        val expected = listOf(KeyChord(ctrlCmd = true, key = "k"), KeyChord(ctrlCmd = true, key = "s"))
        for (spec in listOf("cmd+k cmd+s", "cmd+k,cmd+s", "ctrl+K  Command+S", "cmd k cmd s")) {
            assertEquals(expected, KeySpec.parseSequence(spec), "'$spec'")
            assertEquals("cmd+k cmd+s", KeySpec.canonicalize(spec))
            // `parse` is the single-chord view and says no rather than returning half of it.
            assertNull(KeySpec.parse(spec), "'$spec' parsed as one keystroke")
            assertNull(KeySpec.explainFailure(spec), "'$spec' was called a failure")
        }
        assertEquals(listOf(KeyChord(key = "a"), KeyChord(key = "b")), KeySpec.parseSequence("a b"))
        // The same key twice is a chord too, not a plain Cmd+K.
        assertEquals("cmd+k cmd+k", KeySpec.canonicalize("cmd+k cmd+k"))
        // A separator key survives a chord boundary.
        assertEquals("cmd+, cmd+s", KeySpec.canonicalize("cmd+, cmd+s"))
        assertEquals("cmd+k shift+/", KeySpec.canonicalize("cmd+k ?"))
        assertEquals("Cmd+K Cmd+S", KeySpec.labelSequence(KeySpec.parseSequence("cmd+k cmd+s")!!, mac = true))
        assertEquals("Ctrl+K S", KeySpec.labelSequence(KeySpec.parseSequence("cmd+k s")!!, mac = false))
    }

    /**
     * Every spec that parsed as one chord before chords existed still means exactly that one chord.
     * This is why no separator change was needed: each of these has exactly one key.
     */
    @Test
    fun singleChordSpellingsAreUnchanged() {
        for ((spec, canonical) in listOf(
            "cmd shift d" to "cmd+shift+d", "cmd + k" to "cmd+k", "cmd+ k" to "cmd+k", "cmd, shift, d" to "cmd+shift+d",
            "cmd+-" to "cmd+-", "cmd+," to "cmd+,", "cmd+plus" to "cmd+=", "?" to "shift+/", "alt+]" to "alt+]",
        )) {
            assertEquals(canonical, KeySpec.canonicalize(spec), "'$spec'")
            assertNotNull(KeySpec.parse(spec), "'$spec' stopped being a single chord")
        }
    }

    @Test
    fun threeKeysAndDanglingModifiersAreRefused() {
        for (spec in listOf("cmd+k cmd+s cmd+d", "a b c")) {
            assertNull(KeySpec.parseSequence(spec), "'$spec' should not parse")
            val why = assertNotNull(KeySpec.explainFailure(spec))
            assertTrue(why.contains("at most two keys"), "unhelpful explanation for '$spec': $why")
        }
        val why = assertNotNull(KeySpec.explainFailure("cmd+k cmd"))
        assertTrue(why.contains("no key"), why)
    }

    @Test
    fun failuresSayWhichPartIsWrong() {
        assertTrue(assertNotNull(KeySpec.explainFailure("cmd+shift+nope")).contains("'nope'"))
        assertTrue(assertNotNull(KeySpec.explainFailure("cmd+shift")).contains("no key"))
        assertTrue(assertNotNull(KeySpec.explainFailure("   ")).contains("empty"))
        // ...and nothing that parses gets an explanation.
        for (spec in shippedDefaults) assertNull(KeySpec.explainFailure(spec), "'$spec' was called a failure")
    }

    @Test
    fun scopeNormalizationFallsBackToBoth() {
        assertEquals(KeyScope.canvas, KeyScope.normalize("canvas"))
        assertEquals(KeyScope.editor, KeyScope.normalize(" Editor "))
        assertEquals(KeyScope.both, KeyScope.normalize(null))
        assertEquals(KeyScope.both, KeyScope.normalize("nonsense"))
    }

    /**
     * A chord that types a character must be recognizable as such, because an editor binding for one
     * swallows the keystroke: `new-box` is bare `n`, and binding that inside Monaco made `n`
     * impossible to type. Cmd or Alt makes it a shortcut; Shift alone does not, since Shift+N types N.
     */
    @Test
    fun knowsWhichChordsWouldTypeACharacter() {
        for (spec in listOf("n", "shift+n", "1", ",", "/", "?", "[", "'", "-", "=", "`")) {
            assertTrue(
                KeySpec.typesACharacter(assertNotNull(KeySpec.parse(spec))),
                "'$spec' types a character, so it must never become an editor binding"
            )
        }
        for (spec in listOf("cmd+n", "alt+n", "cmd+shift+n", "cmd+,", "alt+]", "f1", "f12", "enter",
                            "escape", "tab", "up", "down", "backspace", "delete", "space", "home",
                            "pageup", "shift+f4")) {
            assertTrue(
                !KeySpec.typesACharacter(assertNotNull(KeySpec.parse(spec))),
                "'$spec' types nothing, so it is safe to bind in the editor"
            )
        }
    }

    /**
     * Every editor-scoped default Field ships must be safe to bind there. If someone adds a bare-letter
     * editor shortcut, this fails rather than shipping an unusable letter.
     */
    @Test
    fun noShippedEditorDefaultTypesACharacter() {
        val editorDefaults = listOf(
            "alt+shift+p", "cmd+3", "cmd+4", "f12", "alt+o", "alt+shift+o", "cmd+'", "cmd+.", "cmd+,",
            "alt+enter", "alt+shift+enter", "alt+0", "alt+1", "alt+up", "alt+shift+up", "alt+down",
            "alt+shift+down"
        )
        for (spec in editorDefaults) {
            val chord = assertNotNull(KeySpec.parse(spec))
            assertTrue(!KeySpec.typesACharacter(chord), "editor default '$spec' would eat a keystroke")
        }
    }
}
