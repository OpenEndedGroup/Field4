# video_two — a carousel of time

Eight curved stereo screens face inward around the origin. One `StereoVideoSource` owns the
movie, transport and active decode stream. Each published P010 image is copied once into a
bounded GPU ring. The screens select progressively older pictures from that ring and own
independent temporal accumulators, paired-exposure clocks and Gaussian caches.

The default spacing is **sixteen published pictures**. Box 2 changes it from 1 to 16: eight screens
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

## Independent layouts and opacity

Every material box has a **screen layout** group: an angle offset, a radius override, a sector-fill
override and screen opacity. Layout sliders have broad signed ranges: radius and centre coordinates −50 to +50 metres,
angle offsets −1440 to +1440 degrees, and sector fill −16 to +16. **Follow global radius** and
**Follow global fill** link each screen to the ring; dragging a local slider makes an override. Overrides belong to the
screen number, so its delayed picture and appearance move with it.

Angle adds to the global ring rotation and the screen's evenly spaced sector. Radius is an
absolute distance from the ring centre. Fill is width in units of one sector: one joins
neighbours, values above one overlap them, and large values can wrap through multiple turns.
Negative radius crosses to the opposite side; negative fill mirrors geometry; zero collapses
it. Global layout controls use the same expanded slider ranges. Invalid or
unrepresentable geometry is rejected atomically, retaining the previous layout.

For example, set one screen to radius 2 / fill 0.5, and another to radius 5 / fill 2, adjusting
their angle offsets until they overlap. The larger screen sits behind the smaller one. Radius
also changes physical width and height at the same angular fill. Opacity blends the foreground
with the pictures behind it; it remains independent of the zoetrope's brightness envelope.
New appearance keys include opacity. Layout overrides persist with their own boxes and are not
changed by appearance recalls.

Screens draw back to front using the mean centre depth in this frame's two eye views, with
stable screen-number ties. Both eyes use the same order and the Field pane draws last. This is
whole-screen painter sorting: intersecting or self-wrapping surfaces remain an approximation.
No previous pose, frame of buffering or depth attachment is introduced.

```kotlin
carousel.editLayout {
    screenLayouts = screenLayouts.mapIndexed { i, old ->
        when (i) {
            0 -> old.copy(radiusMeters = 2f, fill = .5f)
            1 -> old.copy(radiusMeters = 5f, fill = 2f, angleOffsetDegrees = -45f)
            else -> old
        }
    }
}
carousel.surfaces[0].opacity = .65f
```

## Shared screen looks

Box **0 · Shared screen looks** captures one screen into a common, native Field keyframe pool.
Choose **Capture from screen**, enter a name and press **Store named look**. Each material box
also has **Store from this screen**. The initial look is seeded as **slow blur** once; later
launches retain the saved key. Storing the same name adds a version.

Keys contain opacity, accumulation black/white/gamma, sharp colour transfer, input/output shadow cooling, local auto-exposure mix, mix, half-life, incoming weight, cadence enable/rate, soft-half blur
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
Ring geometry, per-screen layout overrides, frame spacing and spin remain separate from appearance keys. Stop active scores when
tuning manually, since scores can continue writing appearance parameters.

From code, indices are zero-based and the exported properties resolve through the box graph:

```kotlin
screenLooks.store("slow blur", screen = 0)
screenLookRecall.recall(
    "slow blur",
    screens = setOf(0, 2, 7),
    withSpeed = false,
    delaySeconds = .2,
)
screenLookRecall.cancel()
```

The shared-look box ticks the bounded scheduler; it loads each recalled key once and keeps at
most one pending arrival per selected screen. No movie seek or GPU readback is involved.

## Accumulation presentation levels

Each screen's material box has an **accumulation presentation** group with black level, white
level and presentation gamma sliders, plus **Reset presentation levels**. Defaults are 0, 1
and 1. Levels change the displayed accumulated image; they never write into its running mean,
reset an exposure or change the selected movie frames.

Black/white normalization acts on linear-light RGB after the held/blurred exposure is selected:

```text
normalized = clamp((accumulatedRGB - black) / (white - black), 0, 1)
presented = normalized ^ (1 / gamma)
```

Gamma above one brightens midtones; below one darkens them. White below black reverses contrast.
Coincident endpoints use a tiny signed span, producing a stable near-threshold instead of NaNs.
The neutral triplet bypasses this calculation exactly, including for values above one.

