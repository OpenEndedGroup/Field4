/*
One agent, one program. That is the whole idea of this layer.

alife.kt's swarms take a rule and a parameter block and advance everybody with one step(). Here
each agent runs its own suspending program, and the clock is a fixed step, so the run is the
same every time you press play.
*/

val w = ScriptWorld(width = 200.0, height = 200.0, seed = 1L)
w.spawn(100.0, 60.0, maxSpeed = 50.0) {
    val centre = point(100.0, 100.0) // One fixed target, reused at each steering step.
    forever { moveFor(4.0) { orbit(centre, distance = 40.0) } }
}
w.run(480)
lines(
    *w.trailFLines().toTypedArray(),
    channel = "orbit",
    w = 440.0,
    h = 240.0,
    fitToSize = true,
)

/*
 Try this: change `distance = 40.0` to 20.0 and re-run — the circle tightens.
 Then swap `orbit(centre, ...)` for `wander(turn = 30.0)` and the same lines draw a random walk
 instead. The program is the agent's, so changing it changes one agent and nobody else.

 Keep the target outside the repeating block so its position is easy to edit.
 Steering blocks also accept point(...); here every step reuses the same target.
 */
