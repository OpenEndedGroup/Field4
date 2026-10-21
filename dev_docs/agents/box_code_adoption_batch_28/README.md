# Use the shared spline and make paint-tool limits/effects explicit

Baseline `13f9020ce`. Three findings addressed in ai4_paint_studio:

- `75fde331e73a-01`: through validates complete coordinate pairs, keeps a straight segment for two open points, and delegates longer/closed paths to catmullRomToBezier. The shared helper explicitly closes closed paths. Six actual open/closed paths with two, three and four points compare against the old implementation at 256 samples each: maximum position difference 0.00023835 canvas units, below 0.001. Empty/one-point/odd-coordinate inputs reject rather than silently dropping a coordinate.
- `75fde331e73a-02`: scribble validates finite density within its documented 1..8 rows per 100 units before accessing the canvas or starting either loop. Registered-tool calls reject -1, 0, 0.5 and 9 with the density explanation; the real native paint tools successfully paint at both valid endpoints 1 and 8.
- `75fde331e73a-03`: paint.look declares FILESYSTEM_WRITE together with CAPTURE_IMAGE. Calling the actual registered tool adds one progress PNG; ImageIO decodes the real 900-pixel-wide image. The registry's actual metadata contains both effects. The native canvas and painted patch are captured through the private browser and inspected.

Actual imports/ktfmt cover both Kotlin bodies, with four collection conversions. Reviewed tokens, containers, raw strings and execution boundaries are preserved. The scoped idiom/collection audit and final transcript execution gate pass. No model/provider request is made; these are the same registered tools invoked through their normal argument binding and native implementation.

The comparison exposed the two-point special case: expressing that straight segment as a cubic changed resampling by 0.0098 units. Keeping the segment removes the difference rather than loosening the tolerance. Rehearsal errors are retained: the native override first preceded imports; runBlocking cannot drive these UI-owned tools; the overloaded start call was ambiguous; and stale earlier diagnostics poisoned a later wait. The final fresh rehearsal uses run-owned go coroutines and waits for explicit successful output channels. These harness mistakes did not change the authored tools' execution model.

`changes.json` and `verification.json` retain the reviewed draft and preservation proof. `mechanical.py`, `verify.py`, `rehearse.py` reproduce the work; `evidence.tar.gz` retains final results and failed rehearsal evidence. The broader corpus sweep is still in progress.
