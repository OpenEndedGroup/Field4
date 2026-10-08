# Loris transforms: how they reach Loris

`LorisClient.distill/sift/dilate/morph` run the pinned Loris library's own Channelizer + Distiller,
Sieve, Dilator and Morpher inside the `field_loris` helper (`native/field-loris/main.cpp`,
protocol modes 2-5). Nothing is re-implemented in Kotlin except the plain envelope edits in
`src/jvmMain/kotlin/afield/stdlib/loris/LorisTransforms.kt`.

Facts worth knowing:

- After a transform the helper crops partials to the declared timeline and removes breakpoints
  outside (0, Nyquist), and clamps bandwidth to [0, 1], so results satisfy `LorisModel`'s checks.
  Analyze/synthesize output is unaffected (`conform` runs only in modes 2-5).
- Transform results get ids 0..n-1; only `synthesize` keeps caller ids.
- Distilling speech joins all same-label pieces across pauses; the viewer then draws long straight
  segments. That is Loris's behaviour, not a viewer bug.
- `LorisTransformTest` runs only when the helper is built (`./gradlew buildLorisNative`), like
  `LorisTest`; CI without the helper skips the native assertions.
- The helper was first built and tested on macOS (Apple silicon) on 2026-10-08. The published
  `provenance.json` was made by a different build (its executable hash differs), so re-rendering
  the docs on another machine changes that file.
- `LorisDocs.morph()` writes `public/docs/assets/loris/morph-a-to-b.wav` and
  `morph-provenance.json`. The two film ranges are called phrase A and B because the speakers in
  them were never identified by ear.
