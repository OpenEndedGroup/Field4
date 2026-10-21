# GA → Field·4: port design

Source: `~/temp/aaaframe/madp/ga.html` (served at `:9982/ga.html`). Interactive evolution in the
manner of Karl Sims' *Genetic Images*: the genome is a tiny reverse-Polish colour language, the
fitness function is the student's eye. Two parents (two Monaco editors) and seven children are
rendered into a 3×3 grid; clicking two tiles makes them the next parents and breeds again. A
toolbar holds the mutation rate, the target length, the resolution, **Reroll** and **Fresh
Parents**.

## Decision: keep the demo's RPN, render it on the CPU

Three hosts were possible.

1. **The demo's own RPN, interpreted on the JVM (chosen).** The language *is* the lesson: a
   student reads `x 9 * sin 0.18 + y 0.55 + 0.62 hsl` under a tile, sees what a mutation did to
   it, and writes one of their own. A faithful interpreter keeps every program from the demo, its
   LLM intro and its students' notebooks drawing the same picture in Field. Cost is not a reason
   to move it: nine tiles at 256² are 590k evaluations of a ~20-token program, which the JIT
   does in tens of milliseconds over all cores, faster than the demo's JS. And a CPU host needs
   no window or GPU, so the starting point opens and captures anywhere.
2. **Lower the RPN to GLSL.** Not cheap. The language's types are not static: `a b c if` picks
   either branch per pixel, and the branches can be a number and a colour, so the stack's slot
   types depend on the pixel. A lowering would carry a type flag and a `vec3` per slot and
   re-implement the final-stack recovery on that representation. It buys speed the CPU host does
   not need, and adds a second implementation to keep in step with the first. Not done; the
   interpreter is the reference such a lowering would be tested against.
3. **StackSL as the genome.** Field-native, GPU, and it already has an evolve layer
   (`StackSLEvolution`, the watchmaker). But it is a different language with different words, so
   the demo's programs would not run and the tile captions would stop reading as the demo's.
   It is the right genome for a *second* interactive-evolution box, and the tile grid below is
   built so StackSL (or FLines) can use it unchanged.

## API mapping

| ga.html | Field |
| --- | --- |
| `compileExpression` / `evaluateCompiled` | `ColorRpn.compile(source)` → `ColorRpnProgram`; `program.color(x, y)` / `program.render(size)` |
| `tokenizeSource` (`//` and `#` comments, whitespace) | `ColorRpn.tokens(source)` |
| `prettyExpression` (line breaks at 8 tokens / 42 chars / after `rgb hsl blend if`) | `ColorRpn.pretty(tokens)` |
| `makeChildTokens`, `applyMutation`, `nudgeLength`, `randomExpression` | `ColorRpnGenome` (a `GenomeOps<List<String>>`): `crossover(a, b)` is the demo's child, `mutations` are its eight named operators, `random` its random expression |
| mutation rate, target length (and the 0.42 seed splice, the mutation mix) | `ColorRpnBreeding` settings, demo values by default |
| 3×3 grid, click two tiles, selection highlight | `Box.evolveByEye(ops, parents, ...)` → `EyeEvolution<G>`: an output-area tile grid, generic over the genome |
| Reroll / Fresh Parents / resolution | buttons and fields in the grid's toolbar; `evolution.reroll()`, `evolution.fresh()` from code |
| editing a parent regenerates | the parents are strings in the lead box; edit and re-run (alt-R) |
| picked tiles' code goes into the editors | the panel shows both parents in full under the grid, selectable; `onPick { }` hands them to box code |
| Monaco AI completion, "Copy LLM intro", local saves | not ported: Field's completion, the KDoc on `ColorRpn` (which carries the language reference), documents + history |

## Shared code

- `afield/lang/rpn/ColorRpn.kt` — the language. Pinned by tests to the demo's semantics: wrap01
  (non-finite → 0.5), safe `/` (±1e-6 floor), empty-stack reads 0.5, unknown tokens skipped, an
  empty program is `0.5`, `perlin`/`fbm` seeds cycle 1–4 per use, and the final-stack recovery
  (one number → grey, two numbers → the blue/orange mix, two colours → their average, colour +
  number → 70/30). Coordinates as the demo's canvas: `y` grows downward, `r` is distance from
  centre over the half-diagonal (capped at 1), `a` is `atan2(dy, dx)/TAU` wrapped.
- `afield/lang/rpn/ColorRpnGenome.kt` — breeding, a `GenomeOps` so `PopulationSearch` can drive it
  with a computed objective too (a test does exactly that, toward a target picture).
- `afield/study/search/EyeEvolution.kt` — the interactive-evolution grid: N tiles (parents first,
  marked), click to toggle, `pick` selections breed the next generation through the genome's
  `GenomeOps.crossover` (or a mutation when it declines), an `onPick` callback into box code. The
  browser half posts JSON over the existing `string-update` control message (as
  `StackSLStudyViewer` does), so there is no new route or `/ws` command. Rendering is the
  caller's `picture: (G) -> ByteArray` (PNG) on a background thread.

## The starting point (`madp_ga`)

A lead box with starters A and B and the demo's three settings, then three boxes seeded from the
demo's LLM-intro examples:

- `x 8 * perlin dup invert 0.3 blend` with `r 12 * sin a 9 * cos + 0.6 0.5 hsl`,
- `x y + 7 * 2.2 fbm 0.65 0.55 hsl` with `x 6 * perlin y 9 * perlin + 0.5 * 0.7 0.55 hsl`,
- `x y 0.45 rgb normalize dup dot invert` with a breeding variant (numbers only, a slow mutation
  rate) to show where the rules live.

Note on the starters: the brief writes `x 9 * sin 0.18 + / y 0.55 + / 0.62 / hsl`; the `/` there
are the demo's line breaks (`DEFAULT_A` is four lines joined by `\n`), not divisions.

## Captures

Every box captured through the backdoor with `--with-playwright`, next to the demo at
`:9982/ga.html`. Selection in the captures is driven through the grid's own click path
(Playwright clicking two tiles) and through `evolution.pick(i, j)`; neither is a human click.
