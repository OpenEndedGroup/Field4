# video_two — a carousel of time

Eight curved stereo screens face inward around the origin. One `StereoVideoSource` owns the
movie, transport and active decode stream. Each published P010 image is copied once into a
bounded GPU ring. The screens select progressively older pictures from that ring and own
independent temporal accumulators, paired-exposure clocks and Gaussian caches.

The default spacing is **one published picture**. Box 2 changes it from 1 to 4: eight screens
then use ages `0,1,…,7` through `0,4,…,28`. These are uploaded material pictures, after the
alternate-frame selection and material cap. They are not headset frames, and skipped decoder
pictures do not create history entries. Box 3 shows each screen's actual source timestamp and
age, so the relationship to playback speed is visible.

Only new selected pictures feed each accumulator. Repeated VR draws and cold-start holds never
add duplicate weight. Every headset frame draws with current eye poses. Neither the delay ring
nor the exposure caches contain a headset image.

## Build and launch

```sh
uv run --project . --group backdoor python templates/video_two/build_transcript.py \
  --headset --video /absolute/path/to/full-resolution-SBS.mp4 --output /tmp/video-two
```

The output contains operation transcripts `create.json`, `launch.json` and `stop.json`. Apply
`create` to a new `video_two` document through the backdoor. Connect WiVRn, then run `launch`.
Omit `--headset` for desktop stereo; changing headset mode requires a fresh Field process.
`--no-browser` omits the Field-in-VR workspace. The context retains the same float eye targets
and desktop preview as `video_one`.

Box 2 creates the carousel and exports `carousel` plus `movie`, an alias for screen 1 retained
for existing score/playhead code. Box 3 owns the render loop and shared transport. Every screen
has a material/exposure box and a separate editable accumulation shader directly below it.
Both shaders use `moduleHere` and standard reload. The GPU delay copies are independent of the
colour and accumulation shaders and preserve all P010 code bits.

## Layout and styles

The default radius is 2.5 metres and each screen fills 90% of its 45-degree sector. Arc width
and curvature follow the radius: every screen lies on the same cylinder, with +Y up. Ring
rotation and centre are independent of the viewer's subsequent motion. Edit the screen count
in box 2's constructor; the prepared document uses eight.

Box 3's style buttons broadcast speed/mix changes to all screens. Screen boxes then allow
independent accumulation mix, half-life, input weight, clearing, paired cadence, rate, blur
sigma/mix and linear-light darkening. **Copy screen 1 look to all** broadcasts those settings;
it does not merge the histories. Per-screen control readouts are initialized when their boxes
run; the source time table stays live. A running score can overwrite the material values.

A copied Resolve score and video-time score should broadcast exposure settings to the ring,
while changing the shared speed once. Resolve playhead following/Get uses screen 1's newest
image timestamp. It never uses a delayed screen as the shared transport clock. Stop the scores
for independent manual tuning.

## History lifetime and memory

Seek, loop wrap, file replacement and a delay-spacing change discard the old temporal layout.
During refill each screen holds the oldest available picture. There is no negative seek or
additional decoding to manufacture the pre-roll. Pause freezes source publication and every
screen's paired-exposure phase. A local exposure clear affects that screen only.

The history uses 3 bytes per packed source pixel (P010 luma plus subsampled chroma), excluding
allocation padding. For 3840×1080, eight pictures cost about 95 MiB; 29 cost about 344 MiB.
Slots allocate as history fills. Each enabled full-resolution accumulator adds about 63 MiB,
and each enabled paired-exposure cache about 55 MiB. The eye buffers are separate. The single
`VideoTexture` retains its existing standby decoder for cheap loop rewinds; there are no eight
independent playback/decode queues.

## Agents and implementation

`StereoVideoSource` registers its upload/history target before the surface preparation targets.
Each `StereoVideoSurface` selects its own immutable timestamp/serial plus persistent image
views, then updates its own accumulation and cadence before either eye is rendered. The GPU
copies bracket transfer layouts and restore sampled layouts; slot reuse waits for prior shader
reads through command-buffer barriers, without a readback or a new queued frame.

A surface created without an explicit shared source owns a one-picture source, preserving the
`video_one` API. Shared surfaces do not close their source; `StereoVideoCarousel.close()` closes
them first, then the source. File changes rebuild source-sized exposure resources. Delay-ring
resizing retains a valid current picture while the new ring refills. Use the live graph and
inherited `prop` values; do not construct a decoder in a screen box.

Checks: `VideoHistoryCursorTest` covers warm-up, wrap and ages 0–28. The Vulkan smoke
`four.stereo-video.carousel` exercises actual P010 copies, ten-bit precision, eight delayed
outputs, independent exposures/cadence, cylindrical geometry and seek/refill. The existing
`four.stereo-video.precision` still checks the ordinary one-screen path.
