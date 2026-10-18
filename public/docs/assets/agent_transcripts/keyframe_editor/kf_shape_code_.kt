// Run this box: one key of each type. Right-click a key to change its type.
val shape = keyframes(
    name = "shape",
    keys = listOf(
        Keyframe(0.0, 0.1, "linear"),
        Keyframe(1.0, 0.8, "hold"),
        Keyframe(2.0, 0.3, "bezierFlat"),
        Keyframe(3.0, 0.9, "bezierContinuous"),
        Keyframe(4.0, 0.5, "bezierFree"),
        Keyframe(5.0, 0.2, "linear"),
    ),
)
