# Why an embedded viewer's output panel opens at 480 x 174

An iframe-bearing output panel — a Three.js viewer from `Box.three` or `Box.threeLines`, and any
other output that goes through the iframe view — opens at **480 x 174** and stays there, no matter
what size the iframe asks for. Dragging it works and the size persists; nothing else does.

This page is the map of that, because it took seven wrong hypotheses to find, and every one of them
looked right from the source.

## The chain, and where it actually breaks

`src/jsMain/kotlin/afield/outputareas.kt:186-193`, the iframe output view:

```kotlin
iframe.style.height = request.height ?: iframe.style.height.takeIf { it.isNotBlank() } ?: "420px"
contentRoot.asDynamic().dataset.afieldAutoSizeMode = "fixed"
```

It applies the requested height **to the iframe**, marks the panel `fixed`, and **never sizes the
panel**. `fixed` is then read by `outputareas2.js`:

```js
function autoSizeModeForDisclosure(d) { ... return mode || "content" }
function usesContentAutoSize(d) { return !d?.inline && autoSizeModeForDisclosure(d) !== "fixed" }
```

and `markContentChanged` is gated on it:

```js
if (!st.userSized && usesContentAutoSize(disclosures.get(id))) scheduleAutoSize(id, reason);
```

So auto-size never runs, and the panel keeps the constants it was born with —
`DEFAULT_W = 480`, `DEFAULT_H = 150`, plus the header, which is the 480 x 174 you see.

**The measurement side is not the problem and never was.** Replicating
`contentSizeForDisclosure`'s own arithmetic against a live panel returns ~1460 for a viewer that
declares 1460: the iframe is inside the content wrapper, `isElementVisible` passes, the intrinsic
dataset is read, and `boundsForId` leaves a non-nested panel `maxW: POSITIVE_INFINITY`. It would
size correctly if it were ever asked to.

## The trace that settles it

Instrument `markContentChanged`, `scheduleAutoSize`, its `runPass`, and `rememberSize` to push
records onto a `window` array, then read it back. 159 events for one viewer, and every one of them
says the same thing:

```
markContentChanged  id:"confluence3d"  reason:"iframe-report"  userSized:false  uses:FALSE
```

with **no** `schedule:enter`, no `pass:run`, no `rememberSize:userSized` anywhere in the log, and

```
cwDataset: {"origPadding":"4px","afieldAutoSizeMode":"fixed"}
```

`uses` also flips `true -> false` partway through the run, which is the moment the iframe view
stamps `fixed` onto a panel that had been auto-sizing until then.

## Seven hypotheses, each killed by one measurement

Recorded so nobody spends the afternoon again. In order:

| # | hypothesis | what killed it |
|---|---|---|
| 1 | `threeLines(height = …)` sizes the panel | it never publishes a frame size at all; `frameWidth`/`frameHeight` stay `null` |
| 2 | `parsePixels("100%")` returns 0, so a percentage measures as nothing | it is `parseFloat`, so it returns **100** — positive and meaningless. `Three.kt`'s own KDoc says so |
| 3 | `Box.three`'s pixel `width` fixes it | it correctly publishes 1460/840 onto the dataset; the panel does not move |
| 4 | the panel tracks the box, so a bigger box fixes it | a **1480 x 880** box still gave a 480 x 174 panel |
| 5 | it is a persisted size from an earlier run | a **fresh channel name** gave 480 x 174 too |
| 6 | the intrinsic size arrives after the sizing pass, so declare it up front | emitting `data-afield-intrinsic-width/height` on the iframe changed nothing |
| 7 | an async-loading iframe trips the `ResizeObserver` and sets `userSized` | the trace shows `userSized:false` throughout; a change gated on `lastAutoSizedContentVersion` was built, served, verified live, and made no difference |

Hypothesis 7 is worth its own line, because it is the plausible one: `rememberSize` really does set
`st.userSized = true` on any >2px change outside a `selfResizeUntil` window, and the observer's own
comment warns that recording a self-caused resize would "permanently disable auto-size for a panel
nobody touched". It is a real hazard. It is not this one.

## What the intended contract is

From `tools/run_ui_regressions.py --id h2-panel-sizing`, which pins it:

> *The panel claims the room the box gives it, and stops there. A frame that measured itself stayed
> at the 200px header width; a frame given a hardcoded pixel width overflowed a narrow box.*

So "make the panel honour a 1460px iframe inside a 620px box" is **not** the fix — that is the
overflow bug that test exists to prevent. The defect is narrower: a `fixed` panel is never given a
size at all, so it falls back to a constant instead of to anything the author or the box implies.

## If you fix it

The fix belongs where the mode is stamped — an iframe view that declares a size should set the
panel's size at the same time. Two things make that less trivial than it sounds:

- The interface `outputareas.kt` holds is `open` / `close` / `remove` / `setContent` /
  `upsertDisclosure`. **There is no size setter**, so either one gets added, or the adoption happens
  on the JS side when a `fixed` panel contains an iframe with a concrete declared size.
- `fixed` is stamped at **three** call sites: the iframe view (`:192`), a lines-figure path (`:479`),
  and **the Monaco editor disclosure** (`:1039`). Anything that changes how `fixed` panels take their
  size reaches the code editor panel, so it is not a local change.

Gate any attempt on `tools/run_ui_regressions.py --id h2-panel-sizing`, and add a case for an
iframe-viewer panel at the same time — there is currently no regression that would notice this
defect, which is why it has survived.

## Measuring this yourself

`page-eval` opens **its own client session** (`user=playwright`), which has the document but no
output panels unless it was already connected when the box ran. Run the box and the probe in **one**
invocation with `--with-playwright`:

```
python3 tools/field_backdoor.py --attach-existing --port <port> --document <doc> --with-playwright \
  --command "run <uid>" \
  --command "wait-output <uid> --contains DONE --timeout 240" \
  --command "page-eval --file probe.js --wait-ms 5000"
```

Without `--with-playwright` you get `0 disclosures` and an empty result, which reads exactly like a
finding and is not one. `screenshot-box` is no help either: it returns the 48 x 48 placeholder here,
and reports the box frame as 24 x 24 when the boxes are collapsed in the live view.
