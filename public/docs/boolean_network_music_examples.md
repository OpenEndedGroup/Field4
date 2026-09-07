# Boolean network music examples

*Every public declaration used below is listed, with its KDoc, in the codebase reference: [`booleannetworks.kt`](reference/data.md#booleannetworks-kt). Start from [what's already in the box](reference/index.md).*

Boolean networks become musically useful once their states acquire time. Give each network step a duration and the state stream can be read as notes, labels, triggers, and sample hits. The bridge lives inside `afield.four.synth`.

The bridge is a handful of extension functions on `BooleanNetwork`, all in `BooleanNetworkSequencing.kt`:

- `scheduledSteps(...)` for timed Boolean states.
- `scheduledNotes(...)` for direct `Iterator<ScheduledNote>` mapping.
- `asNoteProcess(...)` for piano-roll friendly note streams.
- `scheduledDrivenSteps(...)` and `asDrivenNoteProcess(...)` for external clocks and oscillators.
- `respondAhead(...)` for live clocked side effects such as sample triggering.

## From attractor cycle to `NoteProcess`

Take a small attractor, map rising edges to pitches, and render the result as a piano roll. `step.lanes(...)` takes one of four trigger modes and the choice does most of the musical work: a node that stays on for six steps gives six notes under `ACTIVE`, which is the default, and one under `RISING_EDGE`, which is what this fence asks for. `FALLING_EDGE` and `CHANGE` are the other two. Note also that the first step of a run has no previous state to compare against, so every lane already on at step zero counts as a rising edge.

```diffstatic_runner {data-lang=kotlin screenshot="assets/boolean_network_music_examples/noteprocess_roll.png" wait="1.0" capture="box"}
:::static
import afield.four.synth.*

data class Candidate(val seed: Int, val net: BooleanNetwork, val attr: Attractor)

val chosen =
    (0..80).mapNotNull { seed ->
        val net = kauffmanNetwork(n = 5, k = 2, seed = seed.toLong())
        val attr = net.findAttractors().filter { it.period >= 6 }.maxByOrNull { it.period } ?: return@mapNotNull null
        Candidate(seed, net, attr)
    }.first()

val pitches = listOf(36, 43, 48, 55, 60)
val labels = listOf("C2", "G2", "C3", "G3", "C4")
val colors = listOf("#63b3ff", "#ff9b54", "#6df0a7", "#f67bff", "#ffe46b")

val process =
    chosen.net.asNoteProcess(
        initialState = chosen.attr.cycle.first(),
        stepDuration = 0.25,
        steps = 24,
    ) { step ->
        step.lanes(BooleanTriggerMode.RISING_EDGE).map { lane ->
            note(
                pitch = pitches[lane],
                duration = step.duration * 0.84,
                start = step.start,
                velocity = 0.74 + lane * 0.04,
                label = labels[lane],
                color = colors[lane],
            )
        }
    }

val preview = process.notesBetween(0.0, 2.5).take(8).joinToString("\n")

:::fold render the piano roll and the first notes
markdown("main", """
    #### seed ${chosen.seed} · period ${chosen.attr.period} · initial ${chosen.attr.cycle.first().joinToString("") { if (it) "1" else "0" }}
    ${process.pianoRollSvg(widthPx = 860, heightPx = 210, pastSeconds = 0.0, futureSeconds = 6.0, title = "Boolean attractor -> NoteProcess")}

    ~~~
$preview
    ~~~
""")
:::endfold
```

The seed in the heading is found rather than chosen: the fence sweeps seeds 0 to 80 looking for a `kauffmanNetwork(n = 5, k = 2)` with an attractor of period 6 or more and takes the first that qualifies. What comes back is an ordinary `NoteProcess`, so `notesBetween` and `pianoRollSvg` work on it exactly as they do on a hand-written one.

![](assets/boolean_network_music_examples/noteprocess_roll.png)

## Previewing Dirt tokens before playback

The same timing layer can carry Dirt sample labels instead of note names, which is how you inspect the Boolean rhythm as a sample rack before anything is played.

```diffstatic_runner {data-lang=kotlin screenshot="assets/boolean_network_music_examples/dirt_token_roll.png" wait="1.0" capture="box"}
:::static
import afield.four.synth.*

val net = kauffmanNetwork(n = 5, k = 2, seed = 27L)
val attr = net.findAttractors().maxByOrNull { it.period }!!
val tokens = listOf("bd:3", "sn:1", "hh:4", "cp:0", "perc:5")
val colors = listOf("#69b8ff", "#ff8e5a", "#c4ff5f", "#ff6cc4", "#ffe36e")

val preview =
    net.asNoteProcess(
        initialState = attr.cycle.first(),
        stepDuration = 0.25,
        steps = 32,
    ) { step ->
        step.lanes(BooleanTriggerMode.RISING_EDGE).map { lane ->
            note(
                pitch = 42 + lane * 3,
                duration = step.duration * 0.8,
                start = step.start,
                velocity = if (lane == 0) 1.0 else 0.82,
                label = tokens[lane],
                color = colors[lane],
            )
        }
    }

:::fold render the Dirt-token piano roll
markdown("main", """
    #### seed 27 · longest attractor period ${attr.period} · labels are direct Dirt tokens
    ${preview.pianoRollSvg(widthPx = 860, heightPx = 210, pastSeconds = 0.0, futureSeconds = 8.0, title = "Boolean attractor -> Dirt token preview")}
""")
:::endfold
```

The symbols in the preview are the exact ones a live Dirt player would receive, so a typo in a sample name shows up here rather than as silence later.

![](assets/boolean_network_music_examples/dirt_token_roll.png)

## Braiding several Boolean rhythms together

This example braids three Boolean attractors at different temporal resolutions: a kick-like layer on eighths, a change-trigger layer on dotted eighths, and a high-density active-state shimmer on sixteenths. The three grids are 2, 3 and 1 sixteenths wide, so they all coincide every six sixteenths — 0.375 s — and the braid relocks rather than drifting.

```diffstatic_runner {data-lang=kotlin screenshot="assets/boolean_network_music_examples/braided_rhythm.png" wait="1.0" capture="box"}
:::static
import afield.four.synth.*

fun <T> Iterator<T>.collectAll(): List<T> {
    val out = mutableListOf<T>()
    while (hasNext()) out += next()
    return out
}

data class Layer(
    val seed: Long,
    val stepDuration: Double,
    val steps: Int,
    val mode: BooleanTriggerMode,
    val basePitch: Int,
    val label: String,
    val color: String,
    val velocity: Double,
)

data class Chosen(val layer: Layer, val net: BooleanNetwork, val attr: Attractor)

val layers =
    listOf(
        Layer(seed = 27L, stepDuration = 0.125, steps = 64, mode = BooleanTriggerMode.RISING_EDGE, basePitch = 36, label = "bd", color = "#69b8ff", velocity = 0.98),
        Layer(seed = 11L, stepDuration = 0.1875, steps = 48, mode = BooleanTriggerMode.CHANGE, basePitch = 50, label = "sn", color = "#ff8e5a", velocity = 0.76),
        Layer(seed = 42L, stepDuration = 0.0625, steps = 64, mode = BooleanTriggerMode.ACTIVE, basePitch = 74, label = "hh", color = "#c4ff5f", velocity = 0.42),
    )

val chosen =
    layers.map { layer ->
        val net = kauffmanNetwork(n = 5, k = 2, seed = layer.seed)
        val attr = net.findAttractors().maxByOrNull { it.period }!!
        Chosen(layer, net, attr)
    }

val notes =
    buildList {
        chosen.forEach { ch ->
            addAll(
                ch.net.scheduledNotes(
                    initialState = ch.attr.cycle.first(),
                    stepDuration = ch.layer.stepDuration,
                    steps = ch.layer.steps,
                ) { step ->
                    val lanes =
                        when (ch.layer.mode) {
                            BooleanTriggerMode.ACTIVE ->
                                step.state.indices.filter { lane -> step.isActive(lane) && (lane + step.stepIndex.toInt()) % 2 == 0 }.take(2)
                            else -> step.lanes(ch.layer.mode)
                        }

                    lanes.map { lane ->
                        note(
                            pitch = ch.layer.basePitch + lane * 2,
                            duration = if (ch.layer.mode == BooleanTriggerMode.ACTIVE) step.duration * 0.42 else step.duration * 0.78,
                            start = step.start,
                            velocity = (ch.layer.velocity - lane * 0.04).coerceAtLeast(0.2),
                            label = "${ch.layer.label}:$lane",
                            color = ch.layer.color,
                        )
                    }
                }.collectAll()
            )
        }
    }.sortedWith(compareBy<ScheduledNote> { it.start }.thenBy { it.pitch })

val process =
    noteProcess(defaultPastSeconds = 0.0, defaultFutureSeconds = 8.0) {
        for (scheduled in notes) emit(scheduled)
    }

val summary =
    chosen.joinToString("\n") { ch ->
        "${ch.layer.label} seed ${ch.layer.seed} · period ${ch.attr.period} · ${ch.layer.mode} every ${ch.layer.stepDuration}s"
    }

:::fold render the braided piano roll and the layer summary
markdown("main", """
    #### Three attractors, three clocks, one shared note space
    ${process.pianoRollSvg(widthPx = 900, heightPx = 260, pastSeconds = 0.0, futureSeconds = 8.0, title = "Braided Boolean rhythm")}

    ~~~
$summary
    ~~~
""")
:::endfold
```

Read the picture and only one of the three layers behaves like an ensemble member. The kick runs the full eight seconds; the shimmer's 64 sixteenth-steps are four seconds long, so it stops dead at the halfway mark; and the snare fires twice, both at time zero, and then not again. That last one is not a bug in the figure but a consequence of how the layers are picked — `maxByOrNull { it.period }` with no minimum, unlike the fence above, which insists on period 6. Two `CHANGE` triggers at step zero and silence after is the signature of a network started on a fixed point: with nothing changing, `active != previous` can only be true on the first step, where there is no previous state to compare against. If you want three live layers, filter the seeds by period the way the first example does.

![](assets/boolean_network_music_examples/braided_rhythm.png)

## Driving the network with an oscillatory input

The driven scheduling helpers let something outside the network write into its state before each step: an oscillator forces one node, and the rest of the network updates from that forced value as if it had produced it.

```diffstatic_runner {data-lang=kotlin screenshot="assets/boolean_network_music_examples/oscillator_driven.png" wait="1.0" capture="box"}
:::static
import afield.four.synth.*

fun <T> Iterator<T>.collectAll(): List<T> {
    val out = mutableListOf<T>()
    while (hasNext()) out += next()
    return out
}

val net =
    BooleanNetwork(
        listOf(
            BoolNode("clk", "external", emptyList()) { s -> s[0] },
            BoolNode("kick", "clk XOR clap", listOf(0, 3)) { s -> s[0] xor s[3] },
            BoolNode("snare", "kick OR hat", listOf(1, 4)) { s -> s[1] || s[4] },
            BoolNode("clap", "snare AND NOT clk", listOf(2, 0)) { s -> s[2] && !s[0] },
            BoolNode("hat", "kick XOR snare", listOf(1, 2)) { s -> s[1] xor s[2] },
        )
    )

val driver = { stepIndex: Long, _: Double, _: Double, state: BooleanArray ->
    state[0] = sin(2.0 * PI * stepIndex.toDouble() / 12.0) > 0.0
}

val steps =
    net.scheduledDrivenSteps(
        initialState = BooleanArray(net.size),
        stepDuration = 0.125,
        steps = 64,
        driver = driver,
    ).collectAll()

val labels = listOf("clk", "bd", "sn", "cp", "hh")
val pitches = listOf(30, 36, 50, 57, 74)
val colors = listOf("#5fa8ff", "#69b8ff", "#ff8e5a", "#ff6cc4", "#c4ff5f")

val process =
    net.asDrivenNoteProcess(
        initialState = BooleanArray(net.size),
        stepDuration = 0.125,
        steps = 64,
        driver = driver,
    ) { step ->
        step.lanes(BooleanTriggerMode.RISING_EDGE).map { lane ->
            note(
                pitch = pitches[lane],
                duration = if (lane == 4) step.duration * 0.45 else step.duration * 0.9,
                start = step.start,
                velocity = if (lane == 0) 0.4 else 0.78 + lane * 0.04,
                label = labels[lane],
                color = colors[lane],
            )
        }
    }

lines(
    drawRhythmGrid(steps.map { it.state }, cellW = 18.0, cellH = 16.0, stepGap = 3.0, laneGap = 4.0, accentEvery = 8),
    channel = "driven-grid",
    w = 1080.0,
    h = 170.0,
    fitToSize = true,
)

:::fold render the driven piano roll
markdown("main", """
    #### lane 0 is a sine-derived square wave; lanes 1-4 are the network's response
    ${process.pianoRollSvg(widthPx = 900, heightPx = 220, pastSeconds = 0.0, futureSeconds = 8.0, title = "Oscillator-driven Boolean network")}
""")
:::endfold
```

The upper grid is the raw Boolean state and the piano roll underneath is the same run read as events, so the forced node shows up twice: as a regular stripe in the grid, and as whatever the rest of the network made of it in the roll.

![](assets/boolean_network_music_examples/oscillator_driven.png)

## Driving a live Dirt player

`respondAhead(...)` clocks the network in real time and calls back with `BooleanNetworkStep` values, which is enough to drive a `DirtSamplePlayer` directly. It does not start anything itself: it builds a `BooleanNetworkResponseSpec`, and a registered return-value handler launches that spec when it turns up as the box's last expression. So the responder runs because the fence *ends* with it, and the job it starts is registered against the box, which is what makes stopping the box stop the sound.

```diffstatic {data-lang=kotlin}
import afield.four.synth.*

val clock = musicClock().reset(started = true)
val net = kauffmanNetwork(n = 5, k = 2, seed = 27L)
val attr = net.findAttractors().maxByOrNull { it.period }!!
val tokens = listOf("bd:3", "sn:1", "hh:4", "cp:0", "perc:5")

val lib = dirt("/absolute/path/to/Dirt-Samples", preload = false)
val kit = dirtPlayer(library = lib, voices = 24, gain = 0.22.p)

audio { kit } +
    net.respondAhead(
        initialState = attr.cycle.first(),
        ahead = 0.05,
        stepDuration = 0.25,
        clock = clock,
    ) { step ->
        step.lanes(BooleanTriggerMode.RISING_EDGE).forEach { lane ->
            kit.trigger(
                token = tokens[lane],
                gain = if (lane == 0) 1.15f else 0.82f,
                pan = (lane - 2) * 0.18f,
            )
        }
    }.onBeat(every = 1.0, clock = clock)
```

For the underlying state-space pictures, continue with [Boolean networks examples](boolean_networks_examples.md). For piano-roll and synth display tooling, see [Visualizers & Controls](visualizers.md).
