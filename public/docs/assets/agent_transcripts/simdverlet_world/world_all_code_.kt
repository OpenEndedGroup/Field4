import afield.four.physics.*
import afield.stdlib.vec

// Everything at once, and one knob.
//
// A sheet pinned along its top edge, a rope tied to the middle of its bottom edge with a heavy
// weight on the end, a sphere and a floor beneath, gusty wind across it, and tearing on all of it.
// Five subsystems in one simulation, all going through the one `step()`.
//
// ---------------------------------------------------------------------------------------------
// THE KNOB. Change this and re-run. Measured on exactly this scene over 900 frames
// (SimdVerletTearingTest.theWholeWorldLayerInOneSceneBehavesAsTheDemoSaysItWill asserts it):
//
//     0.15  ->  154 edges torn   the sheet shreds; the pieces land on the sphere and the floor
//     0.25  ->   50 edges torn   a large rip, spreading from where the rope pulls
//     0.40  ->   23 edges torn   a local tear at the knot, and the sheet survives
//     0.60  ->    0 edges torn   it holds -- and never touches the sphere or the floor at all
//
// The shipped 0.6 is the last row, and that is on purpose: an intact sheet hangs clear of
// everything under it, so "the collider contact count went from 0 to 236" and "the sheet failed"
// are the same event, and the scene is watchable for as long as you leave it running.
//
// One honest warning before you turn it down. Below about 0.5 the torn sheet keeps being blown
// downwind by a wind that never stops, and over thousands of frames it stretches into a thin web
// pinned across the whole room -- measured, by frame 2500 the cloth's bounding box was the room.
// The first few hundred frames are the interesting part; after that, re-run the box.
// ---------------------------------------------------------------------------------------------
val tearThreshold = 0.6f

// y grows DOWN the plane view, so gravity is positive and the sheet hangs from row 0.
val sim = SimdVerlet(SimdVerlet.State3.empty())

val cloth = sim.addCloth(
    width = 20,
    height = 12,
    spacing = 0.22f,
    stiffness = 60f,
    curvatureStrength = 0f,
    position = { c, r -> vec(c * 0.22f, r * 0.22f, 0f) },
    pinned = { _, r -> r == 0 },
)

// A rope, tied to the sheet. `Strand` spends only two of its four constraint slots on the chain,
// which is what leaves attachTo something to spend: the knot is an ordinary symmetric distance
// constraint, so the solver, the colliders and the tearing all already understand it -- and the
// knot is among the edges that can fail, with nothing written to make it so.
//
// It has to be tied to a grid node with a free slot. A cloth's bottom edge has one; its interior
// has none, and attachTo will tell you so by index rather than failing quietly.
val rope = sim.addStrand(
    count = 16,
    spacing = 0.16f,
    stiffness = 150f,
    bendStiffness = 0.2f,   // 0..1, a projection: how tightly the rope is allowed to fold
    position = { n -> vec(10 * 0.22f, 11 * 0.22f + 0.16f * (n + 1), 0f) },
)
rope.attachTo(cloth[10, 11], node = 0)

// The weight on the end of the rope. invMass 1/24 is a mass of 24 -- this is what loads the sheet.
sim.state.invMass!![rope[15]] = 1f / 24f

// The world. Projections, not forces, and they run after integration inside step().
// A room rather than a floor, and that is a lesson rather than a preference: left running with a
// steady wind, a torn-free piece of sheet leaves the view and never comes back. Measured on the
// earlier version of this box -- at frame 17654 the picture was empty while the solver still held
// 443 live edges. A box with `inside = true` keeps the whole thing on screen indefinitely.
val world = sim.addColliders(
    SphereCollider(1.1f, 4.6f, 0f, radius = 0.7f).also { it.friction = 0.6f },
    BoxCollider(-0.9f, -0.4f, -1.6f, 7.4f, 6.4f, 1.6f, inside = true).also { it.friction = 0.9f },
)

// Air -- and only the per-particle drag pass here, which is a deliberate omission with a reason.
// `cloth.addWind`'s per-quad force acts along the sheet's NORMAL, and this sheet's normal points
// out of the screen: left running with it on, the whole thing swings into the y/z plane and its
// x/y projection collapses onto the pinned row. Measured, at frame 23811 the picture was a single
// horizontal line while the solver still held 443 edges and reported 236 contacts. Box 3 is the
// view that shows the per-quad model properly; this box wants a wind it can draw.
val wind = sim.addWind(velocityX = 3f, drag = 0.25f, turbulence = 0.8f, turbulenceScale = 0.4f, seed = 5)

// Tearing over the WHOLE simulation, so the knot and the rope are candidates like any other edge.
val tear = sim.addTearing(threshold = tearThreshold, maxBreaksPerStep = 2)

val view = Rect(-1.0, -0.6, 7.6, 7.6)
var frames = 0

loop {
    cloth.step(dt = 1f / 60f, damping = 0.99f, gravity = 3f, iterations = 16)
    frames++

    showPlane(
        sim.debugLines(points = false, thickness = 0.9) +
            world.outline(thickness = 2.0) +
            wind.windLines(-0.8f, 0f, 0f, 7.2f, 7.2f, 0f, columns = 9, rows = 9, scale = 0.05f),
        window = view,
        title = "everything at once - cloth, rope, colliders, wind, tearing",
        caption = "frame $frames · threshold $tearThreshold · ${tear.brokenTotal} edges torn · " +
            "${world.contacts} contacts · ${sim.liveEdgeCount()} edges left",
    )
}
