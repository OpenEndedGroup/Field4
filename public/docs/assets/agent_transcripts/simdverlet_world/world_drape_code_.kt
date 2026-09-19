import afield.four.physics.*
import afield.stdlib.vec

// A cloth, and something for it to land on.
//
// Convention first, because it decides every sign below: the plane view has y growing DOWN
// the screen, so gravity is POSITIVE, the sheet starts at y = 0 and the sphere is below it
// at y = 2.
val sim = SimdVerlet(SimdVerlet.State3.empty())
val cloth = sim.addCloth(
    width = 22,
    height = 22,
    spacing = 0.22f,
    stiffness = 60f,
    curvatureStrength = 0f,
    // Held out flat, face down, centred over the sphere. The sheet lives in the x/z plane
    // and falls along y, so what the view below shows is its silhouette.
    position = { c, r -> vec((c - 10.5f) * 0.22f, 0f, (r - 10.5f) * 0.22f) },
    pinned = { _, _ -> false },
)

// The world. A collider is a POST-SOLVE POSITION PROJECTION, not a penalty force. The
// constraint solve is force-based -- all `iterations` corrections land in one accumulator
// -- so a cloth spring of stiffness k acts at k*iterations while a bulk force runs once.
// A penalty collider is therefore sixteen times too soft at the setting below, and
// stiffening it by sixteen puts it past the explicit-integration limit: nothing in a 256x
// sweep of penalty stiffness got within two orders of magnitude of the projection.
// dev_docs/physics/SIMDVERLET_5_WORLD.md has the sweep.
val world = sim.addColliders(
    // friction is Coulomb, bounded by mu * penetration. Turn it down to 0f and watch the
    // sheet slide off the shoulder of the sphere instead of gripping it.
    SphereCollider(0f, 2f, 0f, radius = 1.05f).also { it.friction = 0.75f; it.margin = 0.02f },
    // A floor at y = 4.4, with the legal side ABOVE it: y grows down, so the normal is -y
    // and the offset is -4.4.
    PlaneCollider(0f, -1f, 0f, offset = -4.4f).also { it.friction = 0.9f },
)

val view = Rect(-3.0, -1.0, 6.0, 6.0)
var frames = 0

loop {
    // The colliders run inside step(), after integration -- see SimdVerlet.positionProjections.
    cloth.step(dt = 1f / 60f, damping = 0.99f, gravity = 9.8f, iterations = 16)
    frames++

    showPlane(
        sim.debugLines(points = false, thickness = 0.9) + world.outline(thickness = 2.0),
        window = view,
        title = "a 22 x 22 cloth draping over a sphere",
        caption = "frame $frames · ${world.contacts} contacts this frame · cyan: constraints · amber: colliders",
    )
}
