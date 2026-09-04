import afield.four.synth.*
import java.io.File

// 2 - Read it back, and check the round trip actually held.
//
// Run box 1 first. This reads the file with no knowledge of what wrote it: the PPQ,
// tempo map, track split and track names all come out of the file itself.
val file = File(System.getProperty("java.io.tmpdir"), "field-midi-demo.mid")
require(file.isFile) { "run box 1 first - no file at ${file.absolutePath}" }

val clip = readMidiClip(file)

showMidiRoll(clip)

// And back into the pattern algebra, so an imported clip can be composed with.
val asPattern = readMidiPat(file, beatsPerCycle = 8)
val firstCycle = asPattern.events(PatArc(PatQ.ZERO, PatQ.ONE))

markdown(
    "main",
    """
    ### Read ${clip.notes.size} notes back

    | | |
    |---|---|
    | PPQ | ${clip.ppq} |
    | tempo | ${"%.2f".format(clip.bpmAt(0.0))} BPM |
    | tempo events | ${clip.tempoMap.size} |
    | time signature | ${clip.timeSignature.first}/${clip.timeSignature.second} |
    | tracks | ${clip.tracks.map { "${it.name} (${it.notes.size})" }.joinToString()} |
    | pitch range | ${clip.notes.minOf { it.pitch }}..${clip.notes.maxOf { it.pitch }} |
    | velocities | ${clip.notes.map { it.velocity }.distinct().sorted().joinToString()} |

    As a pattern again: ${firstCycle.size} events in cycle 0, pitches
    `${firstCycle.sortedBy { it.arc.start }.map { it.value }.joinToString(" ")}`.

    The roll above is drawn from the *file*, not from the clip box 1 held in memory.
    """.trimIndent()
)
