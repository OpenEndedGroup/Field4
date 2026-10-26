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
