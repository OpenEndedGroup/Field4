package afield.server

import afield.stdlib.KeyframeScope
import afield.stdlib.everyKeyGlob
import afield.four.util.CameraPose
import afield.stdlib.linalg.Quaternionf
import afield.stdlib.linalg.Vector3f
import afield.stdlib.stackSLString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The wire half of the keyframe browser: what the iframe actually receives.
 *
 * The claims worth testing here are the ones about *reuse*, because those are the ones that fail
 * silently. A saved `Vector3f` is supposed to come back through `.h` as the same axis-widget SVG a
 * live one would produce, and the diff panel is supposed to be `KeyDiff.toHTML` rather than a second
 * implementation of it. Both would "work" — producing something plausible on screen — if they had
 * quietly fallen back to `toString()`, which is exactly how the `DataFrame` printer regression
 * described in [afield.ValuePrinterRegistry] went unnoticed for eight months. So these assert on the
 * markup that only the real path can produce.
 */
class KeyframeBrowserProtocolTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun tempDir(): Path = Files.createTempDirectory("keyframe-browser-protocol-test")

    private fun save(dir: Path, name: String, vararg values: Pair<String, Any?>, note: String? = null) {
        val scope = KeyframeScope()
        scope.saveKey(dir.resolve(name), scope.Key(values.toMap().toMutableMap()), note)
    }

    private fun reply(
        dir: Path,
        op: String,
        scope: KeyframeScope? = null,
        slot: String? = null,
        fileId: Int? = null,
        otherId: Int? = null,
        // Defaulted to the suffix-bearing glob the fixtures above were written for, so the pattern
        // tests can vary it without touching them. The *product's* default is `everyKeyGlob`.
        //
        // A `//` comment and not a KDoc block on purpose: KDocExtractor.parseDeclaration cannot read
        // a parameter, so a block here is dropped and its prose is published under whatever symbol
        // parses next. See dev_docs/kdoc_reference_contract.md.
        pattern: String = "**/*.json"
    ): JsonObject = json.parseToJsonElement(
        keyframeBrowserRequestForTesting(dir, pattern, op, scope, slot, fileId, otherId)
    ).jsonObject

    /** The open payload: the header numbers, the file list and the slot pivot, all in one message. */
    @Test
    fun openCarriesTheCollectionHeaderFilesAndSlots() {
        val dir = tempDir()
        save(dir, "warm.json", "glow@radius" to 2f, "tint" to Vector3f(1f, 0f, 0f), note = "warmer rim")
        save(dir, "cool.json", "glow@radius" to 9f, "tint" to Vector3f(0f, 0f, 1f))

        val out = reply(dir, "open")
        assertTrue(out["ok"]!!.jsonPrimitive.boolean)
        assertEquals(2, out["fileCount"]!!.jsonPrimitive.int)
        assertEquals(2, out["versionCount"]!!.jsonPrimitive.int)
        assertEquals(2, out["slotCount"]!!.jsonPrimitive.int)
        assertFalse(out["hasScope"]!!.jsonPrimitive.boolean, "no scope was passed")

        val files = out["files"]!!.jsonArray.map { it.jsonObject["relative"]!!.jsonPrimitive.content }
        assertEquals(listOf("cool.json", "warm.json"), files, "files come back sorted by path")

        val slots = out["slots"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("glow@radius", "tint"), slots.map { it["name"]!!.jsonPrimitive.content })

        // The polymorphic display union has to arrive tagged, or the page cannot branch on it.
        val radius = slots.first { it["name"]!!.jsonPrimitive.content == "glow@radius" }
        assertEquals("range", radius["display"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("2 – 9", radius["summaryText"]!!.jsonPrimitive.content)
        assertEquals(
            "components",
            slots.first { it["name"]!!.jsonPrimitive.content == "tint" }["display"]!!
                .jsonObject["type"]!!.jsonPrimitive.content
        )
    }

    /** With a scope, Apply is offered and scope-only slots appear. */
    @Test
    fun openReportsALiveScope() {
        val dir = tempDir()
        save(dir, "a.json", "r" to 2f)
        val scope = KeyframeScope()
        scope.floatTarget("r", { 2f }, {})
        scope.floatTarget("never-saved", { 1f }, {})

        val out = reply(dir, "open", scope = scope)
        assertTrue(out["hasScope"]!!.jsonPrimitive.boolean)
        assertContains(
            out["slots"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content },
            "never-saved"
        )
    }

    /**
     * The reuse claim, asserted on markup only `.h` produces.
     *
     * `renderVec3Thumbnail` draws an isometric arrow with a fixed-pixel `x`/`y`/`z` reference frame,
     * so an axis label and the arrow's `#6cf` stroke are both present in a real render and both
     * absent from any `toString()` fallback — which for a `Vector3f` would be plain text.
     */
    @Test
    fun aSavedVectorIsRenderedByTheSameVisualizerALiveOneUses() {
        val dir = tempDir()
        save(dir, "a.json", "p" to Vector3f(1f, 2f, 3f))

        val html = reply(dir, "slot", slot = "p")["html"]!!.jsonPrimitive.content
        assertContains(html, "<svg", message = "a Vector3f should reach `.h`, which draws it")
        assertContains(html, "#6cf", message = "the arrow colour ValueVisualizer uses for a vector")
        assertContains(html, "a.json", message = "each file is named beside its value")
    }

    /** StackSL source is shown as source, not as the wrapper's `toString`. */
    @Test
    fun stackSlSourceIsShownAsCode() {
        val dir = tempDir()
        save(dir, "a.json", "field" to stackSLString("x y +\ndup *"))

        val html = reply(dir, "slot", slot = "field")["html"]!!.jsonPrimitive.content
        assertContains(html, "x y +")
        assertContains(html, "dup *")
        assertFalse(html.contains("StackSLString"), "the wrapper's class name must not leak into the panel")
    }

    /** A file the slot is absent from says so rather than being left out of the table. */
    @Test
    fun theSlotPanelListsFilesTheSlotIsMissingFrom() {
        val dir = tempDir()
        save(dir, "has.json", "r" to 1f)
        save(dir, "hasnt.json", "other" to 1f)

        val html = reply(dir, "slot", slot = "r")["html"]!!.jsonPrimitive.content
        assertContains(html, "hasnt.json")
        assertContains(html, "absent")
    }

    /** The version rail appears for a file saved more than once, with one tick per save. */
    @Test
    fun theSlotPanelDrawsAVersionRailForARepeatedlySavedFile() {
        val dir = tempDir()
        val scope = KeyframeScope()
        val path = dir.resolve("looks.json")
        listOf(1f, 4f, 9f).forEachIndexed { i, v ->
            scope.saveKey(path, scope.Key(mutableMapOf("r" to v)), "save $i")
        }

        val html = reply(dir, "slot", slot = "r")["html"]!!.jsonPrimitive.content
        assertContains(html, "across saves")
        assertEquals(3, Regex("kb-tick").findAll(html).count(), "one tick per save")
        assertContains(html, "1 – 9 over 3 saves")
        assertContains(html, "save 2", message = "the manifest note rides along in the tick tooltip")
    }

    /**
     * The contents panel: one row per slot, so a plain click on a key answers "what is in this"
     * without going via a diff.
     */
    @Test
    fun keyReturnsEverySlotOfOneFileWithItsSaveMetadata() {
        val dir = tempDir()
        save(dir, "a.json", "r" to 2f, "p" to Vector3f(1f, 2f, 3f), "label" to "warm", note = "first pass")

        val out = reply(dir, "key", fileId = 0)
        assertEquals("a.json", out["title"]!!.jsonPrimitive.content)
        val html = out["html"]!!.jsonPrimitive.content
        listOf("r", "p", "label").forEach { assertContains(html, ">$it<", message = "slot $it should have a row") }
        assertContains(html, "schema 0 · 3 slots · 1 save", message = "singular, and the counts a Key alone cannot know")
        assertContains(html, "first pass", message = "the save note")
        assertContains(html, "<svg", message = "an ordinary vector still goes through `.h`")
    }

    /**
     * The one place the contents panel deliberately does *not* use `.h`.
     *
     * `.h` on a `CameraPose` reflects the data class and renders seven derived accessors with four
     * vector thumbnails. That is right when the pose is what you are looking at — the slot panel
     * still does it — and wrong when it is one slot of five, because it pushes the other four off
     * the panel. Measured: the same key card went from 9905 to 2200 characters and 4 SVGs to 1.
     */
    @Test
    fun aCameraPoseCollapsesInTheContentsPanelButNotInTheSlotPanel() {
        val dir = tempDir()
        val pose = CameraPose(Quaternionf(), Vector3f(3f, 1.5f, 2f), 5.2f)
        save(dir, "a.json", "camera" to pose, "r" to 2f)

        val card = reply(dir, "key", fileId = 0)["html"]!!.jsonPrimitive.content
        assertContains(card, "cameraPose(position=(3, 1.5, 2), focus=5.2)")
        assertContains(card, "open the slot row", message = "the compact line has to say where the full one is")
        assertFalse(card.contains("focusDistance"), "the reflected accessor list is what we are collapsing")

        // ...and the slot panel, which is where that click lands, still renders it in full.
        val panel = reply(dir, "slot", slot = "camera")["html"]!!.jsonPrimitive.content
        assertContains(panel, "focusDistance", message = "the slot panel keeps the full `.h` rendering")
        assertContains(panel, "<svg")
    }

    /** An empty key file says so rather than rendering an empty table. */
    @Test
    fun aKeyFileWithNoValuesSaysSo() {
        val dir = tempDir()
        val scope = KeyframeScope()
        scope.saveKey(dir.resolve("empty.json"), scope.Key(mutableMapOf()), "nothing captured")

        val html = reply(dir, "key", fileId = 0)["html"]!!.jsonPrimitive.content
        assertContains(html, "holds no values")
    }

    /**
     * The diff panel is `KeyDiff.toHTML`, not a reimplementation — checked on the card heading that
     * only `KeyDiff` emits, and on the `field-md` wrapper `FieldMarkdown.card` puts around it.
     */
    @Test
    fun diffIsTheExistingKeyDiffRendering() {
        val dir = tempDir()
        save(dir, "a.json", "r" to 1f, "gone" to 2f)
        save(dir, "b.json", "r" to 5f, "added" to 3f)

        val out = reply(dir, "diff", fileId = 0, otherId = 1)
        assertEquals("a.json → b.json", out["title"]!!.jsonPrimitive.content)
        val html = out["html"]!!.jsonPrimitive.content
        assertContains(html, "field-md", message = "the card wrapper KeyDiff renders into")
        assertContains(html, "Key diff", message = "KeyDiff's own heading")
        assertContains(html, "3 changes", message = "one changed, one removed, one added")
    }

    /** `updateCode` comes back as pasteable Kotlin with the float suffix that keeps types honest. */
    @Test
    fun codeReturnsPasteableKotlin() {
        val dir = tempDir()
        save(dir, "a.json", "glow@radius" to 2.5f)

        val text = reply(dir, "code", fileId = 0)["text"]!!.jsonPrimitive.content
        assertContains(text, "key.updateAndApply {")
        assertContains(text, """this["glow@radius"] = 2.5f""")
    }

    /** Apply without a scope is refused with an actionable message rather than silently doing nothing. */
    @Test
    fun applyWithoutAScopeIsRefusedAndSaysWhy() {
        val dir = tempDir()
        save(dir, "a.json", "r" to 1f)

        val out = reply(dir, "apply", fileId = 0)
        assertFalse(out["ok"]!!.jsonPrimitive.boolean)
        assertContains(out["error"]!!.jsonPrimitive.content, "no live scope")
    }

    /**
     * A collection shaped like a real one: six keys named `a b c d d2 d3` and the `.DS_Store` macOS
     * leaves behind. Every other fixture in this file is named `warm.json` and friends, eight
     * characters and up, which is precisely why none of them could reproduce a one-character glob
     * losing four keys.
     */
    private fun humansKeyDirectory(): Path {
        val dir = tempDir()
        listOf("a", "b", "c", "d", "d2", "d3").forEachIndexed { i, n ->
            save(dir, n, "glow@radius" to (i + 1).toFloat())
        }
        dir.resolve(".DS_Store").let { Files.writeString(it, "Bud1 junk") }
        return dir
    }

    private fun strings(o: JsonObject, key: String) =
        o[key]!!.jsonArray.map { it.jsonPrimitive.content }

    /**
     * The default pattern, on the wire, over a directory of extension-less keys.
     *
     * This is the payload the iframe renders, and the number in it is the one that used to be zero:
     * the browser reported "No key files matched" over six perfectly good keys because the default
     * glob demanded a suffix `saveKey` never writes. Asserted by *name* rather than by position,
     * because a positional column check goes vacuous the moment the list is reordered.
     */
    @Test
    fun theDefaultPatternPutsEveryExtensionlessKeyOnTheWire() {
        val out = reply(humansKeyDirectory(), "open", pattern = everyKeyGlob)

        assertEquals(6, out["fileCount"]!!.jsonPrimitive.int)
        assertTrue(out["fileCount"]!!.jsonPrimitive.int >= 6, "floor: six keys on disk, six on the wire")
        assertEquals(
            listOf("a", "b", "c", "d", "d2", "d3"),
            out["files"]!!.jsonArray.map { it.jsonObject["relative"]!!.jsonPrimitive.content }
        )
        // Junk is a notice, not an alarm. `problems` is now damage only.
        assertEquals(emptyList(), strings(out, "problems"))
        assertEquals("1 file ignored", out["notice"]!!.jsonPrimitive.content)
        assertEquals(
            listOf(".DS_Store"),
            out["skipped"]!!.jsonArray
                .filter { it.jsonObject["kind"]!!.jsonPrimitive.content == "NotAKey" }
                .map { it.jsonObject["relative"]!!.jsonPrimitive.content }
        )
    }

    /**
     * A narrowed pattern reaches the iframe carrying the count of what it hid, and the flag that
     * turns the widen button on.
     *
     * Silence in this payload is the original defect: nothing in the old message could have told a
     * person that switching from `?` to `??` had dropped four keys.
     */
    @Test
    fun aNarrowedPatternArrivesWithTheHiddenCountAndTheWidenAffordance() {
        val out = reply(humansKeyDirectory(), "open", pattern = "??")

        assertEquals(2, out["fileCount"]!!.jsonPrimitive.int)
        assertTrue(out["canShowAll"]!!.jsonPrimitive.boolean, "the widen button must be offered")
        assertContains(out["notice"]!!.jsonPrimitive.content, "4 more keys here are hidden")
        assertEquals(
            listOf("a", "b", "c", "d"),
            out["skipped"]!!.jsonArray
                .filter { it.jsonObject["kind"]!!.jsonPrimitive.content == "Hidden" }
                .map { it.jsonObject["relative"]!!.jsonPrimitive.content },
            "and named, so the notice says which keys rather than only how many"
        )
    }

    /**
     * One click, six keys. `showAll` widens the handle's pattern and returns the same open payload,
     * so the button and a reload share a code path and cannot drift apart.
     */
    @Test
    fun showAllWidensANarrowedPatternInPlace() {
        val dir = humansKeyDirectory()

        val narrow = reply(dir, "open", pattern = "??")
        assertEquals(2, narrow["fileCount"]!!.jsonPrimitive.int)

        val wide = reply(dir, "showAll", pattern = "??")
        assertEquals("open", wide["type"]!!.jsonPrimitive.content, "the same reply shape, not a new one")
        assertEquals(everyKeyGlob, wide["pattern"]!!.jsonPrimitive.content)
        assertEquals(6, wide["fileCount"]!!.jsonPrimitive.int)
        assertTrue(wide["fileCount"]!!.jsonPrimitive.int >= 6, "floor: widening must reveal all six")
        assertEquals(
            listOf("a", "b", "c", "d", "d2", "d3"),
            wide["files"]!!.jsonArray.map { it.jsonObject["relative"]!!.jsonPrimitive.content }
        )
        assertEquals(
            0,
            wide["skipped"]!!.jsonArray.count { it.jsonObject["kind"]!!.jsonPrimitive.content == "Hidden" },
            "nothing is hidden once the pattern is everything"
        )
    }

    /** An unknown token is an error, not an empty collection that reads as "you have saved nothing". */
    @Test
    fun anUnknownTokenIsAnError() {
        val request = """{"channel":"__keyframe_browser__","reqId":"1","op":"open","root":"kb-nope"}"""
        val out = json.parseToJsonElement(keyframeBrowserRawForTesting(request)!!).jsonObject
        assertFalse(out["ok"]!!.jsonPrimitive.boolean)
        assertContains(out["error"]!!.jsonPrimitive.content, "unknown keyframe browser token")
    }

    /** A message on someone else's channel is declined, so the raw-handler chain keeps looking. */
    @Test
    fun aMessageForAnotherChannelIsNotClaimed() {
        val request = """{"channel":"__h2_explorer__","reqId":"1","op":"open","root":"x"}"""
        assertEquals(null, keyframeBrowserRawForTesting(request))
    }
}
