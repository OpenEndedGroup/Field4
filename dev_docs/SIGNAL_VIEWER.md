# Field's time-axis viewer

Since 2026-09-23 Field has **one** interactive viewer for anything drawn against a single x/time
axis. The runtime is `src/commonMain/resources/signal-viewer.js`, drawn with Field's bundled
Plotly. The HTML shell is `TimeViewerShell` (`src/jvmMain/kotlin/afield/stdlib/TimeViewer.kt`).
Four public surfaces render through it, and their signatures did not change:

| caller | source | where the data comes from |
|---|---|---|
| `SignalViewer` / `Box.displaySignal` | `range` | `/signal-viewer/{id}`, evaluated per visible window on the server |
| `Fn.plot`, `Box.plot`, `plot(fn)`, `Fn.plotHtml` | `embedded` | samples inside the HTML; `Box.plot`/`Fn.plot` also register a live `range` source |
| `displayAudio`, `displayAudioSamples`, `displayAudioBytes`, `AudioVisualizer.render*Html` | `audio` | Field Audio's waveform endpoint, or a PCM16/WebAudio decode in the browser |
| `analysisVisualizationHtml`, `analysisPreviewVisualizationHtml`, the two dashboards | `analysis` | a Field Audio `AnalysisPreview`, embedded or fetched once |

