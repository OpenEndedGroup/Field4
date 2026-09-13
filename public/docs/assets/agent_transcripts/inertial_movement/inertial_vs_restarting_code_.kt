// The same target and two identical interpolants. The only difference is the `startAt`
// line: restarting a blend throws away the speed it had, which is what any one-shot ease
// does every time you re-aim it. Left alone, `inertialTo` re-solves and keeps that speed.
var goal = 0.0
val carried = inertialTo<Double>(time = 0.4) { goal }
val restarted = inertialTo<Double>(time = 0.4) { goal }

val target = mutableListOf<Double>()
val reSolved = mutableListOf<Double>()
val fromRest = mutableListOf<Double>()

loop {
    // A target that never holds still, so every single frame is a re-aim.
    goal = sin(frameNumber / 40.0) * 0.8
    (restarted as NeedsStartingValue<Double>).startAt(fromRest.lastOrNull() ?: 0.0)

    target += goal
    reSolved += carried()
    fromRest += restarted()

    showSignals(
        "goal" to target.takeLast(300),
        "left to re-solve" to reSolved.takeLast(300),
        "restarted every frame" to fromRest.takeLast(300),
        title = "re-solving a blend against restarting it",
        xTitle = "frame",
    )
}
