# Make the displayed mathematics match the lesson

Baseline `6ef67f560`. Four findings addressed: the canonical/doc wrap shader divides as floats;
the streaming chirp integrates its frequency into phase; rolling xi uses its own Gaussian-null
reference instead of the dCor cutoff. The wrap repair also updates the other two canonical GLSL
copies; the corresponding Slang helper already used float division. Statistics prose distinguishes
pointwise reference levels from change alarms and removes the unsupported latency-winner claim.
Underfilled rolling windows are omitted until the calibrated 80-sample window exists.

Actual imports/ktfmt cover 46 Kotlin bodies/states; the actual GLSL formatter covers 35 shader
properties/states. Reviewed tokens, containers, raw strings, comments and fragment boundaries are
checked. The selected idiom/collection audit is clean. The canonical document's fallback strings
are refreshed through Field's codec with graph identity and non-source properties checked.

The real revised wrap helper executes on the GPU for 33 indices and bases 3, 5, 11: all 99 channel
values match the float reference within 1e-6, including exact quarter steps. A first test-fixture
buffer member named values conflicted with the generated Structure type; renaming that fixture
member to a repaired the rehearsal. The authored shader members did not have this collision.

The actual streaming-feature lesson runs; after removing its known steady tone/onsets, independent
FFT windows find chirp peaks at 312.5 and 1882.8125 Hz (both within 35 Hz of the intended local
frequency). The actual rolling-statistics lesson preserves the recorded before/after means and
agrees with the batch result within 1e-10. Its null cutoffs are dCor 0.2623222 and xi 0.1312705.
On 200 fresh independent null windows they are exceeded 8 and 5 times respectively. This is finite
validation of the pointwise references, not a familywise significance or latency claim. The seeded
recording's first crossings are +23 and +40 samples; an edited recording with no crossing is safe.
The first 79 samples are verified absent from the plotted full-window statistics.

Four affected assets are forced through the real docs runner: two native triangle captures, the
streaming-feature plot and the rolling-statistics plot. The last is refreshed once more after the
warm-up fix. All captures pass without cache reuse and are inspected. Alt text reflects the new
colors/features. Exact sources, numerical verdicts and capture records are in `evidence.tar.gz`;
`changes.json` and `verification.json` retain the reviewed edits and source-preservation proof.
