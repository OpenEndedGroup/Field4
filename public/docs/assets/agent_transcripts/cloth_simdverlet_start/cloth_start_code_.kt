import afield.four.physics.*

// The physics system. State3 is the SoA particle store; the simulation owns it and grows
// it as things are added, so it starts empty.
val sim = SimdVerlet(SimdVerlet.State3.empty())

// 20 wide, 10 tall. addCloth appends the particles to whatever is already in `sim`, wires
// the four-slot distance constraints, and registers the curvature force.
//
// The plane view below has y growing DOWN the screen, so the sheet hangs from row 0 and
// gravity is +y. Every fourth column of that row is a nail; the rest of it swings.
//
// stiffness x iterations is the spring constant that matters (the solve accumulates all
// `iterations` corrections into one force). 60 x 16 holds this sheet at ~11% peak strain;
// 150 x 16 diverges. curvatureStrength is a shape force on the same scale, so the
// library's 0.05 default is invisible next to springs this stiff — at 100 it takes about
// 40% of the kink out of the festoons, which is what makes it worth a knob here.
val cloth = sim.addCloth(
    width = 20,
    height = 10,
    spacing = 0.5f,
    stiffness = 60f,
    curvatureStrength = 100f,
    pinned = { col, row -> row == 0 && col % 4 == 0 },
)

// One window for every frame. Without it showPlane re-fits the picture to whatever the
// cloth happens to occupy, and the sheet looks still while the world shrinks around it.
val view = Rect(-1.0, -1.0, 12.0, 9.0)

println("${cloth.count} cloth particles, ${sim.size} in the simulation")

var frames = 0
loop {
    // One frame of physics for the whole simulation: gravity, the per-particle force
    // generators, the registered bulk forces (the cloth's curvature pass), then
    // `iterations` of the constraint solve, then Verlet integration.
    cloth.step(dt = 1f / 60f, damping = 0.99f, gravity = 2f, iterations = 16)
    frames++

    // What the solver holds, not what the scene means: a segment per distance constraint,
    // a dot per particle, pinned particles in red, a whisker per particle's velocity.
    // Try cloth.grabNearest(x, y) and cloth.moveGrabbed(x, y) to drag one through it.
    showPlane(
        sim.debugLines(velocityScale = 30f),
        window = view,
        title = "20 x 10 cloth — SimdVerlet, 4-slot distance constraints",
        caption = "frame $frames · cyan: constraints · green: velocity · red: pinned",
    )
}
