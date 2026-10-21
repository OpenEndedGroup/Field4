# SolidSL measurements and deterministic native ownership

Baseline `4b64f20cf`. Five frozen findings addressed:

- `a387a211bbe3-01`: lower the field once before sampling. The cached field and original per-call lowering agree exactly at 36 points. The real hybrid/naive build and copied mesh viewer run.
- `a387a211bbe3-02`: both native demonstrations use Solid.scope with Unit results. The actual retained native handles are closed on success and on an injected exception before viewing, in both examples. Copied mesh arrays still display after closure. Cross-check reuses the fine build for its native viewer rather than allocating another solid.
- `a387a211bbe3-03`: captions say largest sampled surface deviation, in model units. They no longer claim an everywhere upper bound. Escaped HTML middots that rendered literally are replaced with actual punctuation.
- `a387a211bbe3-04`: exact/fallback labels follow isExact; unchanged volume/triangle count is reported separately. Both the original exact expression and an edited smoothUnion expression run the full authored comparison and display the appropriate native-view label. A separate exact/smooth build check verifies the boolean selector.
- `86c50b0f047a-01`: the inverse-fit target is labelled a reference; measured fit parameters/loss remain visible. The actual solve improves loss and both fitted/target viewers are inspected. No pixel-equality assertion remains.

The runtime also exposed the removed parallel parameter on Solid.levelSet; the example now uses the current deterministic sequential API. An initial rehearsal compilation failure is retained. Native scope behavior is verified with actual Manifold handles, not replacement implementations.

Actual imports/ktfmt cover three owned Kotlin sidecars, including two collection conversions, and the formatted inverse body is copied into its builder. The other two builder bodies are preserved exactly. Parsed token/container/fragment/raw-string checks pass. Both saved documents refresh inline fallbacks through Field's codec while preserving graph identity and every non-source property; only owned Markdown mirrors/digests change. The three Python builder tests pass.

The normal landing idiom audit is clean, as is the collection audit of the three owned sidecars. A broader collection audit records existing listOf calls in untouched neighboring builder/mirror bodies; those are not claimed as formatted coverage. This batch has no GLSL edits. Private actual viewer captures are inspected and retained as verification evidence, not published documentation replacements.

changes.json / verification.json retain the reviewed edits and source proof. mechanical.py / rehearse.py / refresh_saved.py reproduce the work. evidence.tar.gz retains exact native success/failure results, viewer captures, codec checks and builder/audit logs.
