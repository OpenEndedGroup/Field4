/*
The same target and two identical interpolants. The only difference is the `startAt`
line: restarting a blend throws away the speed it had, which is what any one-shot ease
does every time you re-aim it. Left alone, `inertialTo` re-solves and keeps that speed.
*/
var goal = 0.0
val carried = inertialTo<Double>(time = 0.4) { goal }
val restarted = inertialTo<Double>(time = 0.4) { goal }

val target = mutableListOf<Double>()
val reSolved = mutableListOf<Double>()
val fromRest = mutableListOf<Double>()

loop {
    // A target that never holds still, so every single frame is a re-aim.
    goal = sin(frameNumber / 40.0) * 0.8
    restarted.startAt(fromRest.lastOrNull() ?: 0.0)

    target += goal
    reSolved += carried()
    fromRest += restarted()
    if (target.size > 300) {
        target.removeAt(0)
        reSolved.removeAt(0)
        fromRest.removeAt(0)
    }

    showSignals(
        "goal" to target,
        "left to re-solve" to reSolved,
        "restarted every frame" to fromRest,
        title = "re-solving a blend against restarting it",
        xTitle = "frame",
    )
}