Order: accumulated/blurred image → global pre-exposure gamma → black/white levels and per-screen
presentation gamma → paired soft-half darkening → sharp/accumulated mix
→ headset-clock brightness envelope → opacity blend with the screens behind. The levels also shape
the colour reference when sharp colour transfer is enabled. Output transfer
encoding and the native ten-bit video decode remain separate.

All three values are stored in new screen looks, including staggered recalls. Earlier keys that
lack them retain the current levels. The controls mirror recalled values, and their ranges are
black/white −2 to +4 and gamma 0.05 to 8. The Reset button changes just these three parameters.

```kotlin
val screen = carousel.surfaces[0]
screen.accumulationBlackLevel = .04f
screen.accumulationWhiteLevel = .7f
screen.accumulationGamma = 1.5f
```

Manual levels and the global pre-exposure gamma share the three-`vec4` `ScreenAppearance` uniform.
No additional RTT, readback, history image or queued frame is introduced. The GPU regression
checks the transfer curve, sharp bypass, inversion, equal endpoints and neutral recovery of the
original accumulated pixels.


## Sharp colour from accumulation

Every material box has a **Sharp colour from accumulation** slider in **accumulation presentation**.
It borrows the processed accumulation's chromaticity at each pixel while retaining the sharp
video's original linear luminance and spatial detail. This makes sharp screens share the warm
palette of their blurred neighbours. Zero preserves the original sharp colour; one transfers the
reference chromaticity. Intermediate values blend colour without blending away luminance detail.

The reference is the same held/blurred image used by the accumulation presentation, after global
pre-exposure gamma, black/white exposure and per-screen presentation gamma, before soft-half
darkening. Matching occurs before the ordinary sharp/accumulated mix, headset brightness envelope
and opacity. **At accumulation mix 1 the display is entirely the processed accumulation, so the
new slider changes only the sharp contribution when that mix comes down.**

The operation uses linear BT.709 luminance, `Y = dot(RGB, vec3(.2126, .7152, .0722))`. It scales
reference RGB to the original sharp Y, then compresses chroma toward the same-Y grey where needed
to fit the SDR RGB gamut. It does not clip channels independently, which would change luminance.
Nearly black references fade back to the sharp image smoothly because black has no reliable hue.
Pure white remains white; adding saturation there would require either lost luminance or HDR headroom.

Colour transfer keeps this screen's existing accumulation and cadence/blur caches running even
when **Accumulation mix is zero**. A sharp-only screen can therefore borrow a continuously updated
colour reference; the GPU meter can also read that buffer. This reuses the same passes and decoder,
with no additional kind of RTT or headset-frame history. Turning both mixes to zero bypasses the
accumulation work. New native look keys include the colour-transfer amount; older keys retain the
current value when the lane is absent.

```kotlin
val screen = carousel.surfaces[0]
screen.accumulationColourTransfer = .8f
screen.accumulationMix = 0f // sharp luminance/detail with processed accumulation colour
```

`four.stereo-video.colour-transfer` exercises the actual decoded video and presentation shader
with a controlled GPU accumulation: partial/full mixes preserve luminance across the ramp,
highlights stay in gamut, black references are safe, and sharp-only accumulation stays alive.


## Input and output shadow cooling

Each screen has two independent controls, both neutral at zero and included in new look keys:

- **Input shadow cooling**, in **screen exposure**, applies to decoded linear RGB before the
  incoming picture enters temporal accumulation. Existing history is retained; only new
  contributions carry the new tint. Its effect follows incoming weight and half-life. While
  paused, **Clear this exposure** rebuilds from the held frame with the current input setting.
- **Output shadow cooling**, in **output colour**, applies after sharp/accumulation blending and
  the brightness/flash envelope, immediately before opacity/output. It updates immediately,
  including on paused material, and does not feed back into metering or accumulation.

Both add a blue/cyan chroma direction with zero linear luminance. Shadow weight is full below
`Y = 0.015`, fades with a smoothstep and reaches zero at `Y = 0.25`; brighter pixels are
unchanged. Same-luminance chroma compression avoids clipping RGB channels, and almost-black
pixels have a smooth safe fallback. These are colour controls rather than brightness changes.

Input cooling affects the accumulation seen by the global gamma, exposure meter and optional
sharp colour transfer. Nonlinear gamma/exposure can consequently respond to that new colour.
Output cooling is the last material colour operation and has no such feedback. It evaluates the
actual post-brightness luminance, so the flash envelope can move pixels into or out of the
shadow range. The original sharp input is affected by input cooling only when it borrows
colour from the accumulation.

