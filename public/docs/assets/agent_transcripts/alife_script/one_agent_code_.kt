// One agent, one program. That is the whole idea of this layer.
//
// alife.kt's swarms take a rule and a parameter block and advance everybody with one step(). Here
// each agent runs its own suspending program, and the clock is a fixed step, so the run is the
// same every time you press play.

val w = ScriptWorld(width = 200.0, height = 200.0, seed = 1L)
w.spawn(100.0, 60.0, maxSpeed = 50.0) {
    val centre = point(100.0, 100.0)          // hoisted: see the note at the bottom
    forever { moveFor(4.0) { orbit(centre, distance = 40.0) } }
}
w.run(480)
lines(*w.trailFLines().toTypedArray(), channel = "orbit", w = 440.0, h = 240.0, fitToSize = true)

// Try this: change `distance = 40.0` to 20.0 and re-run — the circle tightens.
// Then swap `orbit(centre, ...)` for `wander(turn = 30.0)` and the same lines draw a random walk
// instead. The program is the agent's, so changing it changes one agent and nobody else.
//
// Why `centre` is hoisted out of the steering block: `point()` belongs to the behaviour scope,
// and inside `moveFor { }` the receiver is the steering builder, which does not expose it. Written
// inline it fails to compile with "cannot be called in this context with an implicit receiver" —
// so build targets where the program can see them, and pass them in.
