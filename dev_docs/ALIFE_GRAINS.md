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
- Without a `MODE_SHARE` term the search was gamed by a one-pitch trunk drone (`G → GGG[---G]G`
  scored 0.965). `MODE_SHARE` (share of grains on the most common pitch, trapezoid 0,0,0.3,0.6) is
  now in `defaultGrainTerms()`; the drone scores < 0.9 (test), and the page's seeded run (budget 400,
  seed 7) ends at 125 grains, median 56, 22% on one pitch (before the 8x slow-down; after it: 64 grains, median 66, 12.4 s, 19% on one pitch, G → GG+[---G-G]).
- Seeded test run after the change: 0.832 → 0.847 → 0.947 → 0.969 → 0.997 → 1.000 (gens 1,2,3,6,7,12).
- `display(channel, …)` and `showImg` REPLACE a channel's content: sliders/plots/players each need
  their own channel.
- Short clips (<1 s) show "0:00" in the audio player; the breeder's random gen-0 children are often
  that short.
- Kotlin daemon OOM on a cold worktree: pass a distinct `-Pkotlin.daemon.jvmargs=-Xmx13g`.

## Follow-up pass (2026-11-11, branch claude/grains-next-641cc6)
Worked the page's "next builds" list. Done:
- **More symbols.** `<` `>` (length ×/÷ `lengthStep`), `*` `/` (time step ÷/× `densityStep`), `l` `r`
  (`Grain.pan` ± `panStep`), all saved by brackets. `GRAIN_ALPHABET` / `GRAIN_ALPHABET_EXTENDED`;
  the default genome is unchanged, so the earlier seeded captures stay valid.
- **Several productions.** `grainLSystemGenome(nonterminals = "X")` (LSystemGenome already supported
  several symbols; the decoder already ignored `X`). Rejects decoder symbols as nonterminals.
- **Novelty/MAP-Elites.** `grainSetObjective(behaviour = [...])` picks the descriptor. MAP-Elites over
  (spread 0..12, polyphony 0..6), 4×4, budget 800, seed 1: 16/16 cells, QD 14.09.
- **Target from a recording.** `grainsFromAudio` (wraps `patF0Frames` + `patSegmentNotes`, RMS amp)
  and `grainTermsAround` (plateau ±25%, feet ×4, median pitch ±2 st, per-stat floors). Recording =
  the 11 arpy samples concatenated: read back as MIDI 60,62,65,67,69,72,74,77,79,81,84, 1.45 s.
  Round trip on rendered tones recovers pitch exactly and onsets within 60 ms (test).
- **Grains from a sample.** `renderGrains(sourcePitch = ...)`; the `matched` clip plays the evolved
  set from arpy01 at its measured pitch (59.98).
- Render guard: `renderGrains(maxSeconds = 600)` and skips non-finite grains — a run of `>`/`/` made
  1.5^200-second grains (crash guard, not a range clamp). Pan coerced to ±1 only because
  `sqrt(1 - pan)` is NaN past it.

Numbers (budget 600, terms around arpy):
- 8 seeds: plain alphabet mean best 0.823, worst 0.801; extended mean 0.871, worst 0.634.
- Seed 7 extended: 0.967, `G → l<lG+*G*+*`, 8 grains, median 71, 1.53 s. 11 grains is hard at 3
  generations (counts come in powers), so the count term is never on its plateau.
- Nonterminal X, 2 seeds: plain 0.812/0.833 vs 0.801/0.907 without; extended 0.812/0.801 vs
  0.967/0.896. No benefit on this target.

Skipped, and why:
- **Breeder lineage column.** A UI design decision (how many generations of players to keep, how a
  replaced channel is kept) and the breeder is interactive, so no docs capture can show it. Left on
  the page's list; `GrainBreeder.history` already holds the data.
- **Trees that compete for sound** (page-wide list). It belongs to the tree-growth work, not this
  file: it needs the light-competition tree's branch structure mapped to time, which is a decoder of
  its own. Left on the page's list.
- **Polyphonic recordings.** No multi-pitch detector in Field; `patSegmentNotes` refuses chords by
  design (`notOneNote`).

Traps:
- The page's arpy target has no notion of direction: the winner matches span/spread/median and is
  not a rising line. A contour statistic is the next build.
- `map.elites[[col, row]]` with a collection literal key compiles in a box.
