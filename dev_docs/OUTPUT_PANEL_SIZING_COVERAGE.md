# Output panel sizing: what is pinned, and what is not

Displaced from `public/docs/output_panel_sizing.md`, which is a page a reader arrives at because a
panel is the wrong size. This is the coverage census behind its one remaining paragraph.

`tools/run_ui_regressions.py` has the only executable checks on any of it, and they are
browser-driven: they build a real box, run real Kotlin, wait for the auto-size passes to settle,
and read geometry out of the live DOM. `dev_docs/VALUE_VISUALIZER.md:90` names `--id
h2-panel-sizing` as the list/geometry counterpart to its own cases.

## The two cases

`tools/run_ui_regressions.py:3651#id="h2-panel-sizing"` builds a box at `--w 1040 --h 200`,
displays 40 rows as a `.h2` value explorer, and asserts both axes of the resulting panel:

- `tools/run_ui_regressions.py:1045#result.panelTracksBox =` — the panel is within 24px of the
  box's width **and** does not overhang its right edge by more than 2px. Both halves matter: the
  first fails without `fillWidthForDisclosure`, the second fails if a hardcoded pixel width is used
  instead. It fails with `panel 200px does not track box 1040px`
  (`tools/run_ui_regressions.py:1058#does not track box`).
- `tools/run_ui_regressions.py:1049#result.frameKeepsDeclaredHeight =` — the frame still renders at
  the height it declared, which is the height-axis bug, and the case additionally asserts
  `summaryHeight > 0` so that the fixture cannot pass by having nothing above the frame.

`tools/run_ui_regressions.py:3640#id="output-area-basics"` covers the surrounding behaviour rather
than geometry: `display` creating and replacing channel content, embedded scripts executing, output
actually being visible.

## Size in both directions

`tools/run_ui_regressions.py#id="output-panel-shrink"` covers both axes over time rather than at one
moment. One panel is driven through a sequence of contents. Each step waits for the size to hold
for 1.6s, which covers the auto-size passes and the 0.5s shrink settle, then asserts:

- a StudyBuild-shaped card whose open failure `<details>` holds a `max-height:320px` `<pre>` with
  400 lines: the panel fits the card as laid out, not every line the `<pre>` scrolls through. This
  is the case that grew the "builds" panel to thousands of pixels;
- tall plain content grows the panel to fit, and short content afterwards brings it back down;
- `height:100%` fill content holds the tall height instead of collapsing to its one line;
- an unwrapped 1264px line widens the panel to show it, and short content afterwards gets exactly
  the width short content had before;
- a wrapped paragraph after wide content settles under a fresh panel's 480px and keeps the same
  width across three re-renders, so the width measurement does not creep;
- content alternating tall-and-wide/short every 60ms moves each axis down exactly once, after the
  alternation stops;
- 400 lines stop at `0.8 * innerHeight` and scroll inside the panel;
- under `scale(2)` and then `scale(0.5)` transforms (canvas zoom) the same short content keeps the
  unzoomed size;
- a panel sized by hand to 420x260 keeps exactly that through tall, wide and short content.

Each half has a negative control that fails it (2026-10-04): `main`'s `outputareas2.js` gives the
card a 16,196px panel; height grow-only keeps 1851px for 77px of content; width grow-only keeps
1272px for short content; measuring width at the panel's own width rather than at 480px gives the
same short content 200px or 302px depending on what came before; and without `layoutScale` a 2x
zoom takes the panel to 38,148px wide, then 4,882,436px. `main` does the same at 2x: one content
change took a 302px panel to 38,148px, because full-width blocks were counted at their zoomed size.

## What neither pins

- **the fill-frame population.** `h2-panel-sizing` exercises one fill frame; `output-area-basics`
  exercises none. The lazy-output frame and the value-viewer frame are fill frames by the same
  three-part test and are under no geometry assertion anywhere.
  `src/jvmTest/kotlin/afield/animation/keyframes/KeyframeBrowserTest.kt:952#fun aNonPositiveWidthIsStillTheFillMyColumnEscapeHatch`
  pins `keyframeBrowser`'s *markup*, that a non-positive width still spells `100%`, without
  standing up a browser, so it says nothing about what a panel then does with it.
- **the screenshot-prep loop.** The regression cases photograph a box but assert nothing about the
  crop, so a feedback loop between the prep pass and the auto-sizer would show up as a figure
  someone notices, not as a red build.
- **agreement between the two `isFillWidthIframe` implementations.** Nothing compares them. They
  live in different files in different languages, one of them a browser resource and one of them
  compiled, and drifting is a matter of one edit to either.
- **the widths in the published figure.** They are pixels in a PNG. The assertions are in the
  regression case, not on the page.

## Open

- The two `isFillWidthIframe` implementations are the cheapest real gap here: a test that feeds the
  same markup to both and requires the same verdict needs no browser, and would convert a silent
  drift into a red build.
- No case covers the lazy-output or value-viewer fill frames in a browser. `h2-panel-sizing` is the
  template; a second case over those two frames would close the population gap.