The hand-written canvas renderers it replaced (the `Fn.plot` iframe, `AudioVisualizer`'s
runtime and the analysis view's canvas) were deleted in the same change.

## What every source shares

- One lane model: lanes stacked on a shared x axis. Each lane has a y range, optional
  fixed ticks (octave ticks for pitch lanes), and Plotly traces. A trace is a line, a
  filled min/max band, a dashed comparison, the certified band, a set of note bars, or
  beat markers.
- Chrome that matches Field's dark output style: a black panel and a rounded inner frame,
  monospace (Iosevka Web) axes with a 2px label gap, a status chip, and − + ↕ ↺ buttons.
  Below the frame are a readout line and an **overview strip**. The strip is always
  present. It is dimmed while the whole domain is visible and becomes active, with the
  window marked, once you zoom. Click or drag it to move the view.
- Gestures: wheel zooms x at the pointer; shift-wheel or a horizontal swipe pans;
  dragging the plot pans. Alt-wheel zooms y on lanes that allow it (function plots).
  Double-click, ↺ or Home shows everything. ↕ or `f` fits every lane's y range to the
  visible window. `+` `−` and the arrow keys work on the focused plot.
- The readout shows the nearest sample of every trace at the pointer (or at a
  synchronized cursor), `lo … hi` for envelope bins, and the notes sounding at that time.
- The time bus: `field-time-window`, `field-time-cursor`, `field-time-seek` and
  `field-time-transport` DOM events on the top window, keyed by `syncGroup`, with
  `syncOffset` mapping local to shared time. Any viewer in a group publishes its window
  and hover cursor. A click without a drag publishes a seek, and an audio viewer in the
  same group seeks its `<audio>`.
- The playhead and cursors are absolutely positioned DOM lines moved with `transform`.
  Playback never calls Plotly per frame; it relayouts only when the view pages to follow
  the playhead.
- `viewKey` keeps the visible window across output replacement (bounded to 64 keys per page).
- Orange zero axes (x = 0 and y = 0), drawn as shapes because Plotly hides a zeroline that
  falls on the range edge. Function plots always draw them. Signal lanes draw them only
  when 0 lies strictly inside the range. Audio lanes get a faint centre line instead.

## Sources

**range** (SignalViewer). The server evaluates each visible window and returns at most
4096 envelope bins or 8193 line samples per lane, with a 500,000-interval cap per
lane/query. The status chip reports the grid spacing (`Δt`). Zooming in evaluates new,
finer samples. Broad views preserve extrema **on that grid**; features between samples
can still be missed. The opt-in certified band (`SignalChannel.certified`, from the
interval branch) bounds what lies between samples. Evaluators must be pure and quick. With
`gaps = true` a non-finite value becomes a gap rather than an error; `SignalWindow.toJson()`
writes such bins as JSON `null`. `displaySignal` retains the source on its box and releases
the previous one for the same channel. After a Field restart the URL is gone and the
viewer reports it.

**embedded** (Fn.plot). `Fn.plotHtml` samples N points (default 1024) once and puts them
in the page. Non-finite values and throws become gaps. The page needs no server route, so
it draws the same in a docs capture, after a restart, or pasted elsewhere, provided the
host page has Plotly. Field's does and the docs captures run in Field. `Box.plot`,
`Fn.plot` and `plot(fn)` additionally keep a live gaps-mode `SignalViewer` for the same
function on the box. When you zoom inside the domain the chip changes from
`1024/1024 samples` to `live · Δx …` and the viewer resamples. If the source has expired
it falls back to the embedded samples and the chip says `source expired`. Bare `plotHtml`
zooms into its embedded samples only.

**audio** (AudioVisualizer). The source is `waveformUrl` (queried per window with
`start_seconds`, `duration_seconds` and `points`), then `inlineWaveformWavBase64`, then a
decode of the `audioUrl` itself. The strategies and their order are unchanged. One lane
per channel, ±1. Click to seek; zoom anchors at the playhead when it is visible. With
`followPlayhead` the view pages at 8% / 92%. `interactive = false` makes a static Plotly
figure with no buttons or strip. The root keeps the class `field-audio-visualizer` for
callers that style it.

**analysis**. The layout depends on the preview's `operation`:
- `analysis.beats`: onset, local tempo and tempo-confidence lanes, with gold beat lines.
- `analysis.pitch_mono`: pYIN pitch in MIDI with octave ticks, and voicing.
- `analysis.pitch_poly`: a Basic Pitch piano roll, activation and active-pitch lanes.
- `analysis.transcribe_music`: an instrument-coloured roll, active-note and onset lanes.

Piano-roll notes are one trace per colour/alpha bucket, drawn as thick line segments separated
by gaps. That is not a handful of SVG paths: Plotly splits a line at every gap, so large rolls
switch to WebGL (measurement below). The active-pitch count now has its own lane. The old canvas squeezed
it into the activation lane on a private scale.

## Kept separate, on purpose

- `showSignals` (`visualization/plotly.kt`) is a one-shot plotly-kt chart of in-memory
  lists with a legend. It is already Plotly, has no time window, source or bus, and 31
  pages depend on its look. Routing it through the viewer would buy nothing.
- `SpeechEmbeddingVisualization` joins the time bus, but its main view is a 2-D
  trajectory (MDS axis × axis), not a time axis. It stays a canvas.
- `CodecVisualization` draws spectrograms and token maps as JVM rasters (PNG), which is
  the house rule for dense images.
- `pianoRollSvg` (`four/synth/Sequencing.kt`) is a static, non-interactive `toHTML()`
  for pattern values. It has no time bus.

## Bins, readiness, and the piano roll

- Bins are requested per **device** pixel: (stage width − margins) × devicePixelRatio, with a cap
  of 4096. The width is measured from the stage at request time, not from Plotly's last layout.
  An output first laid out while closed has a 200 px layout, and trusting it once gave 296 bins
  for 1944 device px.
- Audio switches from a min/max contour to its waveform (a 1.3 px line through every sample) at
  ≤ 1.5 samples per device pixel, with dots past 6 px per sample. A service answer with min ==
  max everywhere is drawn the same way. `data-samples-per-pixel` records the ratio.
- `aria-busy` on the root is true until the current view's final-resolution draw. A closed
  output stays busy and does not fetch until it has width. Field's box-screenshot prep already
  blocks on a visible `[aria-busy="true"]`, so docs captures wait for it.
- Zero-axis shapes are emitted only inside the current view. A clipped shape far outside
  still has a bounding box, and box screenshots union the rects of every SVG node, which once
  produced seven 4000-px-tall captures.
- Lane labels sit in a 17 px band of headroom at the top of each labelled lane.
- Piano roll: Plotly splits an SVG line at every null, so N notes become N `<path>`s. Measured
  on 20,000 notes: 146–341 ms per pan in SVG against 5–12 ms as `scattergl` (80 paths). Rolls
  over 2,000 notes use WebGL. Every docs fixture (19–34 notes) stays SVG.

## Verification

- `TimeViewerTest`:
  - the embedded payload's gap set equals the set where the function is non-finite or throws;
  - gaps travel as `null`;
  - `Box.plot` registers and then releases its live source;
  - min/max bins keep a single-sample spike that decimating the same grid to one sample per
    bin drops (the counterfactual is asserted, not assumed);
  - every caller inlines the same runtime.
- Also green: `SignalViewerTest`, `AudioVisualizerTest`, `AnalysisVisualizationTest`,
  `AnalysisOperationsTest`, `OscillatorDomainGuardTest`, `FnInterval*`, `FunctionStudyTest`,
  `InfixCompletionTest`, `CodecVisualizationTest`, `RenderedAudio*`.
- Browser: `tools/test_signal_viewer.js` (page-eval) and `tools/test_signal_viewer_gestures.kt`
  run against a three-lane 0..3600 s SignalViewer. The gestures script uses real Playwright
  drag, wheel and overview-strip navigation.
- The demo in `public/docs/visualizers.md` ("One viewer for functions, sounds and signals")
  puts all three sources on one `syncGroup`.
- Type sizes (5da6efc2f): ticks and axis titles 9 px, lane labels 8 px, panel titles 11 px,
  chips, legend and footer 9 px; stacked lanes are 16 px apart. The 101 Fn.plot figures, the
  recorded-audio figure and the one-viewer demo were captured before this change and are owed a
  recapture. synchronized_analysis.png is current.

