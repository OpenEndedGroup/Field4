# Teaching comments, accurate controls and shared mechanical coverage

Eight frozen findings addressed: `02b566216ca7-03`, `7a5d00bb1ace-02`, `013ba1fa3c91-02`, `0977f890d8a9-01`, `2792e161f146-03`, `ded1d6a3cafb-02`, `153504d5e8c3-02`, `9be027ca01a6-01`.

- Template/reflection fragments say why declarations must run first; original execution markers remain unchanged.
- Interactive-guide prose names the state that callbacks need. The current example already has no fragment marker, so this correction adds explanation outside the code without restoring obsolete boundaries.
- Both evolution contact sheets explain that display samples at most five roughly evenly spaced record holders from the complete measured history.
- The fluid instructions use fluid.plan.report() and the actual sdf centre/radius/spacing setters. Resolution guidance now distinguishes halving-stride rounds from seed, JFA+1 and distance stages, following FabricatePassLibrary directly.
- Klecks documents method results as snapshots: repeat retrieval after Submit. The Csound excerpt returns dry/low samples; the full starting point computes and prints the RMS ratio.
- RTT shader finalColor.xy is documented as an object-space offset. Remote foo retains its JavaScript binding, with componentwise normal displacement and perspective point-size scale explained.

## Verification and coverage

Actual import/ktfmt checks 80 Kotlin states (76 page states plus four transcript bodies); 40 page states are formatted. Both shader sidecars use the actual GLSL formatter. No bodies skipped. Collection literals audited; no conversions needed. The remote JavaScript remains JavaScript and is not Kotlin-formatted.

verify_semantics.py compares baseline executable tokens after comments/imports/layout normalization, including string contents, Kotlin state counts, fragment markers, YAML metadata/operations and shader interfaces. All match. verify.py separately compares the approved edit with final formatter output, preserving raw strings and folds. Source contracts checked against Klecks accessors, actual jump-flood construction, remote foo binding and the Csound measured example.

No runtime/capture replay: this batch changes comments, prose, imports and layout only; algorithm, shader tokens, bindings and published outputs are preserved. Required full guards are left to the lander. This avoids duplicating successful behavior tests for unchanged code. Evidence contains formatter logs and explicit semantic verdicts; existing captures are retained.

The checkpoint is now 312/405 addressed, 93 open. Corpus-wide coverage is still incomplete. Batch45 is queued separately as 20261018-131653-codex-box-code-readability-45; it has not yet been reported landed.
