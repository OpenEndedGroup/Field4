# Docs and starting-place catch-up — 27 October 2026

Compared authored docs, transcripts and templates with the previous catch-up landing
`5b9e36542a`; froze the current baseline at `14c14fbad63e3a1967c21995c42b05070c1b0cf9`.
144 candidate paths yielded 456 changed body occurrences across 96 paths.
All occurrences were reviewed, sharing review for exact duplicates.

## Changes

- Actual editor import analysis, ktfmt and GLSL formatting: 424 eligible spans,
  389 distinct bodies. The initial pass changed 172 bodies; a second pass formatted
  20 bodies after readability edits. The final demo shader was formatted separately.
- 73 collection factories converted to house-style literals; nine pure fixed-decimal
  calls use numeric helpers. Typed, empty and spread factories retain their intent.
- Pen and temporal-trail constructors use `fline` and `breakNext`, reducing repeated
  first-point branching. No additional helper API was necessary.
- Compiled kit lifecycle code uses a typed property delegate instead of the raw
  property backdoor. Its real compiled imports are retained.
- Shader falling masks use increasing smoothstep edges and explicit inversion;
  the demo antialiasing width stays positive. Previous reversed edges had undefined
  GLSL behavior, so these changes preserve the intended mask rather than claiming
  defined equivalence to the old expression.
- Presentation metadata was refreshed through Field's normal save codec and every
  inline file was compared with its sidecar.

## Evidence and limits

`scope.json` freezes the inventory; `checkpoint.json` records completion state.
The formatter receipts check tokens, raw strings, folds and fragment markers.
`revision-verification.json` counts complete source changes against the frozen HEAD.
`runtime-verification.json` records six real Field rehearsals, including exact
before/after geometry comparisons, nested collection output, compiled kit delegate
roundtrip, and normal template metadata synchronization.
`shader-verification.json` records 11 real standalone shader compilations and the
five existing shader test suites that passed with their normal includes/preambles.
`review-verification.json` records final source hashes and the later shader change.
The final collection idiom audit and `git diff --check` passed.

No native GPU rendering or new visual capture is claimed. Intentional compiler-error
examples, browser-language bodies, raw literals and live fragments retain their
teaching semantics. Generated docs and transcript mirrors remain lander-owned.

The scripts are reusable within this frozen session; inventory rebuilding is an
explicit operation that replaces its checkpoint. Runtime fixtures and detailed logs
live in ignored `build/box-code-revision/catchup-20261027/`.

Landing precheck passed in 2m 13s: selected transcript compilation/execution, box
idioms and DocumentedApiEvaluationTest. Rehearsal and formatter scripts read the
frozen scope HEAD, so committing the catch-up does not change their before-source.
