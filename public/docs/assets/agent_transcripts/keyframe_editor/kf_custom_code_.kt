/*
Run this box: three key types written in Kotlin. Each returns the curve's value across a
segment; u runs 0 to 1, lerp(f) is "f of the way from this key's value to the next".
Right-click a key and they are in the menu, after the five built in.
*/
interpolant("overshoot") { lerp(1 + 2.7 * (u - 1).pow(3) + 1.7 * (u - 1).pow(2)) }
interpolant("wobble") { lerp(u) + 0.08 * sin(u * 6 * PI) * (1 - u) }
// Deterministic chaos: the logistic map, seeded by where we are, and pinned to both keys.
interpolant("chaos") {
    var x = 0.1 + 0.8 * u
    repeat(30) { x = 3.97 * x * (1 - x) }
    lerp(u) + 0.25 * sin(PI * u) * (x - 0.5)
}

val custom = keyframes(
    name = "custom",
    keys = listOf(
        Keyframe(0.0, 0.1, "overshoot"),
        Keyframe(1.5, 0.8, "wobble"),
        Keyframe(3.0, 0.3, "chaos"),
        Keyframe(4.5, 0.6, "linear"),
    ),
)
