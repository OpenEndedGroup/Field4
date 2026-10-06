// Run this box: two key types written in Kotlin. Each returns the curve's value across a
// segment; u runs 0 to 1, lerp(f) is "f of the way from this key's value to the next".
// Right-click a key and they are in the menu, after the five built in.
interpolant("overshoot") { lerp(1 + 2.7 * (u - 1).pow(3) + 1.7 * (u - 1).pow(2)) }
interpolant("wobble") { lerp(u) + 0.08 * sin(u * 6 * PI) * (1 - u) }

val custom = keyframes(name = "custom", keys = listOf(
    Keyframe(0.0, 0.1, "overshoot"),
    Keyframe(1.5, 0.8, "wobble"),
    Keyframe(3.0, 0.2, "linear"),
))
