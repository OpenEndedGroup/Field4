# video_two — a carousel of time

Eight curved stereo screens face inward around the origin. One `StereoVideoSource` owns the
movie, transport and active decode stream. Each published P010 image is copied once into a
bounded GPU ring. The screens select progressively older pictures from that ring and own
independent temporal accumulators, paired-exposure clocks and Gaussian caches.

The default spacing is **one published picture**. Box 2 changes it from 1 to 16: eight screens
then cover ages 0–7 through 0–112. **Reverse delay direction** defaults on: in screen-number
order the ages are `0,7,6,…,1` times the spacing. Turn it off for `0,1,…,7`. Screen 1 stays
newest, and each screen keeps its geometry and appearance. These are uploaded material pictures, after the
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
it does not merge the histories. Per-screen controls follow recalled keys and global style changes
at UI rate; the source time table stays live. A running score can overwrite the material values.

A copied Resolve score and video-time score should broadcast exposure settings to the ring,
while changing the shared speed once. Resolve playhead following/Get uses screen 1's newest
image timestamp. It never uses a delayed screen as the shared transport clock. Stop the scores
for independent manual tuning.

## Shared screen looks

Box **0 · Shared screen looks** captures one screen into a common, native Field keyframe pool.
Choose **Capture from screen**, enter a name and press **Store named look**. Each material box
also has **Store from this screen**. The initial look is seeded as **slow blur** once; later
launches retain the saved key. Storing the same name adds a version.

Keys contain accumulation mix, half-life, incoming weight, cadence enable/rate, soft-half blur
sigma/mix and darkening, plus shared playback speed and, once Ring motion is launched, the six headset-flash settings. Check any collection of screens and press
**Recall to checked screens**. **Restore playback speed** defaults off: appearance changes keep
the current shared speed unless explicitly enabled. With no screens selected, this option
allows a speed-only recall. Each screen's own Recall button applies just to that screen.

**Stagger / screen (s)** inserts a fixed wall-time delay between the selected screens in ring
order. For screens 2, 4 and 7 with 0.2 seconds, arrivals are at 0, 0.2 and 0.4 seconds. Zero
applies together. The shared speed changes once at the start when enabled. A new recall replaces
pending arrivals; **Cancel pending recall** leaves already-applied screens alone. The status
shows progress. Playback and headset rendering continue throughout.

Keys live at `%document/keys/screen-looks` (`files/keys/screen-looks` in the document folder).
The native browser lists saved looks and their versions. Application uses the named Recall
controls above. Keys store parameters, while the current accumulation pixels continue evolving.
Ring geometry, frame spacing and spin remain separate global controls. Stop active scores when
tuning manually, since scores can continue writing appearance parameters.

From code, indices are zero-based and the exported properties resolve through the box graph:

```kotlin
screenLooks.store("slow blur", screen = 0)
screenLookRecall.recall("slow blur", screens = setOf(0, 2, 7),
    withSpeed = false, delaySeconds = .2)
screenLookRecall.cancel()
```

The shared-look box ticks the bounded scheduler; it loads each recalled key once and keeps at
most one pending arrival per selected screen. No movie seek or GPU readback is involved.

## Ring motion and the headset zoetrope

**Ring motion** retains manual coarse/fine spin. **Stepped rotation** holds the geometry for
**Frames per step** whole headset refresh periods. **Lock to screen sectors** replaces manual
spin with signed, integer sector hops at those boundaries. One sector is 45 degrees for eight
screens. **Lock drift** adds a tiny continuous detuning: 0.001 turns/second is 0.36 degrees/second.
Zero drift locks the repeated screen outlines; different movie ages and looks still swap places.

Start with **Try sector lock + flash**. It sets one sector every ten headset ticks and opens all
shutters for the first tick. At 120 Hz that is twelve steps and flashes per second, with an
8.33 ms illuminated pulse. **Open all shutters** removes flashes while retaining rotation;
**Stop ring rotation** disables sector lock and drift and holds the current orientation.

Each material box has **headset flash** controls: enable, separate **on/off times**, separate
**on/off brightness** and a signed **phase**. Durations are integer headset ticks; one tick is
8.33 ms at 120 Hz. The interval is on time plus off time. Either duration may be zero to hold
the other level continuously; the controls prevent a zero-length total cycle.

Brightness is a linear-light multiplier, after sharp/accumulated mixing and video-local blur.
One preserves the image, zero makes it black, and two doubles its light. Both levels are
independent: use a dim off level to keep the picture visible between flashes, or invert the
levels. The UI ranges from 0 to 4; the float scene retains values above one, though final
headset encoding may clip them. There is no automatic duty-cycle compensation.

In **Ring motion → shared flash timing**, **Flash stagger / screen (frames)** adds a signed
phase delay per screen. Screen 1 adds zero, screen 2 one stagger, screen 3 two, and so on.
Each screen's local phase adds to this offset, modulo its own on+off interval. **Copy screen 1
flash to all** copies its six envelope settings while retaining the shared stagger. **Align
all flash phases** zeros local phases and shared stagger, preserving durations and levels.
The stagger is a global control separate from movie delay spacing and keyframe-recall delay.

For example, on 2 / off 8, levels 1.5 / 0.1, stagger 1 gives a ten-tick cycle whose two-tick
bright window travels around the ring. Match the total interval to **Frames per step** to
expose a fixed part of each rotation step. All six per-screen settings join newly stored
screen looks; old keys without them leave current envelopes unchanged.

Both rotation and flashes read one `VideoCarouselClock` on the graphics queue, before the
surface preparation targets. Its origin is shared. OpenXR's predicted display time and actual
refresh period count physical refresh ticks, including missed application frames; duplicate
predictions never advance. A missed image cannot be flashed retrospectively, but subsequent
pulses keep their schedule. The status shows the measured runtime rate and common tick counter.
Desktop mode uses a nominal 120 Hz grid. These clocks run independently of video playback,
including pause, 24 fps material selection and the separate soft/dark exposure pairs.

The envelope multiplies the screen's linear-light output; zero skips its draw module. The source, history, exposure and head pose keep
updating, and the Field pane stays visible. Flashing adds no RTT or readback. Rotation is applied
before this frame's mesh push constants; both eyes share the same rotation and shutter state.

## History lifetime and memory

Seek, loop wrap, file replacement and a delay-spacing change discard the old temporal layout.
During refill each screen holds the oldest available picture. There is no negative seek or
additional decoding to manufacture the pre-roll. Pause freezes source publication and every
screen's paired-exposure phase. A local exposure clear affects that screen only.

The history uses 3 bytes per packed source pixel (P010 luma plus subsampled chroma), excluding
allocation padding. For 3840×1080, eight pictures cost about 95 MiB; 113 cost about 1341 MiB.
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

Checks: `VideoHistoryCursorTest` covers warm-up, wrap and ages 0–112. The Vulkan smoke
`four.stereo-video.carousel` exercises actual P010 copies, ten-bit precision, eight delayed
outputs, independent exposures/cadence, cylindrical geometry and seek/refill. The existing
`four.stereo-video.precision` still checks the ordinary one-screen path.
