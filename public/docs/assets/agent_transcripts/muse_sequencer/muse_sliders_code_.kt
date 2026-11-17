// Two hands drawn from weights. Move a slider and the roll redraws; re-run the box to hear the
// current settings through the piano (every slider keeps its value across runs).
val muse = PatMuse(seed = 3)
var seed = 3.0
var lockLength = 0.0
var lockAt = 4.0
var start = 0.0
var end = 64.0
var direction = 0.0
var improvise = 0.0

fun hands(): PatMuse {
    muse.left.seed = seed.toLong()
    muse.right.seed = seed.toLong()
    muse.release()
    if (lockLength >= 1) {
        muse.lock(lockLength.toInt(), atCycle = lockAt)
        for (loop in listOf(muse.leftLoop!!, muse.rightLoop!!)) {
            loop.start = start.toInt()
            loop.end = end.toInt()
            loop.direction = PatLoopDirection.entries[direction.toInt()]
            loop.improvise = improvise
            loop.seed = seed.toLong()
        }
    }
    return muse
}

fun redraw() {
    val m = hands()
    val state = if (lockLength >= 1) "locked: ${m.rightLoop}" else "free"
    showPatRollOf(
        "left" to m.leftPattern(),
        "right" to m.rightPattern(),
        title = "PatMuse(seed = ${seed.toInt()})",
        subtitle = state,
        cycles = 8,
        pitched = true,
        channel = "roll",
    )
}

controlGroup("loop", columns = 4) {
    slider("seed", 1.0, 64.0, 1.0, seed) {
        seed = it
        redraw()
    }
    slider("lock length (0 = free)", 0.0, 64.0, 1.0, lockLength) {
        lockLength = it
        redraw()
    }
    slider("lock at cycle", 1.0, 16.0, 1.0, lockAt) {
        lockAt = it
        redraw()
    }
    slider("improvise", 0.0, 1.0, 0.05, improvise) {
        improvise = it
        redraw()
    }
    slider("start", 0.0, 63.0, 1.0, start) {
        start = it
        redraw()
    }
    slider("end", 1.0, 64.0, 1.0, end) {
        end = it
        redraw()
    }
    slider("direction", 0.0, 2.0, 1.0, direction) {
        direction = it
        redraw()
    }
}
controlGroup("right hand", columns = 4) {
    weights("degree", muse.right.degree) { redraw() }
    weights("chord", muse.right.chord) { redraw() }
    weights("length", muse.right.rhythm) { redraw() }
}
controlGroup("left hand", columns = 4) {
    weights("degree", muse.left.degree) { redraw() }
    weights("chord", muse.left.chord) { redraw() }
    weights("length", muse.left.rhythm) { redraw() }
}
redraw()
val r = pianoRender(17.0, Piano(sampleRate = 44_100)) {
    play(hands().pattern(), cycles = 8, cps = 0.5, velocity = 1.0, startFrame = 0)
}
showPianoAudio(r, "listen")
["loop", "roll", "listen"].forEach { displayOpen(it) }
