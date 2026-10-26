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
