# Make springAngle return a continuous angle instead of jumping by 2π

- **Handoff:** `fc5115e4-81b0-46bc-b5f3-6706377cb5e4` (project)
- **Filed from:** https://openendedgroup.com/field4/movement_examples/
- **Filed by:** human, 2026-10-15 13:43
- **Close with:** `python tools/docs_notes.py handoff-close fc5115e4 "what happened"`

`public/docs/movement_examples.md` carries this warning, which exists only because of how `Springs.springAngle` (`src/jvmMain/kotlin/afield/animation/motion/movement.kt`) reports its result:

> !!! warning "`springAngle` reports its answer near the goal"
>     The returned number can jump by 2π on the step where the short way crosses ±π, even though the angle does not. Feed it to `cos`/`sin` or a rotation and that is invisible. Compare it against a previous value, as the plot above has to, and take the difference through `wrapAngle` first.

Fix in code: make `springAngle` / `doubleSpringAngle` return a value continuous with the input angle (e.g. `x + wrapAngle(result - x)`, i.e. no 2π step between consecutive calls), or offer an explicitly continuous variant and document the wrapped one by name. Add a `MovementTest` case that steps across ±π and asserts no 2π jump between consecutive outputs. Check the `movement-angle` fence on the page (it unwraps through `wrapAngle`) still reads correctly; recapture if its numbers move.

Delete the warning paragraph from the page in the same change.
