var simModule = computeModuleHere {}
var sim = ComputeMesh()
simModule += "step" to sim
renderer += "gpu-state-sim" to simModule

val N = 40000

// ---- four buffers, four different answers to "who writes this, and how often" ----------
//
// State is host-visible: written once below, then written by the compute shader every frame.
// Staging it would be a lie about the access pattern.
var stateA  = defaultBuffer { size = (N * 2).vec4s }.buffer
var stateB  = defaultBuffer { size = (N * 2).vec4s }.buffer
// Anchors are writeOnce(): written here, read by every invocation of every frame, which is
// exactly what device-local memory is for.
var anchors = defaultBuffer { size = N.vec4s; writeOnce() }.buffer
// Heat is zeroInit(): never uploaded at all. The device zeroes it and the shader owns it.
var heat    = defaultBuffer { size = N.vec4s; zeroInit() }.buffer

// One list of home positions, used for the anchors and for both halves of the state, so the
// simulation starts exactly at rest rather than nearly at rest.
val rng = kotlin.random.Random(0x9f7231)
val home = (0 until N).map {
    val angle = rng.nextDouble() * Math.PI * 2
    val radius = Math.sqrt(rng.nextDouble()) * 0.86
    Triple(radius * Math.cos(angle), radius * Math.sin(angle), rng.nextDouble() * 0.5 - 0.25)
}

anchors.write { for (h in home) put(vec(h.first, h.second, h.third, 0.0)) }

// BOTH halves of the ping-pong. The shader reads one and writes the other from frame one, so
// an unwritten second buffer means the first frame integrates from zeroes.
for (target in listOf(stateA, stateB)) {
    target.write {
        for (h in home) {
            put(vec(h.first, h.second, h.third, 0.0))   // position, age
            put(vec(0.0, 0.0, 0.0, 0.0))                // velocity
        }
    }
}

// ---- bindings. Lambdas, evaluated once per dispatch, so they go in once, here. ----------
//
// Parity comes from renderer.frameCounter and nothing else: it is constant for the whole of
// one frame's recording, so this box and the draw box agree without either depending on
// which one's loop ran first.
sim.state     = { if (renderer.frameCounter % 2L == 0L) stateA else stateB }
sim.prevState = { if (renderer.frameCounter % 2L == 0L) stateB else stateA }
sim.anchors   = { anchors }
sim.heat      = { heat }
sim.count     = { N.toUInt() }
sim.dt        = { 1f / 60f }        // FIXED, not measured: stability depends on it
// The clock comes off the same counter as the parity, and not off the wall: a fixed dt
// integrator that walks its force field at wall-clock speed is two different timebases in
// one loop. A slow frame should slow the field, not tear it — and a frame-synchronous docs
// capture of this scene then looks like the scene.
sim.knobs     = { vec(renderer.frameCounter / 60.0, 1.0, 0.0, 0.0) }

// ---- the live slot. On the MODULE, not the mesh. ----------------------------------------
//
// `sim.force = ...` would be a receiver-type mismatch naming both ModulePointer overloads,
// which is a confusing way to learn that a stack.auto slot is a property of the compiled
// shader while the mesh only carries push constants.
//
// fbm is 2D — it lowers to fbm(vec2) over coord.xy + coord.zw * 0.07 — so feeding it a 3D
// position gives a field in (x, y) with z entering at a fourteenth of the weight of x. On a
// disc of particles seen face on that reads as a fixed stripy pattern. Summing three slices,
// f(x,y) + f(y,z) + f(z,x), is the standard fix and costs three fbms.
//
// `>c` and not `=c`: a definition must consume its input and leave its result. `=name` copies
// (a -- a) and `>name` moves (a --), so opening with `=c` leaves the input under the result
// and every call pushes two values. Try it and read the box's stack.auto channel.
simModule.force = """
    : fbm3
      >c
      <c xy  ?pc.knobs x 0.048 * +  fbm x
      <c yz  ?pc.knobs x 0.036 * +  fbm x
      <c zx  ?pc.knobs x 0.061 * +  fbm x
      + + 0.33333 *
      1.032258 *
    ;

    input xyz 7.0 * =q
    <q                    fbm3
    <q [11.3,4.1,7.7]  +  fbm3
    <q [2.9,17.7,13.3] +  fbm3
    vec3
    0.5 - 4.6 *
"""

sim.x = N / 64; sim.y = 1; sim.z = 1

// A GPU-side histogram of per-particle speed, reservoir-sampled in the shader and reduced on
// the card. No readback of the state buffers, and no CPU work per particle.
fieldProbeHere(samples = 4096).display("probe")
displayOpen("probe")

// Published for the draw box, which is a child of this one. Both halves, because the draw box
// has to ask the same parity question this box just asked.
prop<FrameBufferGroup>(gsStateA) = stateA
prop<FrameBufferGroup>(gsStateB) = stateB
prop<FrameBufferGroup>(gsHeat)   = heat

println("simulation launched, N=" + N)
