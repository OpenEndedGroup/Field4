# Braitenberg vehicles with memory

A Braitenberg vehicle is two light sensors, two wheels and some wires between them. Cross the wires
and make them excitatory, and the sensor nearer a light speeds up the far wheel, so the vehicle turns
toward the light and drives into it. Leave them uncrossed and make them inhibitory, and the vehicle
slows down as it faces a light and parks in front of it. Braitenberg called those two the *aggressor*
and the *lover*, and Aatish Bhatia's [Let's build a brain](https://aatishb.com/myriad/lets-build-a-brain/)
is a good playable introduction to them.

Those vehicles have no memory: the wheel speeds depend only on what the sensors read right now. On this
page each motor neuron is a small dynamical system instead of a wire, with state on three timescales:

| Level | Timescale | Mechanism | Switched off by |
| --- | --- | --- | --- |
| membrane | a few steps | leaky integration of the input | `tauMembrane = 1.0` |
| hysteresis | while a neuron is on | self-excitation makes the activation bistable | `selfExcitation = 0.0` |
| homeostasis | hundreds of steps | the threshold integrates `rate − targetRate` | `adaptationRate = 0.0` |
| plasticity | thousands of steps | reward-modulated eligibility traces rewire the sensors | `learningRate = 0.0` |

Each section turns one of these on, runs it beside the same seed with it turned off, and prints the
number that differs. The code is `alife_vehicles.kt`, next to the [boids and Physarum](alife_examples.md)
it is modelled on, and like them it runs on the CPU.

## 1. One neuron, swept up and back

Per step, a motor neuron with input `I`, rate `r` and threshold \( \theta \) does

```diffstatic {data-lang=text}
u += (−u + I + selfExcitation·r − θ − bias) / tauMembrane
r  = activation(gain · u)
θ += adaptationRate · (r − targetRate)
```

The activation is one of four `Activation` values. `SIGMOID`, `TANH` and `RELU` are switches of
different shapes; `BUMP` (\( e^{-x^2} \)) is tuned to a preferred input and is quiet above and below it.

With `selfExcitation = 0` the neuron is a smoothed function of its input. Feed enough of its own
output back, and a sigmoid becomes bistable: once on, the neuron's own rate holds it on after the input
has fallen below the level that turned it on. `hysteresisSweep` drives the input slowly from −2.5 to 2
and back and reports where the rate crosses 0.5 on each leg.

```diffstatic_runner {data-lang=kotlin screenshot="assets/braitenberg_vehicles/hysteresis.png" embed=true wait="5.0" capture="box" open-outputs="true" padding="20"}
val plain = hysteresisSweep(NeuronParams(selfExcitation = 0.0))
val latched = hysteresisSweep(NeuronParams(selfExcitation = 1.5))
for ((name, s) in ["no feedback" to plain, "selfExcitation 1.5" to latched]) {
    println(name + ": on at " + s.switchOn.f2 + ", off at " + s.switchOff.f2 + ", loop width " + s.width.f2)
}
showImg(
    montage(
        [
            "no feedback: width " + plain.width.f2 to plain.toImg(),
            "feedback 1.5: width " + latched.width.f2 to latched.toImg(),
        ],
        columns = 2,
    ),
)
```

Orange is the rising sweep and blue the falling one. Without feedback the two lie on top of each other
(the small width left is the membrane's lag). With it, the neuron switches on at an input of −0.45 and
stays on until −1.06, a loop 0.61 wide. In a vehicle this means that a motor neuron that has committed to
driving keeps driving through a brief dip in the light.

## 2. Four wirings

`VehicleWorld.random(count, lights, wiring = ...)` places vehicles at random on a torus among some
lights. The four companion wirings are Braitenberg's: `AGGRESSOR` (crossed, excitatory), `COWARD`
(uncrossed, excitatory), `LOVER` (uncrossed, inhibitory) and `EXPLORER` (crossed, inhibitory).
`VehicleCanvas` draws a fading trail behind each vehicle, red for the left motor neuron's rate and green
for the right, so a vehicle driving flat out is yellow and a turning one is red or green.

The inhibitory pair use `bias = −1.0`, so their neurons are on in the dark and switched off by light.

```diffstatic_runner {data-lang=kotlin screenshot="assets/braitenberg_vehicles/zoo.png" embed=true wait="12.0" capture="box" open-outputs="true" padding="20"}
val lights = [Light(60.0, 60.0), Light(180.0, 70.0), Light(120.0, 180.0), Light(200.0, 200.0)]
val excite = VehicleParams()
val inhibit = VehicleParams(neuron = NeuronParams(bias = -1.0))

fun run(name: String, wiring: DoubleArray, p: VehicleParams): Pair<String, Img> {
    val world = VehicleWorld.random(40, lights, wiring = wiring, seed = 3L)
    val canvas = VehicleCanvas(world, scale = 1.2)
    repeat(1500) {
        world.step(p)
        canvas.record()
    }
    println(name + ": mean light at the body " + world.meanLight().f3)
    return name to canvas.image()
}

showImg(
    montage(
        [
            run("aggressor", VehicleWorld.AGGRESSOR, excite),
            run("coward", VehicleWorld.COWARD, excite),
            run("lover", VehicleWorld.LOVER, inhibit),
            run("explorer", VehicleWorld.EXPLORER, inhibit),
        ],
        columns = 2,
        cellSide = 300,
    ),
)
```

### The same four, at driving speed

The still shows where the vehicles have been. This clip shows how they move, at two steps per displayed
frame: the aggressors swerve into a light and accelerate through it, the cowards turn away from each
light and speed off, the lovers slow down and come to rest facing a lamp, and the explorers slow down
near a lamp and then turn and leave.

```diffstatic_runner {data-lang=kotlin video="assets/braitenberg_vehicles/wirings.mp4" capture="box" open-outputs="true" wait-output="true" wait-output-kind="display" execution="launch" duration="16" fps="20" timeout="300" w="1000" embed=true name="vehicles-wirings-video" alt="four panels of Braitenberg vehicles moving slowly among four lights: aggressors swerving into the lights, cowards turning away, lovers slowing to a stop facing a lamp, explorers slowing and turning away"}
val lights = [Light(60.0, 60.0), Light(180.0, 70.0), Light(120.0, 180.0), Light(200.0, 200.0)]
val excite = VehicleParams()
val inhibit = VehicleParams(neuron = NeuronParams(bias = -1.0))

class Arm(val name: String, wiring: DoubleArray, val p: VehicleParams) {
    val world = VehicleWorld.random(24, lights, wiring = wiring, seed = 3L)
    val canvas = VehicleCanvas(world, scale = 1.2, fade = 0.97f)
}

val arms = [
    Arm("aggressor", VehicleWorld.AGGRESSOR, excite),
    Arm("coward", VehicleWorld.COWARD, excite),
    Arm("lover", VehicleWorld.LOVER, inhibit),
    Arm("explorer", VehicleWorld.EXPLORER, inhibit),
]

animateEvolution(
    Unit,
    totalSteps = 600,
    seconds = 15.0,
    framesTarget = 300,
    columns = 2,
    cellSide = 300,
    step = {
        for (arm in arms) {
            arm.world.step(arm.p)
            arm.canvas.record()
        }
    },
    panels = { _, n -> arms.map { it.name + " " + n to it.canvas.image() } },
)
```

## 3. Getting bored: homeostasis

A lover parks in front of the first light it finds and stays there. That is a fixed point, and nothing
in a memoryless vehicle can leave it. Give the threshold a slow integrator instead, so that a neuron
held below its `targetRate` for long enough lowers its own threshold, and the parked vehicle's motors
creep back on until it drives away to find another light. Its neurons then run hot in the dark, the
thresholds climb again, and it arrives at the next light able to be stopped by it.

`LightVisits` counts arrivals at a light other than the last one.

```diffstatic_runner {data-lang=kotlin screenshot="assets/braitenberg_vehicles/bored.png" embed=true wait="20.0" timeout="240" capture="box" open-outputs="true" padding="20"}
val lights = [Light(60.0, 60.0), Light(180.0, 70.0), Light(120.0, 180.0), Light(200.0, 200.0)]
val fixed = VehicleParams(baseSpeed = 0.0, neuron = NeuronParams(bias = -1.0))
val bored = fixed.copy(neuron = fixed.neuron.copy(adaptationRate = 0.002))

fun run(name: String, p: VehicleParams): Pair<String, Img> {
    val world = VehicleWorld.random(60, lights, wiring = VehicleWorld.LOVER, seed = 3L)
    val canvas = VehicleCanvas(world, scale = 1.2, fade = 0.998f)
    val visits = LightVisits(world)
    repeat(6000) {
        world.step(p)
        visits.observe(world)
        canvas.record()
    }
    println(name + ": " + visits.mean().f2 + " lights visited per vehicle")
    return name + ": " + visits.mean().f2 to canvas.image()
}

showImg(montage([run("fixed", fixed), run("homeostatic", bored)], columns = 2, cellSide = 300))
```

With the same 60 vehicles from the same seed over 6000 steps, the fixed-threshold lovers visit 2.7
lights each and the homeostatic ones visit 5.6. The mean light at their bodies falls from 0.82 to 0.71:
boredom costs them light, which is the trade the next level has to deal with.

## 4. Learning the wiring

The slowest level changes the four sensor→motor weights. Each motor neuron's output carries a little
noise `ξ`. An eligibility trace low-passes `ξ · sensor` over `tauTrace = 30` steps, recording which
twitches happened while which sensor was lit. The reward is the light at the vehicle's body, compared
with its own average over the last `tauBaseline = 50` steps, and the weights move by
`learningRate · (reward − average) · trace`. A twitch followed by more light than usual is written into
the wiring. This is node perturbation, REINFORCE with a baseline; it needs no gradient and no model of
the world.

The population starts with every weight drawn uniformly from ±2, so it contains every kind of vehicle
in some mixture. With learning on, the weights drift negative: the population discovers that slowing
down in the light keeps it in the light, which is the lover's strategy. Each caption is the step count and
the mean light at the vehicles' bodies.

```diffstatic_runner {data-lang=kotlin video="assets/braitenberg_vehicles/learning.mp4" capture="box" open-outputs="true" wait-output="true" wait-output-kind="display" execution="launch" duration="13" fps="20" timeout="600" w="1000" embed=true name="vehicles-learning-video" alt="two populations of randomly wired vehicles among four lights; on the left the weights learn and the vehicles gather and linger at the lights, on the right they never learn and keep wandering"}
val lights = [Light(60.0, 60.0), Light(180.0, 70.0), Light(120.0, 180.0), Light(200.0, 200.0)]
val learn = VehicleParams(
    neuron = NeuronParams(bias = -1.0),
    plasticity = PlasticityParams(learningRate = 3.0),
)
val control = learn.copy(plasticity = learn.plasticity.copy(learningRate = 0.0))

val a = VehicleWorld.random(80, lights, seed = 5L)
val b = a.copy(seed = 6L)
val canvasA = VehicleCanvas(a, scale = 1.2)
val canvasB = VehicleCanvas(b, scale = 1.2)

animateEvolution(
    Unit,
    totalSteps = 30000,
    seconds = 12.0,
    framesTarget = 240,
    columns = 2,
    cellSide = 300,
    step = {
        a.step(learn)
        b.step(control)
        canvasA.record()
        canvasB.record()
    },
    panels = { _, n ->
        [
            "learning " + n + ": " + a.meanLight().f2 to canvasA.image(),
            "fixed " + n + ": " + b.meanLight().f2 to canvasB.image(),
        ]
    },
)
```

The clip runs 30 000 steps. The learning population climbs for about the first 20 000 and then levels
off near 0.59, with the summed weight per vehicle settled around −1.6; out to 80 000 steps neither changes by more than its own wobble. Over three seeds, after 15 000 steps, the mean light at the body in the last 2000 steps is 0.58, 0.55
and 0.62 with learning against 0.41, 0.42 and 0.44 without, and the summed weight per vehicle has moved
down by between 0.6 and 1.5. A much larger `learningRate` overshoots: at 80 the weights
become inhibitory enough that the faint background light between the lamps stops the vehicles too, and
the mean light falls to about 0.3.

## 5. Circling at a set distance

`BUMP` is quiet below its preferred input and quiet above it. Put it into the aggressor's crossed
excitatory wiring and the vehicle behaves like an aggressor while the light is dimmer than the preferred
level and like a coward once it is brighter, so it neither reaches the lamp nor leaves it: it circles.
`orbitBias(distance)` returns the `bias` that puts the bump's peak at the brightness a sensor sees
`distance` away from a lamp. The membrane has to be fast (`tauMembrane = 1.0`): with the default 4 steps
of lag the vehicle has driven past the ring before its neurons notice, and the population scatters
again.

`OrbitRadii` records every vehicle's distance to its nearest lamp once per step. The box tunes four
populations to four distances, lets them settle for 6000 steps, draws each population's trails
(captioned tuned distance -> median) and
hands the last 2000 steps of distances to `violin`, one violin per tuned distance on a shared 0 to 60
axis, with the tuned distance as a dashed yellow line across its own violin.

```diffstatic_runner {data-lang=kotlin screenshot="assets/braitenberg_vehicles/orbits.png" embed=true wait="20.0" timeout="300" capture="box" open-outputs="true" padding="20"}
val lights = [Light(60.0, 60.0), Light(180.0, 70.0), Light(120.0, 180.0), Light(200.0, 200.0)]
val panels = mutableListOf<Pair<String, Img>>()
val groups = mutableListOf<Pair<String, List<Double>>>()
for (d in [8.0, 12.0, 16.0, 20.0]) {
    val p = VehicleParams(
        neuron = NeuronParams(activation = Activation.BUMP, gain = 4.0, tauMembrane = 1.0, bias = orbitBias(d)),
    )
    val world = VehicleWorld.random(60, lights, wiring = VehicleWorld.AGGRESSOR, seed = 3L)
    val canvas = VehicleCanvas(world, scale = 1.2, fade = 0.99f)
    world.stepTimes(p, 6000)
    val radii = OrbitRadii()
    repeat(2000) {
        world.step(p)
        radii.observe(world)
        canvas.record()
    }
    println(
        "tuned ${d.f2}: median ${radii.median().f2}, middle half ${radii.quantile(0.25).f2} to ${radii.quantile(0.75).f2}",
    )
    panels += (d.f2 + " -> " + radii.median().f2) to canvas.image()
    groups += ("tuned " + d.f2) to radii.distances(every = 40)
}
showImg(montage(panels, columns = 2, cellSide = 300))
display(
    "radii",
    violin(
        *groups.toTypedArray(),
        references = [8.0, 12.0, 16.0, 20.0],
        yRange = 0.0..60.0,
        yTitle = "distance to nearest lamp",
    ),
)
```

Tuned to 8, 12, 16 or 20, the vehicles settle on rings of median radius 10.2, 14.4, 19.2 and 24.5:
wider than asked for, because the sensors sit 3 units ahead of the body and the inner sensor has to be
just past the peak for the turn to balance. The middle half of each distribution is under a unit wide
at 8 and 12 and about one and a half at 20. At 20 a few vehicles are not on a ring yet: the tails of the bump are too
faint 60 units from a lamp to steer by, so a vehicle that starts far away has to drift close before it
is caught.

## 6. A price on speed

The reward on the learning level is the light at the body. `PlasticityParams.speedCost` subtracts
`speedCost · speed` from it, so a vehicle that keeps the same light while driving slower is the better
one. Each arm below is the same 80 randomly wired vehicles from section 4, learning for 15 000 steps,
with four prices; each caption is the price and the mean speed over the last 2000 steps.

```diffstatic_runner {data-lang=kotlin screenshot="assets/braitenberg_vehicles/speed-cost.png" embed=true wait="30.0" timeout="300" capture="box" open-outputs="true" padding="20"}
val lights = [Light(60.0, 60.0), Light(180.0, 70.0), Light(120.0, 180.0), Light(200.0, 200.0)]
val panels = mutableListOf<Pair<String, Img>>()
for (cost in [0.0, 0.2, 0.5, 1.0]) {
    val p = VehicleParams(
        neuron = NeuronParams(bias = -1.0),
        plasticity = PlasticityParams(learningRate = 3.0, speedCost = cost),
    )
    val world = VehicleWorld.random(80, lights, seed = 5L)
    val canvas = VehicleCanvas(world, scale = 1.2)
    world.stepTimes(p, 15000)
    var light = 0.0
    var speed = 0.0
    repeat(2000) {
        world.step(p)
        canvas.record()
        light += world.meanLight() / 2000
        speed += world.meanSpeed() / 2000
    }
    println(
        "speedCost ${cost.f2}: light ${light.f3}, speed ${speed.f3}, summed weight ${(world.w.sum() / world.count).f2}",
    )
    panels += ("cost " + cost.f2 + ": " + speed.f2) to canvas.image()
}
showImg(montage(panels, columns = 4, cellSide = 240))
```

Without a price the learned population drives at 0.90 and holds 0.58 of light. At a price of 0.5 it
drives at 0.68 and holds 0.54; at 1.0, 0.53 and 0.51. The weights carry the difference: the summed
weight per vehicle goes from −1.4 to −6.3 and −7.6, more inhibitory, so the motors are quieter
everywhere, not just at the lamps. The trails get shorter and the vehicles spend their time parked.

## 7. Learning and homeostasis together

Homeostasis made the fixed lovers of section 3 forage; learning made random wirings into lovers in
section 4. Turning both on in the same population gives neither result in full. The box runs the
four combinations from one seed for 20 000 steps and reports the last 5000.

```diffstatic_runner {data-lang=kotlin screenshot="assets/braitenberg_vehicles/combined.png" embed=true wait="40.0" timeout="300" capture="box" open-outputs="true" padding="20"}
val lights = [Light(60.0, 60.0), Light(180.0, 70.0), Light(120.0, 180.0), Light(200.0, 200.0)]
val base = VehicleParams(neuron = NeuronParams(bias = -1.0))

fun arm(name: String, adaptation: Double, learning: Double): Pair<String, Img> {
    val p = base.copy(
        neuron = base.neuron.copy(adaptationRate = adaptation),
        plasticity = PlasticityParams(learningRate = learning),
    )
    val world = VehicleWorld.random(80, lights, seed = 5L)
    val canvas = VehicleCanvas(world, scale = 1.2, fade = 0.998f)
    world.stepTimes(p, 15000)
    val visits = LightVisits(world)
    var light = 0.0
    repeat(5000) {
        world.step(p)
        visits.observe(world)
        canvas.record()
        light += world.meanLight() / 5000
    }
    println(name + ": light " + light.f3 + ", lights visited " + visits.mean().f2)
    return (name + ": " + light.f2) to canvas.image()
}

showImg(
    montage(
        [
            arm("neither", 0.0, 0.0),
            arm("homeostasis", 0.002, 0.0),
            arm("learning", 0.0, 3.0),
            arm("both", 0.002, 3.0),
        ],
        columns = 2,
        cellSide = 300,
    ),
)
```

The untrained random population holds 0.40 and drifts through 3.8 lights each in 5000 steps, because
most of its vehicles are aggressors and cowards that never stop. Homeostasis on its own makes that
population worse (0.31): boredom is a way out of a parked state, and these vehicles were not parked.
Learning alone holds 0.58. Both together hold 0.50, between the two, and visit 0.9 lights where
learning alone visits 0.6. Over seeds 5, 7 and 9 that order holds every time: with both on, the mean
light is 0.46 to 0.51 against 0.55 to 0.59 for learning alone. The learned weights are less inhibitory
with homeostasis on (−0.5 to −1.1 against −0.3 to −1.5 summed), because the threshold is doing part of
the stopping for them.

## 8. Moving lights

A `Light` takes a velocity, `vx` and `vy` in units per step, and `VehicleWorld.step` moves it across the
torus, and `withLights` moves a population onto a different set of lamps. The box below trains the
section 4 population on still lamps for 15 000 steps, freezes its
weights, then sets every lamp drifting at 0.1 units per step in a different direction. Beside it are
Braitenberg's lovers on the same moving lamps.

```diffstatic_runner {data-lang=kotlin video="assets/braitenberg_vehicles/moving.mp4" capture="box" open-outputs="true" wait-output="true" wait-output-kind="display" execution="launch" duration="13" fps="20" timeout="600" w="1000" embed=true name="vehicles-moving-video" alt="two panels of vehicles among four drifting lamps: on the left the learned population mostly loses the lamps, on the right the lovers sit in front of each lamp and are dragged along behind it"}
val still = [Light(60.0, 60.0), Light(180.0, 70.0), Light(120.0, 180.0), Light(200.0, 200.0)]
val moving = still.mapIndexed { k, l -> l.copy(vx = 0.1 * cos(k * 1.7), vy = 0.1 * sin(k * 1.7)) }
val p = VehicleParams(neuron = NeuronParams(bias = -1.0))

val trained = VehicleWorld.random(80, still, seed = 5L)
trained.stepTimes(p.copy(plasticity = PlasticityParams(learningRate = 3.0)), 15000)
val learned = trained.withLights(moving, seed = 11L)
val lovers = VehicleWorld.random(80, still, wiring = VehicleWorld.LOVER, seed = 5L).withLights(moving, seed = 11L)
val canvasA = VehicleCanvas(learned, scale = 1.2, fade = 0.98f)
val canvasB = VehicleCanvas(lovers, scale = 1.2, fade = 0.98f)

animateEvolution(
    Unit,
    totalSteps = 3000,
    seconds = 12.0,
    framesTarget = 240,
    columns = 2,
    cellSide = 300,
    step = {
        learned.step(p)
        lovers.step(p)
        canvasA.record()
        canvasB.record()
    },
    panels = { _, n ->
        [
            "learned " + n + ": " + learned.meanLight().f2 to canvasA.image(),
            "lovers " + n + ": " + lovers.meanLight().f2 to canvasB.image(),
        ]
    },
)
```

The learned wiring does not follow. On still lamps it held 0.58; on lamps drifting at 0.1, measured
over 4000 steps after a 1000-step settle, it holds about 0.41, and keeping the learning on while the lamps move does not recover it (0.38). The lovers
hold 0.75, more than they did on still lamps, because a lamp moving toward a parked lover brings its
light with it. `trailAlong()` measures how far behind a moving lamp the vehicles near it sit, along the
lamp's direction of travel: the lovers trail by about 0.8 units at 0.1, 7 units at 0.2, and 14 at 0.4,
where they are being dragged at the edge of what their sensors can see. What the learned population
found is the lover's strategy only on average: a mixture of weakly inhibitory wirings that slow down in
bright places, which is enough for a lamp that waits and not enough for one that leaves.

## 9. Searching for a wiring

The four wirings are four corners of a four-dimensional space, and learning is one way to move through
it. `VehicleWiringGenome` puts the same four weights on [`PopulationSearch`](alife_examples.md), the
engine the Lenia and Life-rule searches use, and `vehicleForageObjective(lights)` scores a wiring by
giving it to 30 vehicles and taking the mean light at their bodies over the last 1000 of 3000 steps.
As on the ALife page, a random-sampling control at the same budget and seed runs beside the search.

```diffstatic_runner {data-lang=kotlin screenshot="assets/braitenberg_vehicles/evolved.png" embed=true wait="40.0" timeout="300" capture="box" open-outputs="true" padding="20"}
val lights = [Light(60.0, 60.0), Light(180.0, 70.0), Light(120.0, 180.0), Light(200.0, 200.0)]
val ops = VehicleWiringGenome()
val objective = vehicleForageObjective(lights)

val control = PopulationSearch.random(ops, objective, 300, seed = 13L, label = "random")
val evolved =
    PopulationSearch.evolve(ops, objective, 300, seed = 13L, mu = 6, lambda = 12, crossoverRate = 0.3, label = "evolve")
println(control.summary())
println(evolved.summary())
println("lover " + objective.evaluate(VehicleWorld.LOVER).fitness.f3)
val best = evolved.best!!.genome
println("evolved wiring " + best.joinToString { it.f2 })

fun trails(name: String, wiring: DoubleArray): Pair<String, Img> {
    val world = VehicleWorld.random(30, lights, wiring = wiring, seed = 1L)
    val canvas = VehicleCanvas(world, scale = 1.2)
    repeat(3000) {
        world.step(VehicleParams(neuron = NeuronParams(bias = -1.0)))
        canvas.record()
    }
    return (name + ": " + world.meanLight().f2) to canvas.image()
}
showImg(montage([trails("lover", VehicleWorld.LOVER), trails("evolved", best)], columns = 2, cellSide = 300))
```

Both arms find wirings that hold about 1.0 of light (0.99 for the random control, 1.02 for evolution),
against 0.64 for the lover on the same test and 0.58 for the learned population. The trails show the
evolved wiring on a different set of starting positions. The evolved wiring is not one of the four corners: it is lopsided, one
motor strongly driven by one sensor and inhibited by the other, so the vehicle turns in a tight circle
on top of the lamp it found instead of parking in front of it. Evolution does not beat random sampling
by much here (at seeds 17 and 19 the two are within 0.01), for the reason the
radius-1 Life rules gave on the ALife page: four numbers is a space a few hundred independent draws
cover on their own. A genome that carried the neuron parameters as well, or a test with moving lamps,
would give the search something to do.
