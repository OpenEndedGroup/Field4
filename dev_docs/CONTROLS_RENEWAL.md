# Controls renewal (2026-10-29)

A hands-on audit of the `controlGroup` controls DSL (`afield/ui/output/outputs.kt` →
`src/commonMain/resources/sliders.js` → the `*-update` handlers in `Server.kt`), driven through the
backdoor with Playwright `page-eval` and box screenshots, and what was fixed.

## Audit (observed)

| Control | Observed before | Now |
| --- | --- | --- |
| `slider` | Already had shift fine drag, double-click reset, arrow/Page/Home/End keys, typed entry, laws. Wheel did nothing. A drag whose control was re-rendered mid-gesture moved a detached node. | Wheel steps a *focused* slider (unfocused keeps canvas scroll). Drag re-finds its control by id. |
| `vec2Control` | `tabIndex -1`; no keys, no reset (double-click left the value), no fine drag. SVG angle label read `-135°` beside a `θ 135°` readout. A press on a collapsed pad sent `(-1, 1)`. A shift-free 10-move drag reached Kotlin as `0.030 0.030 0.010 0.020 0.010 0.040 0.060 0.050 0.020 0.050` — out of order, ending on a stale value. | Focusable; arrows (shift ×10), Backspace/double-click reset to `initialValue`, Enter types `(x, y)`; shift-drag relative ×0.1; collapsed pads ignore presses; label sign fixed; updates arrive in order. |
| `checkbox`, `stringInput`, `colorPicker`, sequencer `array-update` controls | Same unordered dispatch as vec2 on the server (`launch` per message). Interaction otherwise sound. | `/ws` serializes every control `*-update` (`ORDERED_CONTROL_MESSAGES` in `Server.kt`); callbacks start `UNDISPATCHED`. |
| KeyframeScope | `keyframeSlider` / vec2 / string bind through `ControlValueHandle.set`; untouched. | — |

The ordering defect is the significant one: `/ws` dispatched every message in its own coroutine
and only `slider-update`/`button-update` went through the ordered channel, so the stored control
value (echoed to other pages) and the callback could both end on an earlier drag value.

## Regressions

`tools/run_ui_regressions.py --id controls-vec2-keys --id controls-vec2-fine --id controls-slider-wheel-rerun`
(sources `tools/ui_regressions/controls-*`). `controls-vec2-fine` lists every callback value and
fails on any decrease, so it is the ordering guard.

## Not done / next

- Alt-arrow ÷10 on a stepped slider snaps back to the same value (existing behaviour, kept).
- The vec2 angle and |v| SVG labels overlap the handle near the centre.
- No undo/history integration for control moves.
