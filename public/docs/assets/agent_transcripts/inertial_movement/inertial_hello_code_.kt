// Launch it (Alt-R). `goal` jumps between two values; reading `eased` arrives there with
// inertia instead of snapping, and re-aims mid-flight without losing the speed it had.
var goal = 0.0
val eased = inertialTo<Double>(time = 0.4) { goal }
val target = mutableListOf<Double>()
val seen = mutableListOf<Double>()

loop {
    goal = if ((frameNumber / 40) % 2 == 0) 1.0 else -1.0
    target += goal
    seen += eased()
    showSignals(
        "goal" to target.takeLast(240),
        "inertialTo" to seen.takeLast(240),
        title = "one value, chasing a square wave",
        xTitle = "frame",
    )
}
