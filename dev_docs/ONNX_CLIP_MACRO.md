# The `clip()` macro that was built, and then removed

A decision record, kept so the idea does not get re-attempted. The user-facing conclusion — encode
prompts once into a `val` — lives on `public/docs/onnx.md`; the reasoning is here.

## What it was

`afield.onnx` shipped in commit `a647ccbef` with a fourth piece: a `clip("…")` macro. A CLIP prompt in
source is a constant, so the macro encoded it at *macro-expansion* time, wrote the 512 floats into the
box's attic as base64 keyed by `hash(model, prompt)`, and replaced the call site with a reference. The
242 MB text encoder would then never load at run time — not in that session, and not in any later one,
because the attic is saved with the document. It was the same shape as the `ai()` macro's attic cache,
with a distinct `@clip-gen` marker so the base64 was not fed to a language model as context.

Commit `9d611b4cb`, the next day, **reverted the whole layer**: the macro, `ClipBakeTool`, the
pre-baked attic in the shipped transcript, the macro half of `ClipSmokeTest`, and
`MacroRegistry.isPreviewExpansion` along with the wrappers it had required around `applyMacros` in
*both* REPL engines.

## Why it went

- **Its stated justification was circular.** The claim was that baking is what let CI compile that box
  without the network. But CI only reached for the text encoder *because* `clip()` ran during macro
  expansion, and macro expansion is part of compilation. With prompts resolved at run time,
  `clipEncode("storm")` is an ordinary call: `COMPILE_ONLY` compiles it and never evaluates it, the
  session is `by lazy`, and the 242 MB is never opened. Baking created the problem that pre-baking
  then solved.
- **What was left after that was about 5 ms a prompt** — hoistable into a `val`, and never in the
  per-frame path to begin with — plus a reproducibility property nobody had asked for.
- **Against which it cost** a macro, an attic block format, an orphaned-block wart, and a change to
  *shared* macro infrastructure. That last one is the real price: `MacroRegistry.rewrite` runs during
  preview expansion, which fires as the caret moves over half-typed source, so without a guard a
  keystroke could bake `clip("s")`, `clip("st")`, `clip("sto")` — and could trigger a 242 MB download.
  A feature that needs the macro system to grow a new safety concept in order to be safe is a feature
  asking an expensive question.

## What survived

Everything that had been *measured*: the session cache, the worker thread, the modality-gap-aware API,
and `WebcamTexture.withLatestFrame`. `ClipVector.toBase64`/`fromBase64` stayed too — fifteen lines of
pure function with an obvious use beyond the macro. The shipped transcript now reads
`val prompts = mapOf("…" to clipEncode("…"), …)`, which is one line longer and needs no explanation at
all.

Encoding prompts once into a `val` is the whole technique. There is nothing cleverer to do.
