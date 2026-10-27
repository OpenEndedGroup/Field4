# Tour docs audit (2026-10-27)

Claims and figures on `public/docs/funFeatures.md` ("A Tour"). Verdicts: **true (run)** means a
docs-runner capture in `FIELD_DOCS_RUNNER_MODE=always` produced it on port 9651 and the PNG was
opened and looked at; **true (code)** means read from source only; **unverified** means neither.

## Figures

| Was | Now | Verdict |
| --- | --- | --- |
| `screenshot_31` (`.h`) | `funfeatures/h-map.png`, runner capture | true (run): one-line map with sparkline |
| `screenshot_32` (`.h2`) | `funfeatures/h2-buffer.png`, runner capture of a 100 000-float `FloatBuffer` | true (run): paged table, stride 4 |
| `screenshot_33` (`display`) | `funfeatures/display-map.png` | true (run) |
| `screenshot_34` (display HTML) | `funfeatures/display-html.png` | true (run); panels appear newest-first |
| `screenshot_35` (`column`) | `funfeatures/column.png` | true (run) |
| `screenshot_36` (`tree`) | `funfeatures/tree.png` | true (run) |
| `screenshot_37` (`logs`) | `funfeatures/logs.png` | true (run); page said `logs('log')`, the API is `logs()` (channel defaults to "internal logging") — fixed |
| `screenshot_38` (alt-held ✕) | omitted, aside | UI state needing a held modifier; no capture hook holds one |
| `screenshot_40` (docs browser) | omitted, aside | capture would record whichever docs build the Field finds |
| `screenshot_42` (keyframes) | `funfeatures/keyframes.png`, box capture of the shown `keyframes(...)` call | true (run): two default keys at (0.2, 0.5), (0.5, 2.0) |
| `screenshot_45` (nested connections) | omitted, aside | needs a setup transcript with a nested+connected box |
| `screenshot_54` (Clojure) | `funfeatures/clojure-output.png`, same code as `clojure.md`'s output fence | true (run): inline chips and rainbow parens |

All twelve old PNGs were referenced only by this page and were removed. Existing runner captures
on the page (print-here, editable flower, layers panel, command palette/nesting/multi-editor
videos) were regenerated in the same run; stills were looked at, videos were not played.

## Prose claims

- Snippets used `[1, 2, 3, 4, 8, 7.2]` (a mixed Int/Double collection literal); replaced with
  `listOf(1.0, …)` so the inferred type is plain. **fixed**
- ".h2 … highlighted colors show unusually low and high values": not visible in the capture
  (smooth data, no tint). **unverified, removed**
- "This page is older than that machinery and many of its screenshots were taken by hand": **stale,
  rewritten** — every figure under code now comes from that code.
- Actors snippets were fenced `data-lang=python` but are Kotlin. **fixed**
- Python snippet uses `setPropertyRaw` (an escape hatch per house idiom). **unverified, left;
  should become a typed property access**
- Running/launching key bindings, spreadsheet, Forth/a-Nano, actors, time slider, marking menus,
  snapping, shared properties/`once`, referencing, collaborating, mobile: **unverified** this pass
  (videos are hand recordings; no runner capture was attempted). Next pass candidates.
- "Some systems, like the Vulkan graphics system, expose interface…" left untouched on purpose: a
  sibling branch is making it true; it should then link `visualizers.md`.

No runtime defects were found, so no src/ changes.
