# Drawing On: optional native iPad head

Drawing On captures Pencil data and microphone audio on a shared timeline, records offline, uploads saved captures, and displays FLine reference geometry streamed back from Field. The native app is maintained as a companion checkout (`/Users/marc/temp/drawingon` in the development workspace), containing `DrawingOn.xcodeproj`, its independent Swift package, tests, and the complete protocol/setup guide at `docs/FIELD_EXTENSION.md`. Field's JVM build has no Xcode dependency. The app sources have not been vendored into this repository.

Open that Xcode project, choose an iPad and your signing team, and run. Connect to `wss://YOUR-FIELD-HOST:9092/drawingon/ws`, target `ipad`, with a host certificate trusted by the iPad. A physical iPad also needs Field started with `AFIELD_BIND_HOST=0.0.0.0` for LAN reachability; keep the default browser-origin policy enabled. The default Field development certificate only covers localhost. A simulator can use the existing loopback mirror at `ws://127.0.0.1:19092/drawingon/ws`.

Paste [Example.kt](Example.kt) into a Field box. It publishes a plan, receives committed native input, and adds a response when a human stroke ends. Re-run to edit the plan or callback. `drawingOn("ipad")` owns an explicit target; unrelated desktop selection cannot change it. Geometry comes from Field's existing AVP Three description/buffer encoder, with an explicit 2D projection in `DrawingOnPlane`.

`pad.onEvent` receives the original JSON payload in `event.data`, the monotonic capture time in `event.time`, the session UUID in `event.session`, and a historical-upload flag in `event.upload`. Callbacks run on the Field main Runloop after durable append; filter event types and keep expensive computation outside the callback. `pad.sessions` lists bound captures and `pad.readCapture(session) { ... }` streams their history. The archive is `WORKSPACE/.drawingon/`, including uploaded audio and returned reference assets. Acknowledgements follow disk sync; replay is deduplicated by session and sequence. Callback delivery itself is not a transactional job queue: recover derived processing from the archive after a server crash.

One Field reference layer per target supports hide/solo without entering human undo. Responses are hashed, retained locally, and chunked into the recording, so later exports/uploads need no running Field server. Disconnection leaves the last reference visible and local recording continues. Saved uploads do not subscribe to returned geometry. FLine stroke/fill/point geometry uses an explicit drawing plane; the initial native renderer uses constant-width lines and flat primitive colours, rather than a 3D camera or materials.

Verify against a real native URLSession client on macOS:

```sh
DRAWINGON_NATIVE_PATH=/absolute/path/to/drawingon ./gradlew fastTest --tests 'afield.io.drawingon.DrawingOnTest'
```

The test starts an isolated Ktor receiver and exchanges actual FLine binary geometry, drawing, audio, undo, and saved uploads. It checks asset reconstruction and reconnect deduplication without a desktop server or user workspace. Without that environment variable, the three JVM archive/ownership tests run and the cross-language case is explicitly skipped. Run `swift test` separately in the native checkout for its full core suite. These tests do not validate physical Pencil or microphone latency.
