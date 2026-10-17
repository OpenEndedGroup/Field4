// Three lanes in one group: a and b share their key times, as bakeGesture's lanes do; c does not.
val n = 121
val a = keyframes("lanes", "a", (0 until n).map { Vec2(it / 30f, sin(it / 10f)) })
val b = keyframes("lanes", "b", (0 until n).map { Vec2(it / 30f, 0.3f * cos(it / 4f)) })
val c = keyframes("lanes", "c", (0 until n).map { Vec2(it / 31f, 0.3f * cos(it / 4f)) })
