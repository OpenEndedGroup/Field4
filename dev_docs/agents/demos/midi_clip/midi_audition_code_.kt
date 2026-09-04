import afield.four.synth.*
import java.io.File

// 4 - Hear it. Two routes, and this box tells you which one it took.
//
// Offline rendering pulls the JDK's Gervill synthesizer faster than real time, which
// needs `com.sun.media.sound.AudioSynthesizer.openStream`. `java.desktop` does not
// export that package, so it only works in a JVM launched with
// `--add-exports=java.desktop/com.sun.media.sound=ALL-UNNAMED`. Field's own JVM is not,
// so this box will normally report the live route instead - and say so, rather than
// pretending to have rendered something.
val clip = MidiClip.of(
    listOf(
        MidiNote(60, 0.0, 2.0, 100),
        MidiNote(64, 0.0, 2.0, 95),
        MidiNote(67, 0.0, 2.0, 105),
        MidiNote(72, 0.5, 1.5, 110)
    ),
    bpm = 120.0,
    name = "chord"
)

val reason = midiRenderUnavailableReason()

val report = if (reason == null) {
    val wav = File(System.getProperty("java.io.tmpdir"), "field-midi-demo.wav")
    val r = clip.renderWav(wav, tailSeconds = 1.0)
    """
    ### Rendered offline to a WAV

    | | |
    |---|---|
    | file | `${r.file.absolutePath}` |
    | length | ${"%.2f".format(r.seconds)}s |
    | **peak amplitude** | **${"%.3f".format(r.peakAmplitude)}** |
    | rms amplitude | ${"%.4f".format(r.rmsAmplitude)} |
    | soundbank | ${r.soundbankName} |

    Peak amplitude is the check that matters: a WAV of pure silence is exactly as large
    as a loud one, so file size proves nothing. ${if (r.isSilent) "**This one is silent.**" else "This one is not silent."}
    """.trimIndent()
} else {
    """
    ### Offline rendering is not available in this JVM

    $reason

    So nothing was rendered, and no claim is made about audio. Use the live route
    instead - this sends real MIDI to Gervill, the JDK's built-in synthesizer, and you
    should hear a chord:

    ```kotlin
    clip.playTo("Gervill")
    ```

    Or to any other port `MidiDevices.outputs()` lists in box 3.
    """.trimIndent()
}

showMidiRoll(clip)
markdown("main", report)
