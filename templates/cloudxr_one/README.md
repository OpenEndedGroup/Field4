# cloudxr_one

Scene-only OpenXR starter for Linux → CloudXR → Apple Vision Pro: a red cube on black,
rendered into a two-layer multiview target with a desktop mirror of the actual eye image.
The cube is 40 cm wide, centred 1.5 m ahead. The browser IDE is **not drawn on a texture by
default**. Edit this document in a laptop browser connected to the Linux server.

The Linux host and setup steps are in [tools/cloudxr](../../tools/cloudxr/README.md).
The reusable visionOS client is in [native/field-cloudxr](../../native/field-cloudxr/README.md).

## Boxes

1. **Stereo room / view** creates windows, OpenXR, eye targets and an echo message channel.
2. **Red cube shader** owns the editable vertex and fragment tabs; Alt+R reloads shaders.
3. **Frame loop** submits once per tick. Stop it before retiring the room.

Generate create/launch/stop operation transcripts with:

```sh
python templates/cloudxr_one/build_transcript.py
```

Apply create.json only to a new empty document; save launch.json as `cloudxr_one_launch`
and stop.json as `cloudxr_one_stop`. The host README gives the exact backdoor commands.
The launcher's private default workspace is `~/.local/state/field-cloudxr/workspace` and
port 9492, leaving an existing desktop Field on 9092 alone.

## Optional IDE panel

`start_field.py --ide` explicitly enables a 3200 × 2000 Chromium surface via FieldVrWorkspace,
with the same predicted eyes as the scene, and a front-on desktop companion. Its controls
show/hide the surface, recenter and focus the desktop keyboard. The red cube moves to the
right so it does not hide behind the panel. OpenXR hand actions drive pointing/pinch,
fist movement and two-fist resize on Vision Pro. Keyboard editing inside the streaming
client currently fails; laptop-browser editing remains the default workflow.

Headset dimensions come from the runtime; changing OpenXR mode requires a fresh JVM.
The working CloudXR setting renders unwarped 3800 × 3040 eyes before the runtime's
1200 × 960 transport warp. Float16 eye targets are verified; full stream bit-depth fidelity
and gaze-following foveation are not separately established.

The native app can also open an optional WKWebView window. Its gestures and native resolution
work, but its hardware keyboard path has the same connection-time failure as the app's
other native text fields. Channel 1 message discovery and preset Hello! echo work independently.