```kotlin
val screen = carousel.surfaces[0]
screen.accumulationShadowCooling = .4f // new contributions; previous history remains
screen.shadowCooling = .25f // final output, immediately
```

Input uses the spare `weights.z` push-constant component in the accumulation shader. Output
uses `ScreenAppearance.outputTone.x`; the uniform is now 48 bytes. Both are shader arithmetic
in existing passes: no new RTT, readback or queued headset frame. GPU checks cover luminance,
highlight neutrality, output ordering, retained input history, clear and neutral bypass.

## GPU auto exposure

**Shared auto exposure** meters every valid accumulation buffer in linear light, including both
eyes. Choose **Auto: per screen** for independent levels or **Auto: linked screens** for shared
levels. **Manual levels** bypasses automatic endpoints. Both the meter and automatic grading
start off, preserving the saved look. The controls also work while the renderer is stopped:
settings remain editable and bind to the carousel when it starts. Accumulation must have been
enabled to create a buffer.

**Global pre-exposure gamma** is a shared power curve applied before exposure normalization:
`RGB = max(RGB, 0)^(1/gamma)`. One is neutral, above one lifts midtones, and below one darkens
them. It acts on every accumulated screen, including in Manual mode, and on the sharp colour
reference when colour transfer is enabled. Stored accumulation history is untouched. Each screen's existing **Presentation gamma**
remains a separate control after black/white normalization.

The meter applies this same power curve to each RGB texel *before* reducing RGB and luminance.
It measures the unblurred accumulation, while presentation applies the curve after choosing the
held/blurred image. Gamma is part of the global saved settings, rather than individual look keys.
Changing gamma invalidates cached reductions even for paused buffers, and seeds the trackers in
the new domain at the next enabled-meter frame. A disabled meter retains its frozen endpoints.
No extra RTT or queued frame is required.

The GPU reduces RGB and luminance to min, mean and max. Linked mode pools the raw extrema and
pixel-weighted means before filtering. Hidden screens and held/frozen buffers participate while
their accumulations remain valid. Measurements follow global pre-gamma and precede exposure
normalization, paired blur/darkening,
flashing and opacity, so the exposure never measures its own correction.

### Optional quantile endpoints

In **quantile endpoint sketch**, enable **Use quantile endpoints** to derive exposure from
**Shadow quantile** and **Highlight quantile**, rather than absolute minimum and maximum.
Defaults are the estimated 2nd and 98th percentiles. The meter must be updating and an Auto mode
selected to affect exposure. Turning the checkbox off returns to min/max; neither choice changes
accumulation pixels. The original min/mean/max table remains visible for comparison.

This uses the actual `fieldStatsHere` GPU implementation: **64 centroids** over **4,096 selected
luminance pixels per valid screen**, plus a further 4,096 samples distributed across the valid
same-sized buffers for linked mode. The deterministic sample positions span both eyes and use the
global pre-exposure gamma. Sampling only occurs at the existing meter cadence when an image,
gamma or valid-source set changes. Held images reuse their sketch; changing quantile cutoffs
reuses the centroids and resets the trackers without rescanning pixels.

One active lane per workgroup records each selected pixel. FieldStats' subgroup reduction thus
sees one pixel per partial, rather than averaging unrelated pixels before building its sketch.
The result is still a bounded estimate: neither the 64-centroid sketch nor the 4,096-pixel sample
is an exact pixel distribution. Even cutoff 0 or 1 returns a sketch endpoint; use min/max mode
for true extrema. Large black regions still legitimately occupy the lower quantiles.

The low quantile, median and high quantile have independent directional alpha-beta levels and
velocities. Padding, bounds, maximum gain and global/local auto mix apply afterwards, exactly
as for extrema. No FieldStats EMA slots are used. Invalid or overflowed sketches fall back to
extrema; the status table reports valid sample counts and dropped partials. The generic
FieldStats CPU snapshots are disabled: the existing small, nonblocking telemetry copy carries
the values needed by the UI. No image or sample cloud is read back.

For eight screens the fixed partial storage is about **3.4 MiB**. The reset and reduction passes
are shared across channels; min/max mode performs no sketch dispatches. **Extra GPU sketch cost**
reports sparse timestamps around just the extra sampling/reset/reduce passes, with invocation
counters disabled. Compare it in the live scene rather than inferring cost from the buffer size.

