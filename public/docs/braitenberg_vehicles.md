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

Per step, a motor neuron with input `I`, rate `r` and threshold `θ` does

```diffstatic {data-lang=text}
u += (−u + I + selfExcitation·r − θ − bias) / tauMembrane
r  = activation(gain · u)
θ += adaptationRate · (r − targetRate)
```

The activation is one of four `Activation` values. `SIGMOID`, `TANH` and `RELU` are switches of
different shapes; `BUMP` (`e^(−x²)`) is tuned to a preferred input and is quiet above and below it.

With `selfExcitation = 0` the neuron is a smoothed function of its input. Feed enough of its own
output back, and a sigmoid becomes bistable: once on, the neuron's own rate holds it on after the input
has fallen below the level that turned it on. `hysteresisSweep` drives the input slowly from −2.5 to 2
and back and reports where the rate crosses 0.5 on each leg.

```diffstatic_runner {data-lang=kotlin screenshot="assets/braitenberg_vehicles/hysteresis.png" wait="5.0" capture="box" open-outputs="true" padding="20"}
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

```diffstatic_runner {data-lang=kotlin screenshot="assets/braitenberg_vehicles/zoo.png" wait="12.0" capture="box" open-outputs="true" padding="20"}
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

## 3. Getting bored: homeostasis

A lover parks in front of the first light it finds and stays there. That is a fixed point, and nothing
in a memoryless vehicle can leave it. Give the threshold a slow integrator instead, so that a neuron
held below its `targetRate` for long enough lowers its own threshold, and the parked vehicle's motors
creep back on until it drives away to find another light. Its neurons then run hot in the dark, the
thresholds climb again, and it arrives at the next light able to be stopped by it.

`LightVisits` counts arrivals at a light other than the last one.

```diffstatic_runner {data-lang=kotlin screenshot="assets/braitenberg_vehicles/bored.png" wait="20.0" timeout="240" capture="box" open-outputs="true" padding="20"}
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

```diffstatic_runner {data-lang=kotlin video="assets/braitenberg_vehicles/learning.mp4" capture="box" open-outputs="true" wait-output="true" wait-output-kind="display" execution="launch" duration="9" fps="20" timeout="400" w="1000" embed=true name="vehicles-learning-video" alt="two populations of randomly wired vehicles among four lights; on the left the weights learn and the vehicles gather and linger at the lights, on the right they never learn and keep wandering"}
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
    totalSteps = 15000,
    seconds = 8.0,
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

Over three seeds, after 15 000 steps, the mean light at the body in the last 2000 steps is 0.58, 0.55
and 0.62 with learning against 0.41, 0.42 and 0.44 without, and the summed weight per vehicle has moved
down by between 0.6 and 1.5. A much larger `learningRate` overshoots: at 80 the weights
become inhibitory enough that the faint background light between the lamps stops the vehicles too, and
the mean light falls to about 0.3.

## Where to take it

- Combine the levels: learned lovers with homeostasis on still forage (1.7 visits per vehicle over
  17 000 steps) and still hold more light than the untrained population: 0.49 against about 0.41.
- Swap `Activation.BUMP` into the excitatory wirings for vehicles that circle a light at a set distance.
- Give the reward a cost for speed, or make the lights move, and see whether the learned wiring
  follows.
