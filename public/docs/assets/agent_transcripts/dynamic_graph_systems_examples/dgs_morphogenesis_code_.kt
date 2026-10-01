// LAUNCH this box, do not just run it.
//
// The full pipeline: diffuse a resource along the edges and climb its gradient, relax the
// springs, grow tips where the incident edges pull coherently, merge what has collided,
// smooth, jitter. The right-hand panel is the same pipeline with ONE stage removed -- tip
// growth -- so the difference between the panels is that stage and nothing else.
val seedRing = List(64) { i ->
    val a = 2.0 * Math.PI * i / 64
    Vec3((0.42 * Math.cos(a)).toFloat(), (0.42 * Math.sin(a)).toFloat(), 0f)
}
// Sized for where the structure ENDS UP. Re-fitting per frame would make growth invisible.
val window = doubleArrayOf(-0.95, -0.95, 0.95, 0.95)
val ceiling = 3.0

class Morph(
    val state: DynamicPointState,
    val kinetics: DynamicPointKinetics,
    val edges: DynamicEdgeList,
    val hash: Vec3SpatialHash,
    val pipeline: DynamicProcessPipeline,
    val system: DynamicGraphSystem
)

fun morph(grow: Boolean): Morph {
    val state = DynamicPointState(seedRing.map { Vec3(it) }.toMutableList())
    val kinetics = DynamicPointKinetics(mutableListOf(), mutableListOf(), mutableListOf())
    kinetics.ensureSize(state.pointCount)
    // Seed the resource on a quarter of the ring, so chemotaxis has a gradient to climb.
    for (i in 0 until 16) kinetics.resource[i] = 6.0
    val stages = mutableListOf<DynamicPointProcess>(
        SignalDiffusionAndChemotaxisProcess(diffusionRate = 0.5, chemoGain = 0.35, maxSpeed = 0.30),
        // rest = restLengthTimesWeight / weight: 0.075 is a rest length of 0.05 at a typical
        // weight of 1.5.
        SpringMotionProcess(springK = 1.2, damping = 3.0, restLengthTimesWeight = 0.075, maxSpeed = 0.30)
    )
    if (grow) stages += DirectedTipGrowthProcess(
        coherenceThreshold = 1.6, spawnDistance = 0.035, localDensityRadius = 0.05,
        localDensityMax = 6, childEdgeWeight = 1.5, childEdgeTtl = 1.5f, maxWeight = ceiling,
        maxPointCount = 900)
    stages += MergeClosePointsProcess(mergeDistance = 0.012, minCombinedWeight = 2.0)
    stages += RelaxToMidpointProcess(gain = 1.5)
    stages += JitterProcess(amplitude = 0.04)
    return Morph(
        state, kinetics, DynamicEdgeList(state.pointCount), Vec3SpatialHash(0.05),
        DynamicProcessPipeline(stages),
        Vec3PersistentProximityHysteresisSystem(
            rEnter = 0.05, rExit = 0.07, ttl = 0.8f, reinforce = 3.0, maxWeight = ceiling)
    )
}

animateEvolution(
    morph(grow = true) to morph(grow = false),
    totalSteps = 560,
    seconds = 10.0,
    columns = 2,
    cellSide = 490,
    framesTarget = 200,
    // Geometric: the 900-point cap is reached at step 79, so a linear schedule would spend
    // seven eighths of the clip on the aftermath. ramp = 25 puts the mid-point at step 93.
    ramp = 25.0,
    step = { (grown, flat) ->
        for (m in listOf(grown, flat)) {
            stepDynamicGraphWithExponentialDecay(
                1f / 60f, m.state, m.edges, m.hash, m.system, 0.6)
            m.pipeline.step(1f / 60f, m.state, m.kinetics, m.edges, m.hash)
        }
        grown to flat
    },
    panels = { (grown, flat), step ->
        listOf(
            "grow ON  s%d  %dpts %de".format(
                step, grown.state.pointCount, grown.edges.values().size) to
                dynamicGraphPanel(grown.state.points, grown.edges.values(), window, 490, 490, ceiling, "magma"),
            "grow OFF  s%d  %dpts %de".format(
                step, flat.state.pointCount, flat.edges.values().size) to
                dynamicGraphPanel(flat.state.points, flat.edges.values(), window, 490, 490, ceiling, "magma")
        )
    }
)
