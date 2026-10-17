# Drawing helpers and explicit path starts

Baseline `befabee7a`. Eleven findings addressed across nine authored files. Panels, swatches,
packing frames and square dots use `rect`; sampled open paths use `breakNext`, and the projected
helix uses `data("m l+", projected)`. The IK reticle requests exactly forty circle segments.

The actual editor import pass and ktfmt cover 80 Kotlin bodies/states, including 61 Markdown
states. Fourteen eligible collection factories become literals. Three direct `lines` calls use
the existing vararg overload: replay found the collection literal ambiguous between its list and
vararg overloads. The first two failed attempts are retained alongside the final all-green run.
The complete parser also covers quoted YAML `code` keys that the line-oriented idiom audit misses.

[Reviewed sources](changes.json), [mechanical proof](verification.json), [runtime harness](rehearse.py),
and [execution evidence](evidence.tar.gz) retain the exact edits and their verification.
Every parsed YAML property or complete Markdown state matches the reviewed edit plus collection
conversion and formatting. Execution boundaries, untrimmed raw strings, container metadata and
non-Kotlin properties are preserved. The explicit nine-file idiom audit passes.

## Verification

All four affected transcripts pass together through their real compile/replay classification:
sorting networks, drawn morph, autodiff worked example and FLine measurement.

Seven actual old/new helper pairs run in the private Field JVM: sorting/Boolean panels, Boolean
curve and markers, palette ring, morph dot and IK reticle. Coordinates agree within 0.0001 units;
color, thickness, fill and stroke agree. The dot and circle deliberately change an explicit final
line into Close, so the comparison resolves Close to the subpath start rather than claiming equal
node kinds. The helix with its frame vectors, swatch rows and spiral have identical exact node
fingerprints before and after. The forty-segment reticle remains visibly separate from its cross.

The actual notation-driven morph transcript also runs and produces its two rows of five clouds.
Captured reticle, helix, spiral and full morph were visually inspected. These are separate browser
captures from the private port 9700 session; the user's Electron document is untouched.
