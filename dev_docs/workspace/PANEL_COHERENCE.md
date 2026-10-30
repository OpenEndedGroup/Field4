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
| 17 | Publish document | `fieldPublishDocument()` | 20px pad, 0 radius, 1px #555 border, shadow, 13px; text "Close" button | as listed | left alone. `publishDocument.js` feeds `docs_publications.capture_digest`, so any edit makes all 181 publications stale. Moving the shell into a FieldPanel first needs it moved out of the digested file |
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

## Second pass (2026-10-30)

- **Keyframe browser, charGrid:** `fieldPanelHost.js` (iframe side) closes the output disclosure
  holding the iframe through the host's `__disclosureAPI.close(id)`, for a `[data-field-panel-close]`
  button and for Escape outside text fields. The keyframe browser has the shared ×; charGrid has
  no chrome of its own, so it gets Escape and the tokens.
- **Publish document:** the panel moved to `publishPanel.js` (loaded after `publishDocument.js`,
  replacing its `window.fieldPublishDocument`). `docs_publications.capture_digest` hashes
  `publication-capture.js`, `publication-three.js`, `publishDocument.js`, `threejs_*.{html,js}` and
  `WebPublication.kt`; `publishPanel.js` is not hashed and should not be — it is chrome, not
  output. `publishDocument.js` still holds its old panel function, overridden; delete it at the
  next deliberate publication refresh.
- **Command palette:** shell, label (sentence case, 11/500), colours from the tokens. No close
  button; Escape as before.
- **closeBox family** (inline output areas, history lens): a `<button>` with `title`/`aria-label`
  "Close", shared colours and hover at gutter size (`button.closeBox` in the tokens file). The
  history lens closes on Escape when focus is in it. The FLine editor overlay has no close control.
- **Slideshow notes:** shared × (positioned only); Escape closes when focus is in the note.
- **Docs source viewer:** tokens and the shared ×; the doc-notes panel keeps its "DOC NOTES"
  header because three published pages quote it.
- **History screenshot preview:** shared × and title tokens.
- **Publication capture:** no UI of its own (it is the capture code); nothing to change.
- **Document search:** black FieldPanel bar at the main pane's bottom-left (`positionSearch`,
  from `CanvasCamera.main.rect`, lifted above history / macro recorder / layers when they are
  docked there). Before, it sat at (1766, 14), on top of Monaco's Find at (1754, 17). Input in the
  palette's font and token colours, focus ring from the tokens; ↑ ↓ × are one row of identical
  26px icon buttons labelled "Previous match (Shift+Enter)", "Next match (Enter)", "Close"; the
  counter is status-line text: blank, "N matches", "i of N · property", or "No matches" (red, with
  the field outlined and ↑/↓ disabled). Behaviour kept: case-insensitive substring over the box
  name and code/shader/metadata properties, `.search-hit` and a badge on matching boxes, Enter /
  Shift+Enter cycle, the match is selected and revealed in the editor and the box scrolled into
  view. Cmd/Ctrl+F with the canvas focused opens it; with the editor focused it opens Monaco's
  Find instead (verified: search hidden, `.find-widget.visible`).
- **Bug found:** the first pass's history Escape fired from history's *parent-window* listener
  (kept there for the arrow keys), so with history loaded any Escape closed history and stopped
  the event. That was the intermittent Bookmarks Escape and the slideshow-note failure. Escape now
  closes history only for keys aimed at the history document. When the screenshot preview is
  open, Escape anywhere still closes the preview first (as on main).

## Close / Escape checks (final)

Five full probe runs after the last change, plus five charGrid runs with its box running.

| Panel | Click closes | Escape closes |
| --- | --- | --- |
| Bookmarks | 5/5 | 5/5 |
| Layers | 5/5 | 5/5 |
| Macro Recorder | 5/5 | 5/5 |
| History | 5/5 | 5/5 |
| Screenshot preview | pass (closes the preview, history stays) | pass |
| Keyframe browser | 5/5 | 5/5 |
| Document search | 5/5 | 5/5 |
| Document guide | 5/5 | 5/5 |
| Profiling timeline | 5/5 | 5/5 |
| Trays | 5/5 | 5/5 |
| Slideshow note | pass (2 runs) | pass (2 runs, after fixing the probe's wait) |
| Publish document | 5/5 | 5/5 |
| charGrid | no close button | 5/5 |
| Command palette | no close button | 5/5 |
