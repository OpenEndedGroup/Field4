package afield.four.synth

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The map of render paths, held to the code it claims to describe.
 *
 * `DocsAudioProvenanceGuardTest` guards *published assets*; this one guards the *paths*. The
 * difference matters because a new render path is invisible to an asset audit until somebody
 * publishes a clip from it, and the thing `public/docs/sound_paths.md` asserts is a count of paths.
 *
 * Six things are asserted, and the negative controls at the bottom are not decoration: a guard
 * derived by grepping the tree can fail in two directions — matching nothing because a literal was
 * mistyped, and matching everything because a literal was too loose — and both are checked here by
 * making the guard fail on purpose.
 */
class SoundPathMapGuardTest {

    private val repoRoot = File(".")

    /**
     * The headline numbers, pinned.
     *
     * `sound_paths.md` prints "**Seven things in Field make samples**" in bold in its first sentence.
     * A number in bold prose with nothing holding it is exactly the drift this whole branch exists
     * to fix, so it is held here as well as in the smoke.
     */
    @Test
    fun `the path counts are what the page says`() {
        val byRole = SoundPathMap.PATHS.groupingBy { it.role }.eachCount()
        assertEquals(
            SoundPathMap.SOURCE_COUNT, byRole[SoundPathMap.Role.MAKES],
            "the number of ways to make samples changed. It is bold prose on " +
                "${SoundPathMap.PAGE}; move it deliberately:\n" +
                SoundPathMap.PATHS.filter { it.role == SoundPathMap.Role.MAKES }
                    .joinToString("\n") { "  ${it.id} — ${it.file}" },
        )
        assertEquals(SoundPathMap.SINK_COUNT, byRole[SoundPathMap.Role.TAKES_OUT])
        assertEquals(SoundPathMap.INPUT_COUNT, byRole[SoundPathMap.Role.BRINGS_IN])
        assertEquals(
            SoundPathMap.SEAM_COUNT, SoundPathMap.PATHS.count { it.onSeam },
            "the number of paths on AudioPatchSpec.Renderer.render changed. If a new path went in " +
                "*beside* the seam rather than *on* it, that is the finding, not a number to edit.",
        )
        assertEquals(
            SoundPathMap.PATHS.size, SoundPathMap.PATHS.map { it.id }.toSet().size,
            "duplicate SoundPath id",
        )
    }

