package afield.lang.forth

import java.io.File

/**
 * The sound on `forth_examples.md`'s step-sequencer sections.
 *
 * Each [SCENES] entry is the ForthField source of one box on the page, character for character
 * (`ForthStepsTest` checks the page still contains it). [renderWav] evaluates that source in a fresh
 * [ForthInterpreter] and writes the WAV its `render-steps` put in the `audio` channel, so the player on
 * the page is the box's own output.
 */
object ForthStepsDocs {

    /** Page box name to its ForthField source. */
    val SCENES: Map<String, String> = linkedMapOf(
        "forth-steps-alternation" to """
            120 bpm
            seq: line  < c4 eb3 g3 > note | | < g3 c4 > note | ;seq
            line 12 run-steps
            line 12 render-steps
        """.trimIndent(),
        "forth-steps-synth-word" to """
            : acid  saw 900 lpf 0.12 decay ;
            130 bpm
            seq: bass  acid c2 note | acid c3 note | | acid < eb2 g2 bb2 > note ;seq
            bass 8 run-steps
            bass 24 render-steps
        """.trimIndent(),
        "forth-steps-bell" to """
            : bell  3.5 4 fm 1.4 decay 0.5 gain ;
            90 bpm
            seq: chime  bell [ c5 e5 g5 b5 ] iter index note | | sine 0.3 decay [ c3 g3 eb3 ] [ 6 3 1 ] wchoose note | ;seq
            7 seed chime 8 run-steps
            7 seed chime 32 render-steps
        """.trimIndent(),
    )

    /** Evaluate scene [name] and return the WAV bytes its `render-steps` displayed. */
    fun render(name: String): ByteArray {
        val source = SCENES[name] ?: error("unknown forth steps scene '$name'; known: ${SCENES.keys}")
        val forth = ForthInterpreter()
        forth.onOutput = {}
        val errors = forth.eval(source)
        check(errors.isEmpty()) { "scene '$name' failed: $errors" }
        return forth.steps.lastWav ?: error("scene '$name' displayed no audio")
    }

    /** Offline-render scene [name] to a WAV at [outPath] — the docs fence's entry point. */
    fun renderWav(name: String, outPath: String) {
        val bytes = render(name)
        require(bytes.size > 44) { "scene '$name' rendered no samples" }
        File(outPath).apply { parentFile?.mkdirs() }.writeBytes(bytes)
    }
}
