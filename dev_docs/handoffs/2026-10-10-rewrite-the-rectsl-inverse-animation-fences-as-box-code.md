# Rewrite the rectsl_inverse_animation fences as box code

- **Handoff:** `dd52549f-8828-448d-ab3c-2dacd4a55a19` (project)
- **Filed from:** https://openendedgroup.com/field4/rectsl_inverse_animation/
- **Filed by:** human, 2026-10-10 18:14
- **Close with:** `python tools/docs_notes.py handoff-close dd52549f "what happened"`

The prose of `public/docs/rectsl_inverse_animation.md` was reordered on 2026-10-10 (usage first, internals under "How the fit works"), but its five `diffstatic_runner` fences were left byte-identical and still break the box-code rules in `dev_docs/agents/DOCS_WRITING_RULES.md`:

- `import afield.math.functions.Functions` in four fences (the opening fit, the sin identity, the free-param scene, the exact step).
- Hand-built HTML (`<pre style=...>`, `<img src='data:...'>`) for output that is text or a single picture; use Markdown output / the image display helpers.
- Long comments that narrate the check ("This box is that claim, checked, rather than that claim, asserted", the 377-character "+277 chars" comment) — internal self-talk inside user code.
- The opening fence is ~80 lines of nested `BinaryDoubleTrack` constructors; consider folding the `channel(...)` builder and the `RectSLFitFigure.render` call with the fold facilities, or leading with the shorter RectSL-source `free-param` box.
- The `free-param` scene fence starts with `RectAnimationOracle.compile(...).programOrNull!!`; if there is a box-level way to compile a `#rectsl` string, use it.

Rewrite the fences, recapture (`FIELD_DOCS_RUNNER_MODE=always ... --page rectsl_inverse_animation.md`), update `tools/completion-corpus/rank-carets.tsv` if it pins these fences, and keep the transcript `dev_docs/agents/transcripts/rectsl_inverse_animation.yaml` in step.
