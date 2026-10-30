# Panel coherence audit (2026-10-30)

The standard is `FieldPanel` (`src/jsMain/kotlin/afield/app/client/fieldPanel.kt`). Its tokens and
close button are in `src/commonMain/resources/fieldPanelTokens.css`. `index.html` loads that file
before `field.css`, and the history and keyframe-browser iframes `@import` it, so there is only one
copy and no guard test is needed for drift.

Standard, measured with `getComputedStyle` in a 2200×2800 headless window:

| Part | Value |
| --- | --- |
| Shell | `#000`, 5px `--field-border` border, 5px radius, 8px padding, 10.2px Iosevka, no shadow |
| Head | min-height 28px; title 11px / 500; status 10.2px `#8a8a8a` |
| Close | `<button class="field-panel-icon">×</button>`, 26×26, 17px glyph, 1px `#2b2b2b` border, 6px radius, `#050505`; hover `#464646` / `#101010` / `#f0f0f0`; `title="Close"`, `aria-label="Close"` |
| Keys | Escape closes. Keys, mouse-downs and wheel events stay inside the panel. |

## A flaw in the standard itself

`.field-panel button` (specificity 0,1,1) outranked `.field-panel-icon` (0,1,0). Because of this,
every FieldPanel close (bookmarks, layers, macro recorder) drew its × at 10.2px, not 17px. History's
close showed the intended look. The close button now uses `.field-panel-icon.field-panel-icon`
(0,2,0), which also wins over a host panel's own `.x button` rule.

## Audit

The "before" columns are measured values. Open paths are the ones the capture probe used
(`.nightly-tmp/probe/specs.json`).

| # | Panel | Open path | Chrome before | Deviations before | Now |
| --- | --- | --- | --- | --- | --- |
| 1 | Bookmarks | `toggleBookmarksPanel()` (alt+b) | FieldPanel | × at 10.2px, no aria-label | standard |
| 2 | Layers | `toggleLayersPanel()` (alt+l) | FieldPanel | same as 1 | standard |
| 3 | Macro Recorder | `macroTranscriptPanel()` | FieldPanel + `.macro-recorder-*` layout | same as 1 | standard |
| 4 | Document history | `showDocumentHistory()` | iframe, own `--panel-*` vars; × 26×28, 17px | Escape did not close | imports tokens; × is `field-panel-icon` (26×26); Escape closes, except while a text field has focus or the screenshot preview is open |
| 5 | Screenshot preview (in history) | — | inside history.html | not opened in this sweep | unchanged; Escape still closes the preview first |
| 6 | Keyframe browser | `keyframeBrowser(dir)` box output | iframe, `--kb-*` copies of the panel values; title 11/500 | values were a hand copy | `--kb-*` read `--fp-*` (old values as fallbacks). Closing it is the output disclosure's job. |
| 7 | Keyframer overlays | — | SVG `.kf-overlay` | not a panel | left alone |
| 8 | History lens | editor command | `div.closeBox` in a Monaco view-zone margin | not a button; no Escape | left alone: it is an output/editor `closeBox` and belongs with that family (see below) |
| 9 | Chat lens | box output | box-output content | no close | left alone: box output, closed through its output panel |
| 10 | Document search | cmd/ctrl+F | own pill: 7px radius, rgba bg, shadow, 16px | close had no title/aria; 14px glyph; ID-scoped button style | close is `field-panel-icon` with title and aria. The search pill shell is kept: it is a find bar, not a panel |
| 11 | Document guide | `showDocumentGuide()` | 14px pad, 6px radius, 13px body, 15px/700 title; × 30×28, 22px, borrowed `macro-recorder-icon` | as listed | shell pad/radius/border from tokens; title 11/500; `field-panel-icon`. Reading text stays 13px |
| 12 | Profiling timeline | `showProfilingTimeline()` | 14px pad, 12px body, 14px/700 title; × 28×28, 20px, borrowed `macro-recorder-icon` | as listed | shell from tokens; title 11/500; `field-panel-icon`. Body stays 12px for its canvas layout |
| 13 | Trays | `FieldTrays.open(side, url)` | uppercase 10px title; capital "X" 19×15, 11px | no Escape | title 11/500 sentence case; × `field-panel-icon`; Escape closes (while focus is in the tray chrome, not inside its iframe) |
| 14 | Lazy output panel | `Outputs.kt` | output `closeBox` | output family | left alone |
| 15 | FLine editor overlay | `sliders.js` | output overlay | output family | left alone |
| 16 | Slideshow notes | slideshow step | close styled inline from Kotlin | not opened (needs a slideshow) | left alone, follow-up |
| 17 | Publish document | `fieldPublishDocument()` | 20px pad, 0 radius, 1px #555 border, shadow, 13px; text "Close" button | as listed | FieldPanel shell (`field-panel` classes): title, status, × |
| 18 | Publication capture | `publication-capture.js` | — | not opened | left alone |
| 19 | State tree browser | AI chat | text "Close" button | — | × `field-panel-icon` with title and aria; the UI regression clicks `×` |
| 20 | Geometry review inbox | three viewer | text "Close"; no Escape | — | × `field-panel-icon`; Escape closes outside text fields. On pages without `fieldPanelTokens.css` the × is unstyled |
| 21 | charGrid view | `chargrid_view.html` | iframe | not opened (needs a charGrid box) | left alone, follow-up: import tokens |
| 22 | Inspector | `inspector.js` | box output | output family | left alone |
| 23 | `cmd+t` palette | cmd+T | `.afield-palette-*`; uppercase 10px/300 label; no close | Escape closes | left alone: a palette with no close button is correct. The title rhyme is a follow-up |
| 24 | Doc notes + source viewer | docs iframe | uppercase `<strong>`, CSS in JS | not opened (docs overlay) | left alone, follow-up |
| 25 | AI4 chat panel, drawing toolbar | — | box output / vendored Excalidraw | out of scope | left alone |

**The `closeBox` family.** Output areas, the history lens and the lazy output panel use `closeBox
hoverable`, a small × in a narrow gutter. A 26px button does not fit there. Unifying them is a
separate design question.

## Close / Escape checks (after)

The probe opens each panel, clicks its close control, reopens it, focuses a control inside it and
dispatches Escape (`.nightly-tmp/probe/measure.js`).

| Panel | Click closes | Escape closes |
| --- | --- | --- |
| Bookmarks | pass | pass (one probe run in three missed it; a direct 4× retest and the final run passed) |
| Layers | pass | pass |
| Macro Recorder | pass | pass |
| History | pass | pass (failed before) |
| Document search | pass | pass |
| Document guide | pass | pass |
| Profiling timeline | pass | pass |
| Trays | pass | pass (failed before) |
| Publish document | pass | pass |
| Command palette | no close button | pass |
