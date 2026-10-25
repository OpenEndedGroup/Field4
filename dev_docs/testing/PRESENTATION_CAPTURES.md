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
