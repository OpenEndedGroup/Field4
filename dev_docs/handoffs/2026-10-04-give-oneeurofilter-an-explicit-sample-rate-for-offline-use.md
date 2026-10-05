# Give OneEuroFilter an explicit sample rate for offline use

- **Handoff:** `8fadbecf-b705-43c4-8257-23e475710efc` (project)
- **Filed from:** https://openendedgroup.com/field4/spectral_examples/
- **Filed by:** human, 2026-10-04 06:10
- **Close with:** `python tools/docs_notes.py handoff-close 8fadbecf "what happened"`

The first example on spectral_examples.md ("Filter chains as layered curves") runs `OneEuroFilter` in a `for` loop over synthesised samples. `OneEuroFilter` takes its time step from `System.currentTimeMillis()` between calls, so offline it sees ~1 ms per sample instead of the intended 1/120 s and smooths far harder than `minCutoff = 1.0f` implies. The page now carries a warning explaining the orange trace.

Fix the code rather than the prose: give `OneEuroFilter` an optional explicit sample rate (or a `dt`/timestamp argument) so offline use means what the parameters say, update the fence to pass `sampleRate = 120f`, recapture `assets/timeseries_examples/filter_chains.png`, and drop the warning.
