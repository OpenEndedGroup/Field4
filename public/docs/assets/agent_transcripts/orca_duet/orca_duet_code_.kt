/*
Two ORCAs and Kotlin in between. Lead plays a tune in twos; Kotlin hands each note to
Echo, which plays the last eight back in threes, an octave up; every eighth note Echo
plays, Kotlin moves one note of Lead's tune a step up the scale. Click a grid to join in.
*/
val synth = PatSynthBank()
val lead =
    orca(
        "lead",
        cols = 40,
        rows = 10,
        grid =
            """
    #.LEAD.#
    .
    .2C8
    D2.8TCDEGAGED
    .:04
    .
    D8
    .:12C
""",
    )
val echo =
    orca(
        "echo",
        cols = 40,
        rows = 10,
        grid =
            """
    #.ECHO.#
    .
    .3C8
    D3.8TCCCCCCCC
    .:25
""",
    )
val up = mapOf('C' to 'D', 'D' to 'E', 'E' to 'G', 'G' to 'A', 'A' to 'C')
val heard = ArrayDeque("CCCCCCCC".toList())
var echoes = 0
var nudged = "none yet"

lead.onNote {
    if (it.channel == 0) {
        heard.addLast(it.note[0])
        heard.removeFirst()
    }
    synth.play(it, pan = 0.35, wave = if (it.channel == 1) PatWave.SINE else PatWave.TRIANGLE)
}
echo.onNote {
    echoes++
    synth.play(it, pan = 0.7, wave = PatWave.SQUARE, gain = 0.35, cutoffHz = 2400.0)
}

displayOpen("between")
// The go-between. A box launches what it ends with: here the synth, and this loop beside it.
synth +
    frames {
        var seen = -1
        while (frame()) {
            if (lead.frame == seen) continue
            seen = lead.frame
            // Lead to Echo: Echo's track is always the last eight notes Lead played.
            heard.forEachIndexed { i, note -> echo[5 + i, 3] = note }
            // Echo to Lead: every eighth echo moves one note of Lead's tune up the scale.
            if (echoes >= 8) {
                echoes = 0
                val x = 5 + seen % 8
                val note = up[lead[x, 3]] ?: 'C'
                nudged = "lead[$x, 3] ${lead[x, 3]} → $note"
                lead[x, 3] = note
            }
            // The bass follows the tune: F under G and A, C under the rest.
            lead[4, 7] = if (lead[4, 4] in "GA") 'F' else 'C'
            markdown("between", "heard `${heard.joinToString("")}` · last nudge `$nudged`")
        }
    }
