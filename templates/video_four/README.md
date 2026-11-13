# video_four — a WiVRn stereo reading room

The eight-screen `video_three` room, with one shared decoder, delayed full-resolution SBS
pictures, additive blending, per-screen exposure, accumulation and ordered whole-ring looks.
The default remains **vanilla-2x**: 2× playback, 24 fps material updates, 0.97 screen fill.
No wobble or precession is applied to the stereo geometry.

## Create the document

```sh
python templates/video_four/build_transcript.py --output /tmp/video-four-transcripts
```

In Field's backdoor, open/create `video_four`, then:

```text
macro write build-video-four --file /tmp/video-four-transcripts/create.json
macro run build-video-four
macro write start-video-four --file /tmp/video-four-transcripts/launch.json
```

Creation does not start a renderer. Connect the headset to WiVRn before running
`macro run start-video-four`. Stop with the generated `stop.json` macro before relaunching;
changing eye resolution or switching desktop/headset mode requires a fresh Field process.

Put the default movie at `video_four/files/thisLand_3D_SBS.mp4`.
The transport resolves `%document/thisLand_3D_SBS.mp4` through `FileManager`, so the
same document works after copying it to another machine or workspace. Override it with
`FIELD_STEREO_VIDEO` at startup, `--video` when generating, or **Full SBS file** in the
transport box. Full SBS retains its native per-eye aspect;
no horizontal squeeze is assumed. Playback is silent.

## Linux → WiVRn

Start the WiVRn server and connect the Quest, then from the Field checkout:

```sh
python tools/field_video_four.py --check
python tools/field_video_four.py
```

The launcher selects `/usr/share/openxr/1/openxr_wivrn.json` for this process, so a CloudXR
runtime selected elsewhere does not capture this session. The system's runtime setting is unchanged.

## macOS → WiVRn

Run `./field.sh --setup`, select **WiVRn headset streaming**, and choose **BUILD**
to install WiVRn and prepare the OpenXR loader. Details are in
[the macOS WiVRn guide](../../public/docs/field_vr_ide.md). Start WiVRn.app and connect the
headset. The same commands above delegate to `tools/field_wivrn_macos.py`, which selects the
local runtime, loader and MoltenVK. `--check` performs a preflight without starting Field.
The Field checkout must contain the shared stereo-video APIs used by `video_three`.

`--workspace /path/to/AFieldWorkspace` selects another documents root.
`--desktop` starts a fresh desktop-only session on either platform without OpenXR.
Once Field opens `video_four`, run the saved **start-video-four** macro.

## Editing and precision

Box **1** owns startup, preview and the optional sharp Field pane. Set `withFieldBrowser = false`
before launch to suppress that pane; generation also accepts `--no-browser`.
The video shader and accumulation shader remain ordinary editable shader boxes with reload support.
All eight screens share transport but retain independent exposure and material settings.
See [video_three](../video_three/README.md) and its guides for the look system.

Scene and accumulation use float16; the OpenXR runtime chooses its advertised eye format.
Box 1 reports that format and prints a warning for an eight-bit handoff. An eight-bit runtime
handoff is usable but is not end-to-end ten-bit. Encoder/stream precision is a separate WiVRn
setting, and the desktop mirror can quantize to its display format.

When copying a document, include its `files/` directory: that holds the movie, ordered looks and ring
arrangements. The movie is document data, not a repository asset. Avoid copying
history/screenshots/backups.
