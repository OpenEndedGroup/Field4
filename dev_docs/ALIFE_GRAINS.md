# Evolved grain sets (alife_grains.kt)

After Nathan Ho's EvoGrain (https://nathanho.me/posts/evograin; scout card 2db65f180b8e). Built from
the method description; nothing copied.

## Method
- Decoder: `decodeGrainString` walks a derived string. `G` emit grain + advance time, `f` rest,
  `+`/`-` pitch step, `[`/`]` push/pop (time, pitch, amp, scale). A branch restores *time*, so
  brackets = polyphony. Branches multiply amp by `branchAmp` and grain length/time step by
  `branchDuration`. Unmatched `]` ignored; `maxGrains` caps output.
- Fitness: `GrainSetStats` (count, median pitch, pitch SD, duration, density, mean polyphony =
  total sounding time / span) → `Trapezoid` per term → weighted mean in 0..1 (`grainSetFitness`).
- Genome: no new type. `grainLSystemGenome()` = `LSystemGenome(axiom "G", symbols "G",
  alphabet "GG+-[]f")`; search is `PopulationSearch.evolve`.
- Sound: `renderGrains` is an offline sample-accurate scheduler (Hann tones, or a `source` sample
  read GrainCloud-style). `GrainCloud` was not usable: it spawns grains stochastically from
  density/position params and cannot play a fixed list at fixed onsets.
- `GrainBreeder`: interactive evolution, elitist (chosen parents survive), seeded so a sequence of
  choices replays.

## Numbers (2026-11-11)
- `GrainLSystemTest` seeded run (budget 240, seed 3): 0.849 → 0.936 → 1.000 by generation 4.
- Transcript box 2 (budget 300, seed 7): best 0.965, 26 gens, 107 crossover children; winner
  `G → GGG[---G]G`, 125 grains, 5.79 s.

## Traps
- The default terms saturate fast and are gamed by a continuous trunk drone (`GGG...` overlapping
  grains read as polyphony). A harder profile (e.g. a density or pitch-entropy term, or penalising
  overlap on one pitch) is the obvious next step.
- `display(channel, …)` and `showImg` REPLACE a channel's content: sliders/plots/players each need
  their own channel.
- Short clips (<1 s) show "0:00" in the audio player; the breeder's random gen-0 children are often
  that short.
- Kotlin daemon OOM on a cold worktree: pass a distinct `-Pkotlin.daemon.jvmargs=-Xmx13g`.
