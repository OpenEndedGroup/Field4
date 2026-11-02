# Make path-tracer temporalReuse work with (or warn about) a participating medium

- **Handoff:** `867170d5-cea3-442a-8ded-2498b98e58eb` (project)
- **Filed from:** https://openendedgroup.com/field4/path_tracing/
- **Filed by:** human, 2026-11-02 10:31
- **Close with:** `python tools/docs_notes.py handoff-close 867170d5 "what happened"`

public/docs/path_tracing.md ("The medium demo") carries this sharp edge:

> **A medium and `temporalReuse` are effectively exclusive.** A collision on the camera ray leaves the history guide's surface normal unset, so reprojection rejects that pixel, and a dense medium gets no temporal reuse for most of the frame. Raise `samplesPerFrame` instead of reaching for reuse.

Fix in code: either write a usable history guide for medium-collision pixels (so reprojection can accept them), or have the tracer warn once (box output) when `temporalReuse` is enabled with a nonzero `mediumDensity`. Delete the paragraph in the same change.
