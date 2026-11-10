# Native presentation screenshots

The public presentation guide's four screenshots come from the real Field Electron starter.
`tools/presentation/capture-docs.mjs` navigates its actual controller, waits for image readiness and
finished crossfades, opens its CSS file in Field's editor, and captures the audience/controller/editor
through Chromium's screenshot API. It never constructs substitute slide HTML or composites views.
The adjacent `capture.json` records source hashes and image hashes. These committed captures stay
available on hosts without Electron, as ordinary documentation assets.

To regenerate on macOS:

1. Build this checkout and launch its Field server on a free high port with a disposable workspace.
2. Copy the starter using `electron/presentation-starter.js`'s `createPresentationDocument(workspace,
   destination)`, or choose **New Presentation** in that checkout's Electron app. Use an untouched copy;
   the capture script checks its source against the template. Do not point at an authored lecture.
3. Launch this checkout's Electron executable with its **electron application directory** as the final
   argument and `--remote-debugging-port=29482`. Set `APP_URL` to the disposable document's URL.
   Run the Presentation box if autorun has not opened the audience and controller yet.
4. Run `node tools/presentation/capture-docs.mjs http://127.0.0.1:29482 /tmp/workspace/docs-talk`.
5. Inspect all four PNGs, then build the docs normally. Failed assertions mean failed capture: repair
   the starter, runtime or capture driver rather than substituting an illustration for the screenshot.

The capture driver requires Node 22+ (built-in WebSocket), and no per-talk JavaScript dependencies.
It changes the demo's selected slide and opens its CSS editor. It does not edit the talk or stylesheet.

## Cues and reset

`node tools/presentation/rehearse-lessons.mjs <CDP URL> <disposable starter directory> [capture directory]`
rehearses the new **Predict a wave** slide. It navigates real controller buttons, edits frequency in
the native audience document's Monaco editor, runs the changed box through the warm backdoor,
resets the captured source and checks that setup reproduces the original waveform. It asserts that
unrelated source stays unchanged. Start `tools/field_session.py serve` on the same server port first,
then `call --no-reset --command 'open <document>'`. Never aim this at an authored lecture.

It records `wave-reveal.png`, `wave-controls.png` and `rehearsal.json` (source hash and checks).
The default destination is `/tmp/field-lesson-rehearsal`; the public guide uses the checked-in
captures produced with destination `public/docs/assets/presentation_studio`. Inspect both images.

## Native builds and themes

With an unchanged disposable copy of the current presentation starter open in this checkout's
Electron, run:

```sh
node tools/presentation/rehearse-builds.mjs http://127.0.0.1:29493 /tmp/workspace/builds-demo
```

This drives the actual native controller and audience. It checks that element fades wait for the
outgoing crossfade and have an intermediate opacity; the retained image is loaded, dimmed and inert;
Back and Refresh show the completed build; and reduced-motion disables the fade. It temporarily
copies each standalone theme onto the disposable document's presentation.css and restores that file
in a finally block. It captures `layered-build.png` and the three `theme-*.png` views, and records
source hashes and image hashes in `builds.json`. Inspect all four images before committing them.
The source document must be disposable because ordinary Field saves still run during the rehearsal.

## Keyboard search

Run `node tools/presentation/rehearse-search.mjs <CDP URL> <disposable starter document>` with the
warm backdoor session and native Electron attached to the same document. This adds 100 authored
Markdown slides to the disposable slide file, reruns Presentation, and drives real CDP keyboard input
through the native controls. It checks body matches and excerpts, slash and Cmd/Ctrl+F, arrow choice,
Enter and repeated hops, unchanged audience while typing, accent matching, multiple terms, zero
results, refreshed search text, and Escape returning to normal navigation. It restores the starter
file and reruns Presentation before capturing the real 11-slide search view to `search.png`, with
source and image hashes in `search.json`. Its finally block restores the disposable source on failure.

## Lecture layouts and automatic staggering

Create a disposable copy of the starter, then replace its Presentation box source with the bundled
`files/examples/lecture.kt` before opening it in Electron. Keep its `files/themes/lecture.css` and
`files/examples/lecture-*.svg` files unchanged. Run:

```sh
node tools/presentation/rehearse-lecture.mjs http://127.0.0.1:29495 /tmp/workspace/lecture-layouts
```

The driver checks the actual four-slide audience, loaded images, frame bounds, right-aligned
banner tagline, visible incoming content during the native crossfade, intermediate opacity and
increasing list delays. It also checks completed Back/Refresh and the real reduced-motion media
preference. `lecture.json` records source hashes, motion samples and image hashes. Inspect the four
`lecture-*.png` captures; `lecture.css` is the matching downloadable theme, not a screenshot asset.
The example uses Field's own editable SVG studies rather than reproductions of reference artworks.

