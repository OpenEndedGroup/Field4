# Pixel evidence for the control-surface laws

Captured on port 9492 by replaying `dev_docs/agents/transcripts/control_surface_laws.yaml` through
`tools/field_backdoor.py --with-playwright` (`applied=7 skipped=0 errors=0`) and screenshotting each
box with `screenshot-box … --open-outputs`. These are here because "it compiles" is not verification
for anything rendered, and a claim about where a handle sits should be checkable without re-running
anything.

**`log_taper_bipolar_sliders.png`** — the whole argument in one frame. `CUTOFF` and `CUTOFF, LINEAR`
have the *same* 20 Hz–20 kHz range and the *same* 1000 Hz value; the log handle measures **56.7%** of
its track and the linear one **~4%**. The readouts differ too — `1000` against `1000.00` — which is the
adaptive significant-digit rule visible rather than described. `PAN` sits exactly at mid-travel reading
`0.000`, which is the only reason to have a bipolar law.

**`momentary_button_beside_checkbox.png`** — `GATE` renders as a bordered `HOLD` button next to a
`BYPASS` checkbox, so a gesture and a state are visibly different controls.

**`live_meters_linear_and_log.png`** — two bars carrying a live level: `0.05` on the linear meter (~5%
of the bar) and `0.05461` on the logarithmic 0.001–1.0 meter (**58%** of the bar, which is where a log
law puts it). A `TRIM` slider below them shows a thumb, so a meter is visibly not a slider. The
readings being non-zero is the end-to-end proof that the 30 Hz push reaches the *browser*, not merely
that it left the JVM.
