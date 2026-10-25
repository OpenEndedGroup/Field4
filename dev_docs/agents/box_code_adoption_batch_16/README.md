# Controls that update their preview

Baseline `a3d8c0d37`. Five findings resolved across three source files. Gain uses typed properties,
and all five mixed controls refresh the preview through one updatePreview function. Wind moves a
circle, bloom changes its radius, trails changes its outline, and tint sets monochrome luminance
and alpha. The final initial refresh follows cached-control restoration. Initial wind is aligned
with the control's 0.05 step. The shared-timeline example names its envelope before plotting it.

One proposed simplification is rejected by execution evidence: bare named("Scale") selects a
script-value reference and cannot read the Box property runs. The explicit thisBox.named receiver
is retained and explained. The actual parent/reader macro then passes scale == 2.5 and ran == 1.

[Reviewed edits](changes.json), [source proof](verification.json), and [evidence](evidence.tar.gz)
retain the changes, actual browser interaction results, failed selector rehearsal, and corrected
results. The editor import pass and ktfmt cover fifty Kotlin states/bodies; the three-file idiom
and collection audit passes. Execution boundaries, raw-string content and non-code structure are
checked against the reviewed edits.

Browser edits go through the actual slider, vector value editor, checkbox, string and color input
handlers and WebSocket callbacks. Assertions verify position, radius, fill, outline and label;
the same edited state survives a full rerun. Gain follows browser edits and remains 0.9 after
rerunning syncToProperty. The gesture transcript's typed gain also reads 0.9 after its browser edit.
These are separate private-browser checks, not the user's Electron document.

The actual docs RunnerState regenerates only the mixed-controls screenshot using its original
page, fence options and block index. The resulting committed image was visually inspected. It
shows the real controls and solid monochrome preview with matching initial values. No capture
was fabricated or copied from a one-off test screenshot.