## Live-document step

With the same disposable starter open in this checkout's Electron, run
`node tools/presentation/capture-live-document.mjs <CDP URL> <disposable starter directory> public/docs/assets/presentation_studio`.
It visits **A real webpage** first so the audience document opens fresh, then selects
**A live Field document**, waits for the **Run reveal_wave** and **Start document guide** controls
and the guest document's boxes, and captures `live-document-audience.png` and
`live-document-controls.png` with hashes in `live-document.json`. The audience picture is taken
1.5 s after the boxes draw; by about 3.5 s the canvas has dimmed, so do not lengthen that pause to
lose the loading toasts.

## Live figures

A live figure is a native view laid over its slot, so a CDP screenshot of the audience page shows
the slide with an empty slot. `tools/presentation/capture-figures.mjs` captures the composited
audience window instead, with macOS `screencapture -l` and a window id from
`CGWindowListCopyWindowInfo` (it compiles a ten-line Swift lister into the temp directory once).
Replay `dev_docs/agents/transcripts/explainer_sine_pitch.yaml` into a disposable workspace
document, open it in this checkout's Electron, run its `deck` box so the talk opens, then run
`node tools/presentation/capture-figures.mjs <CDP URL> <workspace>/explainer_sine_pitch public/docs/assets/presentation_studio`.
It visits the prose slide first so the figure view opens fresh, waits until the figure page shows
the expected slider value (220 Hz, then 440 Hz after the glide cue), brings the audience forward
before each capture (macOS does not composite views of a covered window), and writes
`figure-slide.png`, `figure-glide.png` and `figures.json` with the transcript and image hashes.
`dev_docs/EXPLAINER_PRESENTATIONS.md` has the full recipe, from starting Field to stopping it.

For a whole deck, `node tools/presentation/capture-deck.mjs <CDP URL> <Electron PID> <output dir> [step ids...]`
walks the presented talk from its first view to its last with Next and writes one window capture per
view, `NN-<step id>.png` (`-cueK` for a view a cue reached), plus each figure page on its own as
`NN-<step id>.figure-<box>.png` and a `views.json`. It waits for every figure page to settle (images
decoded, no panel clipping its content, the scale unchanged for 1.5 s) and picks the "Field audience"
window owned by the given PID, since several Electrons may be presenting at once. Several Electrons
on one screen raise their audience windows over each other, and a covered window gets no animation
frames: its figure panels stop resizing to new content and its figure views come out black. Start
the capturing Electron with `--disable-backgrounding-occluded-windows --disable-renderer-backgrounding`
(after `--remote-debugging-port=<cdp>`) and a covered window keeps drawing, and `screencapture -l`
sees its figures. A locked screen still blanks them; check `CGSSessionScreenIsLocked` in
`ioreg -n Root -d1` first.

`node tools/presentation/orbit-figure.mjs <CDP URL> <figure box uid> <output dir> [--then-next]`
checks a `three` figure on the current slide: it drags across the figure view with CDP mouse input
and requires the viewer's camera (`FieldViewer.cameraPose()`) to have turned about its target, the
distance unchanged; with `--then-next` it runs the presenter's Next (a cue that redraws the figure)
and requires the camera to be kept.

None of these drivers launches or quits Electron. Close the Electron you started (`kill -INT` its
PID) and stop its Field server when the capture is done, whether or not it passed; Electron
renames its process to `Field4`, so record the PID when you launch it rather than searching for it
afterwards, and never signal a `Field4` you did not start.

## The explainer pages' slides

`public/docs/explainer_{sound,cameras,lights}.md` show, in the right margin beside each figure, the
slide that first uses it, plus the title slide and the "Further reading" slide. They are
`capture-deck.mjs` window captures of the deck presented in this checkout's Electron, one deck at a
time: replay the transcript into a disposable workspace document, open it in Electron (started with
the two Chromium switches above, PID recorded), run its `deck` box, and run

```sh
CAPTURE_ONLY=<first-use step ids, comma-separated> \
  node tools/presentation/capture-deck.mjs http://127.0.0.1:<cdp> <Electron PID> <out dir> <every step id, in order>
```

Each page uses the cue-0 view of its step (`NN-<step id>.png`), scaled with `sips -Z 960` to
`public/docs/assets/explainer_<deck>/slides/<step id>.png`. The `capture.json` beside them records
the transcript's hash, each window capture's hash before scaling and each committed file's hash.
To move Electron to the next deck, stop the presented `deck` box, navigate the main page to
`index.html?file=<next document>` over CDP, and run that deck's `deck` box. The figure fences on the
same pages are ordinary docs-runner captures; `tools/explainer_pages.py check` keeps their code equal
to the transcripts' figure boxes, and `sync` rewrites them after a deck edit.
