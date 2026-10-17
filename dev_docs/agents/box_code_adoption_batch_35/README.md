# Close native paint and SolidSL owners on every exit

Baseline `a4344fc66`. Four frozen findings addressed:

- `170fca19e6cf-01`: all 26 copies of the shared paint-sweep column function close their canvas in finally, including failures during setup, painting, encoding or image conversion.
- `170fca19e6cf-02`: the scrub loop has an explicit block; actual formatter layout preserves the already-expanded angle loop and independent stroke calls. Sweep values and fragment boundaries stay unchanged.
- `1bc3ed402106-01`: the gesture comparison registers each allocated canvas before painting, then closes all registered canvases in finally. Earlier allocations are protected when later work fails.
- `ee7c97ec6dcf-01`: the vocabulary mesh helper, hybrid runner and short build/report illustration scope SolidBuild with use. Geometry is copied before closing the native owner.

Actual import/ktfmt formatting covers 26 paint states, 18 solid-notation states and both gesture transcript bodies. One collection constructor becomes a literal. The idiom/collection audit is clean across all three files. Reviewed tokens, surrounding prose/folds, graph properties and fragment boundaries are checked by verify.py; no GLSL is edited.

Real native verification:

- Check that every paint state uses the identical shared column function. Render the original and revised default column through the native paint engine. Both decoded 300x750 RGBA byte arrays are identical; both actual canvas closes return. The actual images are inspected.
- Inject a controlled failure after canvas allocation and before painting: the actual native close completes. The test records completed close calls, not a fabricated isClosed property.
- Run the real four-way gesture comparison with pressure, tilt and timing, inspect its output, and verify all four actual closes complete. Inject failure after the first allocation and verify that allocated canvas closes.
- Run the six-shape vocabulary and full hybrid-versus-marched example, inspect their real mesh viewers, and retain native Solid handles solely to assert isClosed after the authored scopes. A controlled conversion failure closes its retained real native Solid as well.

The supplied primary-checkout paint dylib loads but lacks field_paint_stroke_arc required by current Kotlin. A checkout-local buildPaintNative succeeds; its exported symbol is verified. A fresh private Field on 9703 loads that matching library through the documented override. No primary-checkout binary is changed. Initial mismatch and harness type-resolution diagnostics are retained with final successful evidence. Private captures are verification artifacts; no published pipeline capture is replaced.

The scoped transcript compiler also verifies the gesture graph. Reusable mechanical, preservation and native rehearsal harnesses, reviewed changes and evidence.tar.gz record exact coverage. This verifies a representative paint column and common cleanup across all copies, not every setting combination or every illustrative SolidSL fragment. The full corpus sweep remains incomplete.
