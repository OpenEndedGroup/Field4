# A Petri net that throws sparks

On this page one Petri net plays a drum part from the Dirt-Samples and, at the same time, drives a
GPU particle system. Each time a transition fires, a burst of sparks leaves the place its token
came from, in that transition's colour. The net knows nothing about the particles, and the
particle shader knows nothing about Petri nets: the box in between asks the net what has fired
recently and writes the answer into a buffer.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_petri_sparks/sparks.png" capture="native-window" native-wait-frames="215" wait-output=true wait-output-kind="all" wait-output-contains="sparks ready" embed=true hidden=true timeout="240" name="chain-petri-sparks-hero" alt="Three concentric rings of faint dust on black, cyan inside, orange in the middle and gold outside, each with a bright comet where its token is. A fresh pale-gold burst of streaks is leaving the hat ring at the lower left, a larger orange burst from the kick ring is spreading at the lower right, and older cyan and green bursts are falling as fading rain near the top" macro-file="../../dev_docs/agents/transcripts/petri_sparks.yaml" capture-box="ps_sparks"}
// Built and launched from dev_docs/agents/transcripts/petri_sparks.yaml.
```

The three rings are the net's three token rings, drawn from the centre out: the snare's three
places, the kick's four and the hats' five. Each bright comet is a ring's token walking clockwise
from place to place. A fresh burst is still a tight white-hot ball; an older one has
spread, taken on its transition's colour and begun to fall. The clap, which fires when four kicks
have filled a place called `charge`, throws a wider magenta spray from the centre.

## The net

Three rings of places share one eighth-note grid. A ring of *n* places brings its token back to the
start every *n* eighths, so the kick, snare and hats run at three different periods and drift
against each other. The kick also drops a token into `charge` every lap; `charge` holds at most
four, and the transition that takes all four out at once is the clap.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_petri_sparks/net.png" alt="the Petri net drawn as three token rings and one extra place: rings of three, four and five places, each with one ringed marked place and a green transition ready to fire, and a fourth place, charge, fed by the kick transition and drained by the clap transition. Below it, a summary listing the transitions that make a sound and the cycle at which the first clap fires" wait="2.5" capture="box" open-outputs="true" embed=true name="chain-petri-sparks-net" w="1240" padding="12"}
:::static
import afield.petri.*

val net = petri("""
    #petri
    !grid 1/8

    // four places round: a kick each lap, and a token into charge
    k0 : 1
    k0 -[ kick : bd ]-> k1 + charge
    k1 -[ k1.go ]-> k2
    k2 -[ k2.go ]-> k3
    k3 -[ k3.go ]-> k0

    // three places round: a snare each lap
    s0 : 1
    s0 -[ snare : sn ]-> s1
    s1 -[ s1.go ]-> s2
    s2 -[ s2.go ]-> s0

    // five places round: two hats each lap
    h0 : 1
    h0 -[ hat : hh ]-> h1
    h1 -[ h1.go ]-> h2
    h2 -[ hat.2 : hh ]-> h3
    h3 -[ h3.go ]-> h4
    h4 -[ h4.go ]-> h0

    // four kicks fill charge, and emptying it is a clap
    charge : 0 ^4
    4*charge -[ clap : cp ]->
""")

:::fold draw it, and find the first clap
val run = net.run()
val firstClap = run.firingsOver(afield.four.synth.PatArc(cyc(0), cyc(4))).first { it.transition == "clap" }
showPetriNet(net, w = 900.0, h = 560.0)
markdown("main", """
    #### three rings and a counter

    - transitions that make a sound: ${net.transitions.filter { it.emit != null }.map { "`${it.name}` → `${it.emit}`" }.joinToString(", ")}
    - the first clap fires at cycle **${firstClap.at}**, one step after the fourth kick
""")
displayOpen("main")
:::endfold
```

The text after each colon is what a transition emits, and these are Dirt-Samples folder names, so
`net.emits()` is already a drum pattern.

## What it sounds like

Eight cycles at half a cycle a second, played by `patRenderStereoDirt` from the pinned
Dirt-Samples:

```diffstatic_runner {data-lang=kotlin audio="assets/chain_petri_sparks/petri-sparks-drums.wav" audio-spec="petri-sparks-drums" audio-renderer="afield.four.synth.PetriSparksDocs" embed=true hidden=true name="chain-petri-sparks-audio"}
// Offline-rendered by PetriSparksDocs.renderWav("petri-sparks-drums").
```

To play it live from a box instead, hand the same pattern to a Dirt player:

```diffstatic {data-lang=kotlin}
dirt("/path/to/Dirt-Samples")
net.emits().playDirt(dirtPlayer(), cps = 0.5)
```

## From firings to a buffer

The net box publishes the net and one run of it, `prop<PetriRun>(sparkRun)`, and the sparks box
below it reads that run once a frame. A run remembers the steps it has already taken, so asking it
about the last second and a half each frame is cheap. Three questions get the whole picture:

```diffstatic {data-lang=kotlin}
// every firing in the last `life` seconds: one burst each
val recent = sparkRun.firingsOver(PatArc(since, at)).filter { it.transition in launches }

// which place of each ring holds the token: where the comets are
val marking = sparkRun.markingAt(at)

// how full charge is, 0 to 4: the dust brightens as it fills
charge = sparkRun.countAt("charge", at) / 4.0
```

Each recent firing becomes one `vec4` in a small buffer made with `perFrame = true`, so it can be
rewritten every frame without waiting for the GPU. The four numbers are where the burst started,
how old it is in seconds, and which transition fired, with a per-firing seed in the fraction so no
two bursts look alike:

```diffstatic {data-lang=kotlin}
val (colour, ring, place) = launches.getValue(firing.transition)
val age = (now - firing.at.toDouble()) / cps
put(vec(r * Math.cos(a), r * Math.sin(a), age, colour + (firing.index % 4093) / 4096.0))
```

`firing.at` is the instant in cycles that the transition fired, the same instant the drum is struck
in the audio above.

## In the vertex shader

There is no compute pass and no particle state on the GPU: every spark's position is a function of
its burst and its age, so the vertex shader works it out from scratch each frame. A spark leaves at
a random angle and speed, slows under drag and falls under gravity, and is drawn as a short streak
along its velocity:

```diffstatic {data-lang=glsl}
centre = burst.xy + v * (1.0 - exp(-drag * age)) / drag + 0.5 * gravity * age * age;
vec2 velocity = v * exp(-drag * age) + gravity * age;
along = normalize(velocity + vec2(1e-5));
halfLength = 0.003 + 0.045 * length(velocity);
```

The module blends with `srcColor` and `dstColor` both set to `VK_BLEND_FACTOR_ONE`, so overlapping
sparks add up and the centre of a fresh burst burns white.

??? aside "Why the figure is a still, and silent"

    The docs runner records one frame of the native window, not a clip. The sparks box counts
    frames at sixty a second as its clock rather than reading the audio clock, so the figure is the
    same on a machine with no sound output.

## Where each piece is documented

* [Petri nets](petri_nets.md) for the notation, `emits()`, runs and markings.
* [A pattern that moves particles](chain_pattern_particles.md) for the other way of tying sound to
  particles, by measuring the audio rather than reading the score.
* [afield.four Graphics](afield_four_graphics.md) for modules, meshes, buffers and push constants.
* [Making a sound, every path there is](sound_paths.md) for the Dirt-Samples and the other ways to
  play a pattern.
