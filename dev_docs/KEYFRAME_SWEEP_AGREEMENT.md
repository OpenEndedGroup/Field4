# What pins the keyframe sweeper, and where the two halves meet

`public/docs/keyframe_autosweep.md` is the user-facing page. This note is the argument
about *coverage* that used to sit at the end of it: which claim each suite pins, and the
gap between them that is the reason there are two.

## The CPU half pins the arithmetic, and only the arithmetic

`afield.stdlib.KeyframeSweepTest` recomputes `Σ wᵢ·vᵢ` from each manifest and requires it
to reproduce the `key.json` written beside it. That is the whole provenance contract: a
key on disk is a stated function of the participants and weights recorded next to it, and
nothing in between is allowed to have edited it. On top of that it pins the
reproducibility, the resume, the three lane policies, the failure isolation, the disk
floor and the sampling distribution.

**Every one of those assertions would still hold if applying a blend reached no uniform at
all.** The manifest would be correct, the resume would skip the right directories, the
distribution would be right, and every render would be the same picture. The CPU suite
cannot tell the difference, because it never looks at a pixel.

## The GPU half pins that the picture is a function of the blend

`tools/run_field_tests.py --id keyframe.sweep.smoke` runs a real six-render sweep and
requires pixel distance to correlate with key distance across every pair of renders, at
better than 0.6.

What it deliberately does **not** require is that consecutive renders differ. Two
independent draws can legitimately land next to each other in the space, and a test that
forbade that would be pinning the sampler's luck rather than the renderer's behaviour.

The same run re-applies a saved key and re-renders it. On the runs observed so far that
has reproduced the sweep's own still exactly, which is **more than the test asks for** —
it asserts only that the two are close, because the renderer carries a frame of temporal
history and an exact match is not something a frame-history renderer owes anyone.

## Open

- Nothing asserts that the exact re-render stays exact. If it stops being exact the smoke
  still passes, and the only record that it used to be exact is this paragraph. Either
  tighten the assertion or accept that this is an observation and not a guarantee.
- The 0.6 correlation floor was chosen to pass comfortably on the hardware to hand, not
  measured against a distribution of known-good and known-broken renderers.
