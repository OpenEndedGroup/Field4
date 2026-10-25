# Rename Performance.reuse() so it does not read as output repetition

- **Handoff:** `e0d966d8-4723-467c-b29e-f9df5e80df96` (project)
- **Filed from:** https://openendedgroup.com/field4/temporal_memory/
- **Filed by:** human, 2026-10-20 11:09
- **Close with:** `python tools/docs_notes.py handoff-close e0d966d8 "what happened"`

`public/docs/temporal_memory.md` has to warn readers off a misleadingly named measure:

> - `outputRepetition()` is how much the output repeats *itself*: the share of two-second stretches
>   that closely match an earlier stretch. Use it rather than `reuse()`, which only counts memory positions.
>   Two copies of the same gesture in different places in memory are different positions but the
>   same output.

`Performance.reuse()` (`src/jvmMain/kotlin/afield/math/oracle/Improviser.kt`) is the fraction of output
samples whose *source position* was played within a window; its name reads as the output-repetition
measure. Fix in code: rename it to say what it counts (e.g. `memoryReplay()` or `sourceReuse()`), update
callers, the page's fences/prose (also the "Memory proposes" section that lists `Performance.reuse()`)
and the starting point, and in the same change delete the "Use it rather than `reuse()`..." sentence.
