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

## What neither pins

- **the fill-frame population.** `h2-panel-sizing` exercises one fill frame; `output-area-basics`
  exercises none. The lazy-output frame and the value-viewer frame are fill frames by the same
  three-part test and are under no geometry assertion anywhere.
  `src/jvmTest/kotlin/afield/stdlib/KeyframeBrowserTest.kt:952#fun aNonPositiveWidthIsStillTheFillMyColumnEscapeHatch`
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