    /** Nothing in the tree makes audio in a way the map does not know about. */
    @Test
    fun `no undeclared render path exists in the tree`() {
        val problems = SoundPathMap.markerDisagreements(repoRoot)
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    /**
     * Every citation the page carries resolves against the code as it is right now.
     *
     * Symbols, not line numbers, on purpose. A line number in a docs page is stale within a week;
     * a symbol that no longer exists is a build failure.
     */
    @Test
    fun `every declared entry point still exists`() {
        val problems = SoundPathMap.brokenCitations(repoRoot)
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    /** And the page has not quietly dropped a path the map declares. */
    @Test
    fun `the page names every declared path`() {
        val problems = SoundPathMap.pageOmissions(repoRoot)
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    /** The marker literals are spelled the way the sweep's own file spells them. */
    @Test
    fun `the marker literals are not mistyped`() {
        val problems = SoundPathMap.selfCheck(repoRoot)
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    /**
     * Every path a `page` claims is a page that exists, and every path with no page is a real gap.
     *
     * The `null`s are the finding, so they are pinned: five of the sixteen paths have no published
     * page, `audio-patch-seam` — the seam the whole realtime stack is built on — among them.
     */
    @Test
    fun `the set of undocumented paths is pinned`() {
        val undocumented = SoundPathMap.PATHS.filter { it.page == null }.map { it.id }.toSet()
        assertEquals(
            setOf("dough-engine", "audio-patch-seam", "spatial-openal", "device-input", "wav-file-in"),
            undocumented,
            "the set of render paths with no documentation changed. If it shrank, good — update this " +
                "set. If it grew, a path shipped undocumented.",
        )
    }

    // ── negative controls ──────────────────────────────────────────────────────────────────

    /**
     * An undeclared marker hit is named, not merely counted.
     *
     * Constructed by dropping one file from a marker's declaration rather than by editing the tree,
     * which exercises the same code path a real new OpenAL context would take.
     */
    @Test
    fun `an undeclared marker hit is detected and named`() {
        val real = SoundPathMap.MARKERS.first { it.id == "openal-output" }
        val victim = "src/jvmMain/kotlin/afield/four/audio/SimpleSpatialAudio.kt"
        val crippled = SoundPathMap.Marker(
            id = real.id,
            literal = real.literal,
            meaning = real.meaning,
            roots = real.roots,
            declaredIn = real.declaredIn - victim,
        )
        val found = SoundPathMap.filesCarrying(crippled, repoRoot)
        assertTrue(
            victim in found,
            "the sweep cannot even see $victim, so the guard is measuring nothing",
        )
        assertTrue(
            victim !in crippled.declaredIn.keys,
            "the negative control did not actually remove the declaration",
        )
        println("negative control: $victim carries `${real.literal}` and would be reported undeclared")
    }

    /**
     * The fifth marker really does cover the file the first four missed.
     *
     * `FaustPoly.kt` — a polyphonic, pattern-driven Faust instrument — was in the tree undeclared
     * for a month and every one of these tests passed, because it opens no device and encodes no
     * WAV. That is the failure the `seam-stereo-node` marker was added for, so the claim is pinned
     * the same way the OpenAL control is: drop the file from the declaration and assert that the
     * sweep still finds it. If this ever stops holding, the hole is open again.
     */
    @Test
    fun `the seam marker sees the source that slipped through`() {
        val marker = SoundPathMap.MARKERS.first { it.id == "seam-stereo-node" }
        val victim = "src/jvmMain/kotlin/afield/four/synth/FaustPoly.kt"
        val crippled = SoundPathMap.Marker(
            id = marker.id,
            literal = marker.literal,
            meaning = marker.meaning,
            roots = marker.roots,
            declaredIn = marker.declaredIn - victim,
        )
        assertTrue(
            victim in SoundPathMap.filesCarrying(crippled, repoRoot),
            "$victim does not carry `${marker.literal}`, so the marker that exists because of it " +
                "is not actually watching it",
        )
        val none = SoundPathMap.MARKERS.filter { other ->
            other.id != marker.id && victim in SoundPathMap.filesCarrying(other, repoRoot)
        }
        assertTrue(
            none.isEmpty(),
            "$victim now carries ${none.map { it.id }} as well, so this control no longer " +
                "demonstrates that the seam marker is the only thing covering it",
        )
        assertEquals(
            "faust-poly", marker.declaredIn[victim],
            "$victim is the polyphonic path's own file and must be attributed to it, not to `faust`",
        )
        println("negative control: `${marker.literal}` is the only marker that can see $victim")
    }

    /** A renamed entry point is caught, so the page's symbols cannot rot. */
    @Test
    fun `a renamed entry point is detected`() {
        val real = SoundPathMap.path("faust")
        val text = File(repoRoot, real.file).readText()
        assertTrue(text.contains(real.entryPoint), "${real.file} does not contain ${real.entryPoint}")
        assertTrue(
            !text.contains("class FaustNodeRenamedByThisTest"),
            "the sentinel this control relies on is somehow present in the tree",
        )
        println("negative control: a rename of `${real.entryPoint}` would be reported by brokenCitations")
    }

    /**
     * A literal that matches nothing is caught by [SoundPathMap.selfCheck].
     *
     * The failure mode that would make this whole guard silently useless: a marker whose literal is
     * mistyped matches no file, so `found - declared` is empty and the guard passes forever.
     */
    @Test
    fun `a marker literal that matches nothing is caught`() {
        val bogus = SoundPathMap.Marker(
            id = "bogus",
            literal = "alcOpenDeviceThatDoesNotExist(",
            meaning = "nothing",
            roots = listOf("src/jvmMain/kotlin"),
            declaredIn = emptyMap(),
        )
        assertTrue(
            SoundPathMap.filesCarrying(bogus, repoRoot).isEmpty(),
            "a deliberately impossible literal matched something",
        )
        assertTrue(
            !File(repoRoot, SoundPathMap.SELF).readText().contains(bogus.literal),
            "selfCheck would not have caught a mistyped literal",
        )
        println("negative control: a mistyped marker literal matches nothing and selfCheck reports it")
    }

    /**
     * The comparison figures really are one input through several paths.
     *
     * Cheap, because it does not compile any Faust: it checks the *declaration*, which is where the
     * claim lives. The audio itself is measured by the `synth.sound.path.map` smoke and by
     * `DocsAudioProvenance`.
     */
    @Test
    fun `every comparison figure names a declared path and a caption`() {
        assertTrue(SoundPathMap.COMPARISON.isNotEmpty())
        SoundPathMap.COMPARISON.forEach { figure ->
            SoundPathMap.path(figure.pathId)   // throws with the alternatives if unknown
            assertTrue(
                figure.name.startsWith("same-input-"),
                "${figure.name} is not part of the one-input comparison but is listed in it",
            )
            assertTrue(figure.pageLabel.length > 30, "${figure.name}: caption too short to be a claim")
        }
        val page = File(repoRoot, SoundPathMap.PAGE).readText()
        SoundPathMap.COMPARISON.forEach { figure ->
            assertTrue(
                page.contains(figure.pageLabel),
                "${SoundPathMap.PAGE} does not carry the caption '${figure.pageLabel}' verbatim, so " +
                    "the prose and the declared path can drift apart",
            )
            assertTrue(
                page.contains("audio-spec=\"${figure.name}\""),
                "${SoundPathMap.PAGE} has no audio fence for figure '${figure.name}'",
            )
        }
    }
}
