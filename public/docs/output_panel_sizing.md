# How an output panel decides its size

*The width axis, seen from the `three` caller's side, is
[Field's 3D output](three_output.md#which-renderer-draws-this) — one paragraph on why a pixel width
is not a style choice. This page is the whole contract, both axes, from the panel's side.*

An output panel is the grey disclosure a box grows when something calls `display`. Closed, it is
exactly 200 pixels wide (`src/commonMain/resources/outputareas2.js:5#const CLOSED_W`). Open, it is
whatever size `contentSizeForDisclosure` decides the content wants, re-measured over seven passes
(`src/commonMain/resources/outputareas2.js:17#const AUTO_SIZE_PASSES`) as fonts load and iframes
boot.

Panels come out the wrong size when an author declares a size in a vocabulary the panel cannot
read, leaving it to size itself from whatever is left. Nothing throws, nothing is logged, and the
result is not obviously wrong: it is a plausible size arrived at from no evidence. The symptoms
point at the content rather than at the measurement, so this page starts from the symptoms.

## If you arrived here from a symptom

| what you are seeing | what it is | where |
|---|---|---|
| a panel opens at 200px and never grows, with a small frame in the corner of it | your iframe declares a percentage width, and something suppressed the other measurement source | [no measurement source at all](#a-frame-with-nothing-to-report) |
| a panel is short by the height of whatever sits above its frame | a frame's own height is being read as the whole content height | [height is measured from the content edge](#height-is-measured-from-the-content-edge) |
| a docs figure is far wider than its box, or its right-hand column is cropped off | the screenshot-prep pass and the auto-sizer are chasing each other | [the screenshot pass is a second sizer](#the-screenshot-pass-is-a-second-sizer) |
| a panel ignores the size the content asked for | somebody dragged that panel once | [a dragged panel stops listening](#a-dragged-panel-stops-listening) |

## Where a width can come from

`contentSizeForDisclosure` (`src/commonMain/resources/outputareas2.js:361#function contentSizeForDisclosure`)
walks every visible node inside the panel and collects candidate widths. They are candidates, not a
priority chain: the last line of the function is a `Math.max` over the largest node width, the
header, and the fill-width fallback
(`src/commonMain/resources/outputareas2.js:454#width + paddingX, headerWidth(d), fillWidthForDisclosure(d)`).
Whichever source shouts loudest wins, and a source that has nothing to say contributes zero.

For an iframe, `iframeIntrinsicSize`
(`src/commonMain/resources/outputareas2.js:308#function iframeIntrinsicSize`) has four places to
look and takes the max of them:

- the `width` **attribute** on the tag;
- the inline `style.width`;
- `data-afield-intrinsic-width`, which the frame's own copy of `iframe_autosize.js` wrote after
  measuring its document (`src/commonMain/resources/iframe_autosize.js:71#frame.dataset.afieldIntrinsicWidth = String(width)`);
- and, only if all three came back non-positive, the frame's own `getBoundingClientRect().width`.

The first two go through `parsePixels`
(`src/commonMain/resources/outputareas2.js:34#function parsePixels`), which is `parseFloat` and
nothing else, and this is where a percentage width goes wrong.

Be exact about *how*, because it is easy to assume a percentage parses as zero. It does not.
`parseFloat("100%")` is **100**, so a `width:100%` frame reports an intrinsic width of one hundred
pixels: a number, positive, and meaningless.

There are two consequences, and a repair aimed at one does nothing about the other.

The 100 loses the final `Math.max` to the header's floor. `headerWidth`
(`src/commonMain/resources/outputareas2.js:48#function headerWidth`) never returns less than
`CLOSED_W`, so 100 pixels of claimed width produces a 200-pixel panel. This is why the symptom
*looks* like "no intrinsic width" — the observable outcome is identical.

And because 100 is positive, the bounding-rect fallback never runs at all. `iframeIntrinsicSize`
guards it with `if (width <= 0)`. So for a `width:100%` frame, the panel does not measure itself; it
measures a frame that told it 100. Measuring itself is a real failure mode, but it belongs to a
different frame shape — one that declares *no* inline width, where the fallback does run and
`.disclosure-content iframe { width: 100% !important }` in `field.css` has already stretched the
frame to the panel's own width. The frames in the tree that take that path are named
[below](#four-things-in-the-tree-are-fill-frames).

`src/jvmMain/kotlin/afield/graphics/Three.kt:384#val sizing = if (width != null` is where a caller's
pixel width beats a percentage, for the same reason and with the same effect.

## A frame with nothing to report

`data-afield-autosize="off"` on an iframe makes the copy of `iframe_autosize.js` inside that frame
return on its sixth line
(`src/commonMain/resources/iframe_autosize.js:6#toLowerCase() === "off") return`), before it
registers any listener. No `afield-iframe-size` event is ever dispatched, so the parent's handler
(`src/commonMain/resources/outputareas2.js:560#addEventListener("afield-iframe-size"`) never runs and
`data-afield-intrinsic-width` never appears on the tag.

Combine that with a width the panel cannot use — a percentage, which reports a nonsense 100, or no
inline width at all, which sends it to the bounding-rect fallback and back to the panel — and there
is nothing left. This is the shape behind `keyframeBrowser`'s bug and behind the `.h2` explorer's,
both of which declare `width:100%`: a 192-pixel-wide document rendering inside a 200-pixel panel in
a box more than a thousand points wide, two thirds of it off the right edge.

Neither half is a mistake, so do not go banning the combination. `autosize="off"` is what you want
for a frame that scrolls its own content: its document is taller than the frame, and letting it
report its scroll height would grow the panel forever. A percentage width is what you want for a
frame that should be as wide as whatever it is put in. Both are correct alone; the pair is what goes
silent.

The pair therefore has a name.
`src/commonMain/resources/outputareas2.js:283#function isFillWidthIframe` is the three-part test —
autosize off, no reported intrinsic width, and no *concrete* inline width, where "concrete" is
`hasConcreteCssSize` (`src/commonMain/resources/outputareas2.js:96#function hasConcreteCssSize`) and
excludes the CSS-wide keywords, `auto`, the intrinsic keywords, a literal zero, and any percentage.

A frame that answers yes to all three is a **fill frame**: a frame that has declared, in the only
vocabulary CSS gives it, that it has no opinion about its own width.

### Four things in the tree are fill frames

Two near misses first, because both look like candidates. `Box.three`'s no-width path writes
`width:100%` but carries no `data-afield-autosize` attribute, so its viewer does report an intrinsic
width and it is not a fill frame. And
`src/jvmMain/kotlin/afield/python/PythonInterface.kt:1462#const val EMBED_IFRAME_STYLE` is
`width:960px` — pixels, with a KDoc explaining why the percentage was rejected.

The frames that do satisfy `isFillWidthIframe` are these, and they do not all get there the same
way:

- the lazy-output frame
  (`src/jsMain/kotlin/afield/Outputs.kt:833#iframe.setAttribute("data-afield-autosize", "off")`) sets
  only `height` inline. Its width comes from the `.afield-lazy-output-frame` class, and
  `hasConcreteCssSize` is handed `iframe.style.width`, which is the inline style only — so a
  class-set width is not concrete, and this frame is the one that genuinely takes the bounding-rect
  fallback and measures the panel it is in.
- the value-viewer frame
  (`src/jsMain/kotlin/afield/Outputs.kt:911#<iframe data-afield-autosize="off" src="${src}" loading="lazy"`)
  is the same story: its width lives in the `.value-viz-output-viewer-frame` class.
- the embedded value explorer a `.h2` panel puts in a box output declares `width:100%` inline, so it
  is the reports-100 shape rather than the measures-itself shape.
- `keyframeBrowser` when you pass it a non-positive width
  (`src/jvmMain/kotlin/afield/outputs.kt:869#val declaredWidth = if (width > 0)`), which is the one
  documented opt-in. Its KDoc at
  `src/jvmMain/kotlin/afield/outputs.kt:817#[width] is a pixel count and not a percentage` is the
  in-code account of this section.

## Why the fallback measures outwards

Every other measurement in `contentSizeForDisclosure` looks *inward*, at a descendant, and asks how
much room it needs. `fillWidthForDisclosure`
(`src/commonMain/resources/outputareas2.js:298#function fillWidthForDisclosure`) looks outward
instead. It walks up to the enclosing `.box` and returns `box.right - wrap.left`: the distance from
the panel's own left edge to the right edge of the box that owns it.

It has to, because the alternative is circular. A fill frame's rendered width is a function of the
panel's width, so anything inside the panel is downstream of the number being chosen and can only
echo the current value back. The enclosing box is the nearest thing upstream: its width was decided
by the author, or by a drag, before this panel existed.

It seeds the `Math.max` and it never caps. A non-nested panel is left
`maxW: Number.POSITIVE_INFINITY`
(`src/commonMain/resources/outputareas2.js:592#maxW: Number.POSITIVE_INFINITY`), so content with a
real intrinsic width wider than its box still wins and still overflows, which is what a wide table
wants. `fillWidthForDisclosure` is a floor for frames that have no opinion, not a ceiling for frames
that do.

With no enclosing box — a panel on a standalone page — it returns 0 and contributes nothing.

A hardcoded pixel width cannot do this job in general. `keyframeBrowser` is called with an explicit
intent and can be given `width = 900`; the value explorer is emitted for any value in any box with no
per-call knob, and 900px in a narrow box overflows it and crops the figure.

### The figure

Two frames in one box, identical content, differing only in the width each declares. Both carry
`data-afield-autosize="off"`, so neither will ever report a size of its own — the difference is
entirely in what `iframeIntrinsicSize` can read off the tag.

```diffstatic_runner {data-lang=kotlin screenshot="assets/output_panel_sizing/fill-vs-fixed.png" capture="box" open-outputs="true" wait="3.0" w="1040" h="160" padding="18" timeout="120" embed=true name="output-panel-sizing-fill-vs-fixed" alt="A Field box roughly 1040 points wide holding two open output panels, one above the other. The upper panel, titled 'no width of its own', spans the full width of the box and its dark blue frame fills it edge to edge. The lower panel, titled '640 pixels wide', is visibly narrower — a little under two thirds of the box — and stops well short of the box's right edge. Both frames are far taller than the 66 pixels their markup declares."}
// Two frames whose ONLY difference is the width each declares. Both are
// data-afield-autosize="off", so iframe_autosize.js returns early in both and
// neither will ever dispatch afield-iframe-size.
fun card(note: String) =
    "<body style=\"margin:0;background:#12202b;color:#8fd6ff;" +
        "font:12px/1.5 -apple-system,system-ui,sans-serif\">" +
        "<div style=\"padding:10px 12px\">" + note + "</div></body>"

fun frame(widthDecl: String, note: String) =
    "<iframe data-afield-autosize=\"off\" srcdoc='" + card(note) + "' " +
        "style=\"display:block;" + widthDecl + ";height:66px;" +
        "border:1px solid #2d4456;background:#12202b\"></iframe>"

// isFillWidthIframe says yes: autosize off, nothing reported, no concrete inline
// width. fillWidthForDisclosure gives the panel box.right - wrap.left.
display(
    "no width of its own",
    frame("width:100%", "width:100% + autosize off -> a fill frame. The panel takes the room the box gives it.")
)

// isFillWidthIframe says no: 640px is a concrete inline width, so iframeIntrinsicSize
// reads it straight off style.width and the fill-width fallback never applies.
display(
    "640 pixels wide",
    frame("width:640px", "width:640px -> a concrete intrinsic width. The panel is 640 plus padding, whatever the box is.")
)

displayOpen("no width of its own")
displayOpen("640 pixels wide")
```

The upper panel is the whole width of the box because nothing in it had a width; the lower panel is
640 pixels plus the content padding because something in it did. Neither panel measured itself.

Both frames in that picture are about two hundred pixels tall, and their markup says 66. That is the
[screenshot-prep pass](#the-screenshot-pass-is-a-second-sizer), which floors *any* panel containing
an iframe at 220 pixels
(`src/jsMain/kotlin/afield/Main.kt:1548#content.querySelector("iframe") != null -> 220.0`) on
`src/jsMain/kotlin/afield/Main.kt:1514#private fun fitOutputDisclosuresForScreenshot`, before the
picture is taken. A captured panel is not the panel a person sees, so do not read a height off a
docs figure.

Nor a width: those are pixels in a PNG. The measured version lives in
`tools/run_ui_regressions.py:3447#id="h2-panel-sizing"`, which asserts geometry rather than
photographing it — see [what is pinned](#what-is-pinned-and-what-is-not).

## Height is measured from the content edge

Height has one source and no fallbacks worth the name: the bottom edge of the lowest visible
descendant, taken relative to the top of the panel's content box and with the leading padding
removed, which is
`src/commonMain/resources/outputareas2.js:444#Math.ceil(rect.bottom - box.top - paddingTop)` —
the width axis does the mirror of it on
`src/commonMain/resources/outputareas2.js:441#Math.ceil(rect.right - box.left - paddingLeft)`. That
subtraction is the whole convention. A node's height is never its own height; it is how far down the
content box it reaches.

The iframe branch
(`src/commonMain/resources/outputareas2.js:419#if (node instanceof HTMLIFrameElement) {`) is the one
place where breaking the convention is invisible in the common case. Read
`iframeIntrinsicSize(node).height` — the frame's own declared height — as the content height, and a
frame that is alone in the panel and flush against the content edge still comes out right: its
height and its distance from the top are the same number. Put a summary row above the frame and the
two differ by the height of that row, and the panel is short by exactly that much, clipping a frame
it never resized.

So the branch adds the frame's offset from the content edge on both axes
(`src/commonMain/resources/outputareas2.js:434#Math.ceil(offsetTop + iframeSize.height)`), which is
the arithmetic every non-iframe node already does.

## The screenshot pass is a second sizer

`fitBoxWidthToOutputsForScreenshot`
(`src/jsMain/kotlin/afield/Main.kt:1579#private fun fitBoxWidthToOutputsForScreenshot`) runs when the
docs runner or the screenshot API is about to photograph a box. It exists because a figure should be
cropped to its content rather than to whatever width the box happened to be dragged to, and it works
in the opposite direction from everything above: it measures the panels and then grows
`box.style.width` to fit them (`src/jsMain/kotlin/afield/Main.kt:1652#if (desiredWidth > currentWidth + 2.0)`).
Its height sibling `fitOutputDisclosuresForScreenshot` overwrites panel heights outright, which is
where the 220px floor in the figure above comes from.

Put that next to a panel that takes its width from its box and you have a cycle with no fixed point.
The panel grows to the box, the prep pass grows the box to the panel, the auto-size passes are still
running and grow the panel to the new box. Nothing bounds it but the window-width clamp on
`src/jsMain/kotlin/afield/Main.kt:1625#val disclosureWidth`.

Breaking it needs both halves, and Kotlin therefore carries its own copy of the fill-frame test
(`src/jsMain/kotlin/afield/Main.kt:1566#private fun isFillWidthIframe`):

- fill panels are excluded from `maxContentWidth`
  (`src/jsMain/kotlin/afield/Main.kt:1585#if (contentIsFillWidth) 0.0 else content.scrollWidth` and
  `src/jsMain/kotlin/afield/Main.kt:1591#if (containsFillWidthIframe(element)) return@mapNotNull null`),
  so a panel that is only as wide as its box cannot be evidence that the box should be wider. The box
  stays at its authored `w=`.
- and they are exempted from the uniform `disclosureWidth` the pass then writes onto every panel.
  Writing it would *shrink* a fill panel to whatever the other panels needed, and because the
  auto-size passes keep running after the prep pass and put the width back, the panel would end up
  wider than the clip already measured. That is the cropped-right-hand-column symptom: a table whose
  `value` column falls outside the frame of a figure measured while the panel was narrower.

The two copies of `isFillWidthIframe` are not the same predicate, so do not trust one to describe
the other. The JS version routes the inline width through
`hasConcreteCssSize`; the Kotlin version at `src/jsMain/kotlin/afield/Main.kt:1566#private fun isFillWidthIframe`
accepts empty, `auto`, or a `%` suffix and nothing else. They agree on every width any Field output
actually emits. They would disagree about `width:fit-content` or `width:0`, which nothing emits
today.

## A dragged panel stops listening

None of the above applies to a panel a person has resized. `rememberSize` sets `userSized`
(`src/commonMain/resources/outputareas2.js:711#st.userSized = true`) the first time a panel's
measured size differs from its stored size by more than 2px, and every auto-size entry point is
gated on it. `autosize="fixed"` in the channel spec turns the whole mechanism off up front
(`src/commonMain/resources/outputareas2.js:665#autoSizeModeForDisclosure(d) !== "fixed"`).

Because `userSized` is sticky, an auto-size problem is only visible on a panel nobody has touched: a
fresh box, and every docs capture.

What records the drag is a `ResizeObserver` on the panel box
(`src/commonMain/resources/outputareas2.js:833#d.userResizeObserver = new ResizeObserver`) rather
than a `mouseup` on the panel
(`src/commonMain/resources/outputareas2.js:814#d.cw.addEventListener("mouseup"`), and the difference
shows on exactly the panels this page is about. A mouse event raised inside an iframe belongs to that
iframe's document and never reaches the one holding the panel, so releasing the grip over a frame
records nothing and the next auto-size pass reads the unchanged `st.width`/`st.height` and puts the
panel back. The observer does not care where the pointer went: the native grip's whole effect is
inline width and height on the element, so the box changing size outside a self-caused window is
somebody having dragged it. The three entry points that resize the box themselves — `applyOpen`,
`primeAutoSizeForOpen` and `applyClosed` — mark `selfResizeUntil`, or a panel nobody touched would
come out `userSized` and never auto-size again.

## What is pinned, and what is not

`tools/run_ui_regressions.py` has the only executable checks on any of this, and they are
browser-driven: they build a real box, run real Kotlin, wait for the auto-size passes to settle, and
read geometry out of the live DOM.

`tools/run_ui_regressions.py:3447#id="h2-panel-sizing"` builds a box at `--w 1040 --h 200`, displays
40 rows as a `.h2` value explorer, and asserts both axes of the resulting panel:

- `tools/run_ui_regressions.py:1045#result.panelTracksBox =` — the panel is within 24px of the box's
  width **and** does not overhang its right edge by more than 2px. Both halves matter: the first
  fails without `fillWidthForDisclosure`, the second fails if a hardcoded pixel width is used
  instead. It fails with `panel 200px does not track box 1040px`
  (`tools/run_ui_regressions.py:1058#does not track box`).
- `tools/run_ui_regressions.py:1049#result.frameKeepsDeclaredHeight =` — the frame still renders at
  the height it declared, which is the height-axis bug, and the case additionally asserts
  `summaryHeight > 0` so that the fixture cannot pass by having nothing above the frame.

`tools/run_ui_regressions.py:3436#id="output-area-basics"` covers the surrounding behaviour —
`display` creating and replacing channel content, embedded scripts executing, output actually being
visible — rather than geometry.

What neither pins:

- **the fill-frame population.** `h2-panel-sizing` exercises one fill frame; `output-area-basics`
  exercises none. The lazy-output frame and the value-viewer frame are fill frames by the same
  three-part test and are under no geometry assertion anywhere.
  `src/jvmTest/kotlin/afield/stdlib/KeyframeBrowserTest.kt:580#fun aNonPositiveWidthIsStillTheFillMyColumnEscapeHatch`
  pins `keyframeBrowser`'s *markup* — that a non-positive width still spells `100%` — without
  standing up a browser, so it says nothing about what a panel then does with it.
- **the screenshot-prep loop.** The regression cases photograph a box but assert nothing about the
  crop, so a feedback loop between the prep pass and the auto-sizer would show up as a figure
  someone notices, not as a red build.
- **agreement between the two `isFillWidthIframe` implementations.** Nothing compares them. They live
  in different files in different languages, one of them a browser resource and one of them compiled,
  and drifting is a matter of one edit to either.
- **the widths in the figure above.** They are pixels in a PNG. The assertions are in the
  regression case, not on this page.
