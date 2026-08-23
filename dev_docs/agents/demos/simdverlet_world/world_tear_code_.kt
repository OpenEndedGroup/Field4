import afield.four.physics.*
import afield.stdlib.vec

// Tearing, with something doing the tearing: a sphere pushed through a sheet that is pinned
// all round its border. The sheet is in the x/y plane and the sphere travels along z, so the
// view is straight down the barrel and the hole opens toward you.
val sim = SimdVerlet(SimdVerlet.State3.empty())
val n = 26
val cloth = sim.addCloth(
    width = n,
    height = n,
    spacing = 0.2f,
    stiffness = 60f,
    curvatureStrength = 0f,
    position = { c, r -> vec((c - (n - 1) * 0.5f) * 0.2f, (r - (n - 1) * 0.5f) * 0.2f, 0f) },
    pinned = { c, r -> c == 0 || r == 0 || c == n - 1 || r == n - 1 },
)

val punch = SphereCollider(0f, 0f, -1.6f, radius = 0.55f).also { it.friction = 0.4f }
val world = sim.addColliders(punch)

// An edge is retired once it is 24 % longer than it should be. `enableTearing` rather than
// `sim.addTearing` because a Cloth needs one more thing done: its curvature force is written
// from the GRID, not from the constraint set, so without the hook it goes on pulling the two
// lips of the rip together with no spring behind it and the sheet heals in the picture while
// the solver says it is torn.
//
// maxBreaksPerStep spreads the failure over frames, strongest edge first. It is a DIFFERENT
// rip and not merely a slower one -- a sheet that fails a few edges at a time goes on carrying
// load while it fails, so more of it reaches the threshold.
val tear = cloth.enableTearing(threshold = 0.24f, maxBreaksPerStep = 3)

val view = Rect(-2.9, -2.9, 5.8, 5.8)
var frames = 0

loop {
    // Drive the sphere through and let it come to rest on the far side, so what is left on
    // screen is the hole it made.
    punch.centerZ = (-1.6f + frames * 0.0038f).coerceAtMost(3.2f)
    cloth.step(dt = 1f / 60f, damping = 0.99f, gravity = 0f, iterations = 16)
    frames++

    showPlane(
        sim.debugLines(points = false, thickness = 1.0) + world.outline(thickness = 2.0),
        window = view,
        title = "a sphere tearing through a pinned sheet",
        caption = "frame $frames · ${tear.brokenTotal} edges torn · ${sim.liveEdgeCount()} left · " +
            "peak strain ${"%.2f".format(sim.peakStrain())}",
    )
}
