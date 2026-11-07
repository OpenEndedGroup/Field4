import afield.audio.modal.*

// A unit-square drum. Its lowest sixteen modes, each a row you can play on its own;
// the analytic spectrum is π²(m² + n²), so λ₁ ≈ 19.74.
val square = drumMesh(doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0), 0.04)
val modes = square.modes(16)
display("modes", modes.modeTable(fundamental = 110.0))
lines(
    (0 until 8).flatMap { k ->
        modes.modeMesh(k).map { it.copy().apply { transform { v -> v.add((k % 4) * 1.1f, (k / 4) * 1.1f, 0f) } } }
    },
    w = 520.0,
    h = 270.0,
    fitToSize = true,
)
