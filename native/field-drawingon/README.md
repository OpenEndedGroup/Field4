# Drawing On

Optional native iPad drawing and voice capture for Field. This directory is the source of truth for the app and its offline save-file reader, alongside Field's optional visionOS clients. Build the app independently in Xcode; Field's JVM/JS build does not require Xcode or build an iPad application.

Read [Drawing on iPad](../../public/docs/drawing_on.md), open the **drawing_on** starting place from Field's Welcome screen, and run its drawing-head box. [Example.kt](Example.kt) is the same editable example: publish a plan, draw over it on the iPad, and receive a crosshair at the last measured Pencil point. [Protocol](docs/PROTOCOL.md) documents every captured event; [Field integration](docs/FIELD_EXTENSION.md) covers target ownership, projection and archive access.

## Build and connect

On a Mac, `./field.sh --setup` from the Field root offers **Drawing On iPad app / offline reader**. Its BUILD step compiles only the local reader and prints the Xcode steps below. It does not install models, sign the app, or install it on a device.

Open `DrawingOn.xcodeproj`, select the **DrawingOn** scheme, choose an iPad, and set your development team under Signing & Capabilities. The app requires iPadOS 18 or later. It uses UIKit for Pencil input, SwiftUI for its menu, and bundled Iosevka Light. No external iOS dependencies or App Store setup are needed.

Start Field with `AFIELD_BIND_HOST=0.0.0.0` for LAN access. In the app set the address to `wss://YOUR-FIELD-HOST:9092/drawingon/ws`, target `ipad`, and enable **Stream live**. Use an HTTPS certificate matching that hostname and trusted by the iPad; Field's default localhost development certificate needs replacing for a physical device. Configure Field through `AFIELD_KEYSTORE_PATH`, `AFIELD_KEYSTORE_PASS` and `AFIELD_KEYSTORE_ALIAS`. Certificate validation stays enabled. The simulator on the same Mac can use Field's loopback mirror at `ws://127.0.0.1:19092/drawingon/ws` with default ports.

Pencil draws; freehand, line and circle tools sit at the bottom left. Two fingers pan, rotate and scale. Two-finger tap undoes, three-finger tap redoes. The menu manages layers, hide/solo, microphone recording and transfer. Undo is an appended visibility operation; it preserves original samples. Field output occupies a separate reference layer. Portrait and landscape are supported; light mode inverts the canvas.

Leave streaming off to record offline. **Finish capture** saves an immutable recording and opens a new canvas. **Saved captures** uploads later, resumes interrupted transfers, and retains originals on the iPad. **Share capture** exports one self-contained `.ndjson` file, including audio, undone strokes and received Field geometry. Captures are also accessible through Finder/Files file sharing. Microphone recording is explicit and stops in the background.

## Read a save file offline

From this directory on a Mac:

```sh
tools/offline-review/review /path/to/capture.ndjson --output /path/to/capture.review
open /path/to/capture.review/index.html
```

This creates PNG contact sheets and individual viewport images, speech-candidate WAVs, a local review page, and a capture-aligned JSON manifest for downstream agents. The reader shares the app's replay model and Field buffer decoder. Rendering and heuristic energy VAD need only macOS frameworks and the installed Swift compiler. Optional STT uses an explicitly supplied existing local Whisper executable/model and downloads nothing. See the [offline reader guide](tools/offline-review/README.md).

## Verify

Run the core/replay tests and offline-reader tests from this directory:

```sh
swift test
tools/offline-review/test
xcodebuild -project DrawingOn.xcodeproj -scheme DrawingOn -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath .build/xcode \
  CODE_SIGNING_ALLOWED=NO build
```

From the Field repository root, exercise the real Ktor receiver and native URLSession client together:

```sh
DRAWINGON_NATIVE_PATH="$PWD/native/field-drawingon" ./gradlew fastTest --tests 'afield.io.drawingon.DrawingOnTest'
```

The standalone `tools/receiver.py` and its tests remain an input-only diagnostic receiver. Field's native `/drawingon/ws` route is the normal bidirectional destination. `python3 tools/make_project.py` regenerates the checked-in Xcode project when Swift files are added. Sensor precision and Pencil/microphone latency still need a physical iPad check.