```kotlin
carousel.autoExposure.settings = carousel.autoExposure.settings.copy(
    endpointSource = VideoAutoExposure.EndpointSource.QUANTILES,
    lowQuantile = .05f,
    highQuantile = .95f,
)
val comparison = carousel.autoExposure.latest?.linked

/*
rawQuantiles / quantiles contain low, median, high, validity.
quantileVelocities.xyz contains the corresponding rates (w is reserved).
sketchCount and droppedSketchPartials describe the sampled distribution, not the full image.
*/
```

### Directional tracking and bounds

Each statistic/channel keeps its own level and velocity. Two **alpha-beta pairs**, one rising
and one falling, use the recurrence from Field's `AlphaBetaFilter`:

```text
predicted = level + velocity * dt
residual = measurement - predicted
level = predicted + alpha * residual
velocity = velocity + beta * residual / dt
```

A measurement's direction selects the pair; when unchanged, the residual selects it so the
tracker can settle. Gains apply per meter sample (default 12 Hz), rather than per headset frame.
Between samples the GPU predicts from velocity for up to one sample interval. **Velocity cap**
optionally limits that velocity; zero leaves it free. **Reset exposure trackers** seeds from the
next valid measurement and clears velocities, without clearing any accumulation.

Black is the predicted low statistic minus **Black padding**; white is the predicted high statistic plus
**White padding**. Separate black/white bounds and **Maximum contrast gain** constrain these
endpoints even during prediction overshoot. Positive padding reserves headroom/footroom;
negative padding clips more. Mean is tracked and available to code; both gamma controls stay manual.

**Global auto mix × each screen's Auto exposure mix** blends automatic endpoints with manual
black/white. Local mix belongs to screen looks, including staggered recalls. Global mode,
pre-exposure gamma, quantile selection/cutoffs, filter gains, bounds and meter rate persist together in the shared box's settings property.
Turning **Update GPU meter** off freezes existing statistics and endpoints; select **Manual
levels** to stop applying them. Missing/invalid measurements fall back to manual endpoints.

```kotlin
import afield.graphics.scene.video.VideoAutoExposure

carousel.autoExposure.settings = carousel.autoExposure.settings.copy(
    enabled = true,
    mode = VideoAutoExposure.Mode.LINKED,
    preExposureGamma = 1.25f,
    risingAlpha = .25f,
    risingBeta = .05f,
    fallingAlpha = .1f,
    fallingBeta = .01f,
    blackPadding = .01f,
    whitePadding = .02f,
    minimumSpan = .05f, // maximum contrast gain = 20
)
carousel.surfaces[0].autoExposureMix = .5f
carousel.autoExposure.reset()
```

The settings controls validate edits atomically and report rejected combinations. When setting
`settings` directly from another box, update the shared box's `videoExposureSettings` JSON too
if that change should survive reopening; the controls already do this automatically.

Changed images are scanned at the meter cadence; paused buffers reuse their previous reduction.
Small finish passes predict bounded endpoints before the current scene draw. Filtering and
grading remain GPU-resident: no CPU wait or extra rendered frame is needed. A single-flight
snapshot of the small statistics buffer updates the markdown table at most four times per
second after existing graphics completion; it is telemetry only, never the exposure source.
The presentation uniform contains three `vec4`s (including the output-toning controls); the existing 128-byte draw push block is unchanged.
The small statistics record now contains sixteen `vec4`s per screen/linked row, including quantile
levels, velocities and sketch diagnostics.

Verification covers finite pixel counts and non-aligned image sizes, pooled statistics,
directional tracking against the existing CPU filter, prediction, frozen-image scan reuse,
bounds, invalid buffers, settings persistence, robust quantiles in the presence of outliers,
cached sketch reuse, extrema switching and the actual presentation shader's auto mix.
The smoke IDs are `four.stereo-video.auto-exposure`, `four.stereo-video.precision` and
`four.stereo-video.carousel`.


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
Slots allocate as history fills. Resizing retains already allocated image pairs up to the largest
filled ring in this session, reusing them on subsequent edits; closing or replacing the source
releases them through the graphics retirement queue. Each enabled full-resolution accumulator adds about 63 MiB,
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
resizing retains a valid current picture while the new ring refills, and sampling clamps to the
active ring capacity if a UI request changes during that frame. Use the live graph and
inherited `prop` values; do not construct a decoder in a screen box.

Checks: `VideoHistoryCursorTest` covers warm-up, wrap and ages 0–112. The Vulkan smoke
`four.stereo-video.carousel` exercises actual P010 copies, ten-bit precision, eight delayed
outputs, independent exposures/cadence, cylindrical geometry and seek/refill. The existing
`four.stereo-video.precision` still checks the ordinary one-screen path.
