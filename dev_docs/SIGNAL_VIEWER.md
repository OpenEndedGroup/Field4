# Interactive signal viewer

`SignalViewer` is a reusable Plotly box-output viewer for pure functions of time. It uses
Field's bundled Plotly, SVG line traces and continuous filled extrema bands. It adopts
the recorded-audio viewer's wheel/drag interaction and `field-time-window` /
`field-time-cursor` synchronization protocol. It does not require an audio file.

```kotlin
import afield.stdlib.*
import kotlin.math.*

val channels = listOf("x", "y", "z").mapIndexed { index, name ->
    SignalChannel(name, { t ->
        0.5 + 0.5 * sin(2.0 * PI * 0.2 * t + index * sin(t / 180.0))
    })
}
displaySignal(SignalViewer(channels, 0.0, 3600.0),
    options = SignalViewerOptions(label = "Drifting phases", viewKey = "my-signal"))
```

For an existing `Functions.Fn`, use `SignalChannel("x", { t -> f(t) })`.
Channels have independent `minimum` and `maximum` display bounds (default 0..1).
An optional `comparison` evaluator draws a dashed grey trace in the same lane;
`comparisonLabel` identifies it. Bounds are display ranges, not assertions or
normalization of the function.

Controls:

- Wheel or trackpad pinch zooms around the pointer; +/− buttons zoom at the center.
- Drag the plot or shift-wheel to pan horizontally.
- The strip beneath the ruler represents the whole domain; click/drag it to move the window.
- Double-click, **all**, or Home resets the view. Focus the plot to use +/− and arrow keys.
- Hover shows approximate sampled values locally, or min/max bins when zoomed out.

The server evaluates each visible window and returns at most 4096 envelope bins
or 8193 line samples per lane. The default overview grid is at most 20 ms, with a
500,000-interval cap per lane/query. The footer reports the actual grid spacing.
Zooming in evaluates new, finer samples. Broad views preserve extrema **on that
grid**; features between samples can still be missed. Evaluators must be pure,
finite-valued and quick to return. Sampling is cancellable between batches;
arbitrary blocking user Kotlin cannot be forcibly interrupted safely.

`displaySignal` retains the source on its box, and releases the previous source
for the same output channel. Direct `viewer.renderHtml()` users must retain the
viewer themselves and call `close()` when done. URLs are process-local; after a
Field restart, rerun the box. Expired sources show an explicit error.

Use a document-specific `viewKey` to retain the visible interval across output
replacement (for example, a new function revision). This state is bounded and
page-local. `syncGroup` optionally links the window and hover cursor with other
signal viewers and `AudioVisualizer`; both use seconds.

The function-conversation demo uses an `overview` output that follows the active
revision after accepted edits and observations. Inspecting older revisions puts
their viewer in `historical observation` without changing the current overview.
Old static `detail`, `comparison` and `signal` outputs are cleared on tools startup.
AI4 observations continue to include PNGs and
numeric checks of immutable revisions. The interactive human view does not
change the agents' evidence or the authoritative Kotlin source.

Verification: `SignalViewerTest`, `AudioVisualizerTest`, `FunctionStudyTest`;
browser regression `tools/test_signal_viewer.js` via the backdoor, after opening
the output with `screenshot-window --uid <box> --open-outputs`. The browser check
exercises pointer-anchored zoom, panning, finer evaluation, clamping and stale
response rejection against the real sampling endpoint.
`tools/test_signal_viewer_gestures.kt` follows it with real Playwright mouse drag,
wheel and whole-domain navigation checks; execute it on a private scratch property
with the backdoor's `run --property _gestureTest --file ...`.
