// A wall in alife_script is a line SEGMENT, and a segment has no thickness. So the obvious
// collision test — "does the agent's new position overlap a wall" — is false for any step big
// enough to step over it, at any speed at all. The collision here is swept: it solves for the
// first moment of contact along the step, not for where the step happened to land.
//
// Three agents, one wall, three answers to "what now".

val w = ScriptWorld(width = 200.0, height = 200.0, seed = 1L, wrap = false)
// A room as well as the barrier, so a BOUNCE agent has something to come back off. The arena
// edge is NOT a wall — ScriptWorld.confine just clamps there — so without this the bouncer
// parks in a corner and the trail says nothing.
w.addPolygon(12.0, 12.0, 188.0, 12.0, 188.0, 188.0, 12.0, 188.0, name = "room")
w.addWall(120.0, 30.0, 120.0, 170.0, name = "barrier")

listOf(WallResponse.SLIDE, WallResponse.BOUNCE, WallResponse.STOP).forEachIndexed { i, r ->
    w.spawn(
        25.0, 60.0 + i * 25.0, maxSpeed = 90.0, radius = 2.0, steerResponse = 1.0,
        vx = 90.0, vy = 16.0, wallResponse = r, restitution = 0.9, tag = r.name
    ) { moveFor(8.0) { keepVelocity() } }
}
w.run(420)

w.actors.forEach {
    println("%-7s %3d contact(s), ended at %6.1f, %6.1f".format(
        it.tag, it.wallContacts, it.position.x, it.position.y))
}
println()
println("90 units/s at 60 Hz is 1.5 units per step against a radius of 2 — no tunnelling risk.")
println("Raise maxSpeed and vx to 900 and every number above stays sane; that is the whole")
println("point of solving for the contact instead of testing the endpoint.")

lines(
    *(w.obstacleFLines() + w.trailFLines()).toTypedArray(),
    channel = "wall", w = 440.0, h = 440.0, fitToSize = true
)

// Try this: set `restitution = 0.3` on the BOUNCE agent and it loses most of its speed at the
// wall. Set it to 1.0 and the trail is a billiard. SLIDE is the default because it is the only
// one of the three that lets an agent still get somewhere while touching something.
