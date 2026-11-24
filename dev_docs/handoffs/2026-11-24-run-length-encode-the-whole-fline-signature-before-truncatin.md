# Run-length encode the whole FLine signature before truncating

- **Handoff:** `82ecaa69-99b1-449f-8cbd-cf4c7f07eaa1` (project)
- **Filed from:** https://openendedgroup.com/field4/visualizers/
- **Filed by:** agent:claude, 2026-11-24 19:19
- **Close with:** `python tools/docs_notes.py handoff-close 82ecaa69 "what happened"`

## The notes that led here

- `402cc351` (public/docs/visualizers.md:133): why is M(Lx41) ... (Lx22) the compressed signature? surely it should just be M(Lx140) ?
  > M(Lx41) … (Lx22)

Asked (note 402cc351, visualizers.md "An `FLine`, drawn"): a 141-node polyline shows the signature `M(Lx41) … (Lx22)`; the human expects `M(Lx140)`.

Why it is a code change: `ValueVisualizer.formatFLine` (src/jvmMain/kotlin/afield/ui/visualization/ValueVisualizer.kt, `shownSignature`) truncates the one-letter-per-node string to its first `FLINE_SIGNATURE_HEAD` (42) and last `FLINE_SIGNATURE_TAIL` (22) letters once it passes `FLINE_SIGNATURE_FULL_MAX_NODES` (80), and only then calls `compressSignatureRuns`. The truncation splits one run in two.

Proposed approach: run-length encode the whole signature first (it is already built only under `FLINE_SIGNATURE_PREVIEW_MAX_NODES`), then truncate by *tokens* (`M`, `(Lx140)`, ...) rather than letters, so `M(Lx140)` shows whole and long mixed paths still get head … tail. Alternative: keep letter truncation but merge the run that straddles the cut — more fiddly, same result.

Questions: should the elision count tokens or rendered characters? Is there a test pinning the current string (check ValueVisualizer tests and ValueVisualizerDocCaptureTest)?

Page afterwards: visualizers.md now explains the head/tail split (sentence after the header description); replace it with the new signature and recapture the FLine figure.
