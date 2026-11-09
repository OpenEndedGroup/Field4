# Explainer presentations

Marc, 2026-11-09: build Bartosz Ciechanowski's three explainers, [Sound](https://ciechanow.ski/sound/),
[Cameras and Lenses](https://ciechanow.ski/cameras-and-lenses/) and
[Lights and Shadows](https://ciechanow.ski/lights-and-shadows/), as three presentations in Field.
The interactive figures are boxes, the linking text is slides, and still images are used where
nothing needs to move. The work is meant to shape and generalise Field's presentation and teaching
engine; how the material reaches the public site comes later.

The essays are all rights reserved and publish no code. We read them for their structure and their
physics only: which ideas come in which order, and what each figure lets a reader do. Every word on
a slide and every figure is written in Field from the physics. No prose, picture or code of his is
copied, and every deck credits him by name with a link (see [Conventions](#conventions-every-deck-shares)).

This page has five parts: what the engine lacked, the API that closes the gap, the figure tables
for the three decks, the export design, and the brief the three deck agents build from.

---

## 1. What the engine had, and what it lacked

Field has two teaching systems, and they share one step model.

- **Interactive guides** (`slideShow { step { ... } }`, `afield/ui/slides/SlideShow.kt`,
  `public/docs/interactive_guides.md`): steps over the live canvas, each with a note anchored to a
  box, `selectBox`/`selectBoxByName` with a highlighted line, annotation lines to boxes and editor
  lines, `{{templates}}` filled from box properties and variables, `#custom:` link callbacks,
  `onEnter`/`onExit`.
- **Presentations** (`presentation { ... }.present()`, `Presentation.kt`, `PresentationSlides.kt`,
  `AudienceBuilder.kt`, `LessonCheckpoint.kt`, `public/docs/presentation_studio.md`): the same
  steps, each carrying an audience surface for a separate Electron window: kotlinx.html or
  Markdown slides at 1920 × 1080, cues with fades and layered builds, sections and a searchable
  presenter outline, `audienceWebpage`, `audienceYouTube`, `audienceDocument` (a whole live
  document, with its macros and guide), and `checkpoint` + `LessonCheckpoint` to restore box
  source.

The gaps, checked against the code:

| | expected gap | finding |
| --- | --- | --- |
| a | live box outputs, with controls, inline beside prose | **Confirmed.** An HTML slide is rendered into a sandboxed, script-less iframe (`presentation-audience.js`), so box output, which needs scripts and the websocket, cannot run in it. `audienceDocument` shows a whole document (canvas, every box, editor) as one native view. There was no way to show one box's output, or two, beside prose. |
| b | steps that set a figure's parameters | **Partly there.** Any `onEnter` can run Kotlin, and `applyControlChanges` (ControlPresets.kt) already moves a live control and runs its callback for recordings and MIDI. But nothing public set a control by label, a cue's `onEnter` runs only on forward entry so Back left the slider where it was, and there was no glide. |
| c | state shared between figures on one slide | **Mostly a non-gap once (a) exists.** Figures are boxes; two slots can show two channels of one box, which then share its variables. Cross-box sharing is ordinary Field (properties, `prop<>`, parent boxes). What was missing was showing a chosen channel in a slot. |
| d | audio figures that play from a slide | **Confirmed, as a lifecycle gap.** `audio(signal)` returned from a launched box already plays through `FieldAudioEngine` until the box stops (`AudioReturnValue.kt`). Nothing stopped it when the talk moved on, and nothing started it when its slide came up. |
| e | authoring at scale: one document, named figures, reset | **Confirmed.** `selectBoxByName` existed for guides, but slides could not name a figure, and `LessonCheckpoint` restores *source* only: a slider the audience moved stayed moved across reruns (`_controlGroupValues`). There was no "reset this figure". |
| f | a model the site can export | **Design only.** Nothing existed. The API below keeps everything an export needs in data ([section 4](#4-export-to-the-public-site-design-not-built)). |

## 2. The API added

All of it extends `presentation { }` steps; there is no third system. Code:
`afield/ui/slides/PresentationFigures.kt`, `ControlPresets.kt` (`setControl`),
`figureMode.kt` (frontend), `electron/presentation.js`, `presentation-cues.js`,
`presentation-audience.js`. Tests: `PresentationFiguresTest`,
`tools/presentation/tests/presentation-figures.test.mjs`.

```kotlin
// In an audience { } block (AudienceBuilder):
fun AudienceBuilder.figure(name: String, vararg channels: String, height: Int = 720, caption: String = "")
fun AudienceBuilder.figureBeside(name: String, vararg channels: String, figureOnLeft: Boolean = false,
    height: Int = 800, caption: String = "", block: AudienceBuilder.() -> Unit)

// In a step or cue (SlideStepBuilder):
fun SlideStepBuilder.control(figure: String, label: String, value: Double, channel: String = "controls", glideMs: Int = 0)
fun SlideStepBuilder.control(figure: String, label: String, value: Boolean, channel: String = "controls")

// Anywhere (public box API):
fun Box.setControl(label: String, value: Double, channel: String = "controls"): Boolean
fun Box.setControl(label: String, value: Boolean, channel: String = "controls"): Boolean
```

Wire: `AudienceContent.figures: List<AudienceFigure(id, box, name, channels, rect)>`, additive with
an empty default. Frontend: `index.html?file=<doc>&figure=<box uid>[&channels=a,b][&bg=…]` shows one
box's output alone, scaled to fit.

What each piece does:

- **`figure(name)`** writes a slot (`data-field-figure`) into the slide and records the figure. When
  the slide is attached to a step its name resolves to exactly one live box in the presentation's
  document, or the Presentation box fails with a message naming it. In Electron the audience page
  measures each slot after the slide loads and the host lays a native `WebContentsView` of
  `?figure=<uid>` over it. That page is an ordinary Field client: output replay fills it, scripts
  run, sliders send their updates. A view the next slide also shows is kept, so a figure carried
  across slides keeps its state and does not reload. Figures cross-fade with the slide.
- **`control(...)`** declares a value for the view. Next, Back and jumps apply the view's values in
  source order (the step's, then each cue's up to the current one; the last value per control wins).
  Refresh does not, so the audience's own changes survive it. A control whose box is still starting
  is retried for fifteen seconds, and on a box the deck has just launched a control waits for the new
  run to draw it rather than moving the previous run's. `glideMs` eases a slider there along its own travel, so a log slider
  glides evenly in pitch.
- **Lifecycle.** Entering a view launches each figure box (and each box a `control` names) that is
  not running, unless this talk already launched it and it finished by itself. Leaving a slide stops
  what the deck launched that the next slide does not show; those run again when shown. So sound and
  animation live only while their slide is up. A box launched by hand is never stopped by the deck.
  Stopping the talk stops everything it launched.
- **Reset: figures.** A slide that shows figures and declares no `checkpoint` of its own gets the
  presenter button **Reset: figures**: it stops its figures, forgets their remembered control values
  (`Box.forgetControlValues`), launches them again, returns to the slide's first cue and applies its
  values.

Rules that follow from the implementation:

- A figure fills its slot with the box's output area at the size the box draws it, scaled to fit,
  keeping its aspect ratio. Design figures at about 640 output pixels wide (the pilot's wave is
  640 × 240 plus two controls) and they fill a `figureBeside` column.
- Builds (`fadeIn`, `reveal`) apply to prose. A figure in a retained layer stays live and undimmed.
- Figure views are separate native views: an `audienceDocument` or webpage step takes no figures,
  and figures are refused on non-HTML content.
- A figure page counts as a client of the document. Slide notes and selections sent to the document
  reach it too, but nothing of the canvas is visible in it, and it never sends its viewport.
- Slides written as `files/*.slides.json` (Markdown only) cannot hold figures yet. A Markdown
  shortcode for a figure is the natural next step if the decks want editable prose files.

## 3. The figure tables

Building blocks, by the name the tables use:

| block | in Field | use it for |
| --- | --- | --- |
| **lines** | `lines(fline { ... }, channel = ..., w = ..., h = ...)`; `editable = true` for draggable points | 2D diagrams, rays, side views |
| **controls** | `controlGroup { slider / checkbox / vec2Control / colorPicker / button }` | every parameter a reader changes; `control(...)` moves them from slides |
| **signals** | `showSignals("label" to list, x = ...)` | quantities over time or frequency |
| **audio** | `audio(SineOsc(pitch), gain = loudness)` returned from the box; `Param` for live values; a `checkbox` or hold `button` gating a gain | anything heard; `FieldAudioEngine` plays it while the box runs |
| **raster** | an ARGB `IntArray` → `display("main", PngEncoder.encode(px, w, h))` (`import afield.util.PngEncoder`) | per-pixel images computed on the CPU: sensor grids, falloff maps, pressure fields |
| **three** | `three { mesh(...); lines(...); camera(...) }` in a box output, orbitable by drag | 3D scenes whose geometry and per-vertex colours we compute |
| **loop** | return `loop { ... }` for an animation | anything that moves in time. A box returns one value, so a figure that both moves and sounds is two boxes sharing a property |
| **vulkan** | a box under the renderer, shown through the Electron RTT view | only where per-pixel GPU work is unavoidable; **not yet verified inside a figure view** |
| **still** | `imageBeside(...)` / `fullScreenImage(...)` with an SVG or PNG we drew | a diagram nobody needs to change |

Figure names are the box names (`snake_case`, described below). Numbers follow the essay's figures
in order as we read them; several small figures are merged into one row.

### Sound (document `explainer_sound`)

| # | figure | what the reader does | block |
| --- | --- | --- | --- |
| 1 | `keyboard`: a three-key instrument | press keys (mouse or W/E/R) to hear tones | audio + controls (a hold `button` per key gating a gain) |
| 2 | `gas_cube`: molecules crossing a small cube | rotate; time-speed slider | three + loop (CPU particle step) + controls |
| 3 | `collisions`: molecules flash on impact | time speed | three or lines + loop |
| 4 | `box_walls`: walls glow where hit | watch | lines + loop |
| 5 | `pressure_box`: resize the box, collisions per area per time | box-length slider; plot of the rate | lines + loop + signals + controls |
| 6 | `scale`: small cube against one 20× larger | scale slider | three + controls |
| 7 | `one_molecule`: one molecule's path in a big volume | time speed | lines + loop |
| 8 | `tube_plate`: a plate in a tube pushes slices of air | plate-position slider | lines + loop + controls |
| 9 | `tube_side`: compression red, rarefaction blue | watch | raster or lines + loop |
| 10 | `plate_jumps`: three keys move the plate different distances | press keys; hear pops | audio + lines + controls |
| 11 | `repeated_jumps`: repetition rate and distance | two sliders; hear it | audio + lines + controls |
| 12 | `wave_synth`: three keys, a plot each, and their sum | press keys | audio + signals + controls |
| 13 | `combined_key`: a key that plays the sum of two others | press | audio + signals |
| 14 | `triangle_params`: amplitude, frequency, phase of a triangle wave | sliders | signals + controls |
| 15–16 | `triangle_amplitude`, `triangle_frequency`: same shape, louder or higher | press keys | audio + signals |
| 17 | `triangle_sum`: a shape built from four triangles, amplitude bars | step slider; choose example | signals + controls |
| 18 | `phase_cancel`: equal tones a quarter cycle apart | press keys | audio + signals |
| 19 | `draw_wave`: draw one period, hear it repeat | draw; press keys | lines (`editable`, `drawing`) + audio (a wavetable read in the audio graph) |
| 20 | `circle_sine`: a point on a circle traces a sine | radius, speed, start sliders | lines + loop + controls |
| 21 | `sine_sweep`: frequency and amplitude of a pure tone | sliders; hear it | audio + lines + controls (**the pilot's `sine` is this figure**) |
| 22 | `sine_keys`: pure-tone keyboard | press keys | audio + controls |
| 23 | `beats`: two close frequencies pulse | press keys | audio + signals |
| 24 | `sine_sum`: sines building a chosen shape | choose shape | signals + controls |
| 25 | `fourier`: draw a shape, 64 sines approximate it | draw | lines (`editable`) + signals |
| 26 | `square_partials`: sines added to a square, Gibbs ripples | partials slider | signals + controls |
| 27 | `microphone`: a live spectrum | start the microphone | audio (`stereoInput()`) + signals; Electron needs microphone permission |
| 28 | `mass_springs`: one mass between springs | position slider; release | lines + loop + controls |
| 29–31 | `two_masses`, `normal_modes`, `five_masses` | mode amplitude sliders | lines + loop + controls |
| 32 | `string_modes`: first modes of a string of masses | choose a mode | lines + loop |
| 33 | `pluck`: drag to pluck a string | drag; hear it | lines (`editable`) + loop + audio (a sum of modes) |
| 34 | `fret`: the stopping point changes pitch | slider; hear it | lines + audio + controls |
| 35 | `string_density`: thickness or material changes pitch | choose; hear it | lines + audio + controls |
| 36–39 | `plate_revisited`, `pulsing_sphere`, `sphere_shells`, `slow_sphere` | frequency or speed sliders | raster (pressure field) + loop + controls |
| 40–41 | `wall_plate`, `pressure_side`: a directional source, a pressure plot | frequency slider | raster + signals + loop + controls |
| 42 | `speaker_ear`: drag the ear, see delay and falloff | drag the listener | lines (`editable` point) + signals |
| 43 | `listen_position`: keys with a draggable listener | drag; press keys | lines + audio (delay and gain) |
| 44 | `decibels`: pressure in, decibels out | type a value | controls (`stringInput`) |
| 45–46 | `doppler`, `doppler_timeline`: a moving source | velocity and time-speed sliders | lines + loop + audio + controls |
| 47 | `two_speakers`: interference at a draggable receiver | drag; press keys | raster (interference map) + audio |
| 48 | `echo`: a pulse off a wall | emit; drag the listener | lines + loop + audio |
| 49 | `reverb`: room size and reverberation | room slider; press keys | lines + audio (a feedback delay network) + controls |

Rows 43–49 came from a summary of the essay's last part; read that part yourself before building them.

### Cameras and Lenses (document `explainer_cameras`)

| # | figure | what the reader does | block |
| --- | --- | --- | --- |
| 1 | `photon_grid`: photons collecting on a detector grid; under- and over-exposure | time slider | raster + loop + controls |
| 2–3 | `colour_filters`, `bayer`: filters over detectors; the 2 × 2 pattern | watch | raster, or still |
| 4–5 | `kilopixel`, `demosaic`: a 32 × 32 sensor; interpolating neighbours | toggle stages | raster + controls |
| 6 | `exposure`: brightness against collection time | time slider | raster + controls |
| 7 | `test_scene`: red sphere, green sphere, checkerboard, bright background | drag to orbit | three |
| 8–10 | `bare_sensor`, `scatter`, `many_directions`: why a bare sensor sees nothing | orbit | three (rays as lines) |
| 11 | `pinhole_box`: hole diameter, hole-to-sensor distance | two sliders; orbit | three + raster (the sensor image, ray-traced on the CPU) + controls |
| 12–14 | `pinhole_rays`, `field_of_view`, `fov_top` | distance slider | lines + controls |
| 15–16 | `off_axis`, `cos4`: off-centre falloff | angle slider | lines + signals |
| 17–19 | `two_points`, `confusion_grid`, `dim_aperture`: blur against hole size | diameter slider | lines + raster + controls |
| 20 | `pinhole_focus`: which object is sharp | choose | three + raster |
| 21–22 | `glass_stick`, `slab_ray`: a stick behind glass; one ray through a slab | thickness and source sliders; orbit | three (or lines in side view) + controls |
| 23–29 | `sine_wave`, `em_field`, `into_glass`, `oblique`, `wavefronts`, `spherical`, `wave_rays`: waves slowing in glass, refraction | play and pause | lines + loop (each with a `playing` checkbox) |
| 30–31 | `snell`, `fresnel`: refraction angle; reflection and total internal reflection | angle slider | lines + signals + controls |
| 32 | `wedge`: non-parallel faces | face-angle slider | lines + controls |
| 33 | `prism_segments`: N flat segments approach a curve | segment-count slider; side and top views | lines (two channels) + controls |
| 34 | `thin_lens`: a movable source, the focus and focal length | drag the source | lines (`editable` point) |
| 35–37 | `lens_camera`, `two_points_lens`, `focal_length` | lens-to-sensor and focal-length sliders | lines + raster + controls |
| 38–41 | `two_spheres`, `bokeh`, `moving_light`, `cone_angle`: circles of confusion | sliders | lines + raster + controls |
| 42–44 | `aperture`, `iris`, `polygon_bokeh` | aperture and blade sliders | lines + raster + controls |
| 45 | `depth_of_field`: aperture, focal length, object position; focus and near and far limits | three sliders | lines + controls |
| 46–47 | `f_stops`, `stopping_down` | slider | lines, or still |
| 48 | `spherical_aberration`: blur by lens radius | radius slider | lines + controls |
| 49–50 | `white_slab`, `dispersion`: white light through glass; axial chromatic aberration | angle and dispersion sliders | lines + controls |

### Lights and Shadows (document `explainer_lights`)

| # | figure | what the reader does | block |
| --- | --- | --- | --- |
| 1 | `opening_scene`: a rectangular light over a surface | position and size sliders; orbit | three (per-vertex irradiance computed on the CPU) + controls |
| 2 | `sphere_light`: emitted power, photons as rays | power slider (watts) | three + controls |
| 3 | `perceived`: brightness against power, saturating to white | power slider | signals + raster swatch + controls |
| 4 | `matte_surface`: diffuse reflection beside a bright light | orbit | three |
| 5–6 | `light_angle`, `tilted_patch`: the cosine factor; rays that miss | altitude and distance sliders | three (rays) + lines (side view, second channel) + controls |
| 7 | `normal_arrows`: irradiance against angle of incidence | angle slider | lines + signals |
| 8 | `lit_sphere`: shading from the cosine factor | light direction (`vec2Control`) | three |
| 9–10 | `distance_rays`, `enclosing_sphere`: the inverse-square law | distance or radius slider | three + controls |
| 11 | `rect_light`: a finite light's shading | orbit | three |
| 12–14 | `car_angle`, `radians`, `distant_car`: apparent size, arc length, small angles | car position or distance slider | lines (two channels: top view, eye view) + controls |
| 15–17 | `projected_rect`, `steradian`, `receding`: solid angle | position or distance slider | three + controls |
| 18–19 | `hemisphere`, `projected_outline`: what a point on a surface sees | light position | three + controls |
| 20–21 | `constant_radiance`, `receptor_cone`: brightness that does not fall off | distance slider | three + controls |
| 22–23 | `lambert_lobe`, `lambert_side` | viewing-angle slider | lines + three |
| 24–26 | `mirror`, `rough_mirror`, `view_direction`: specular and glossy lobes | light offset and view sliders | lines + controls |
| 27–28 | `lambert_surface`, `lambert_in_out` | watch | three, lines |
| 29–30 | `soft_shadow`, `shadow_hemisphere`: sharpness against source size, power held fixed | altitude and size sliders | three (CPU visibility sampling per vertex) + controls |
| 31–33 | `light_colour`, `additive`, `nine_tiles`: coloured light, mixing, absorption | `colorPicker` | three + controls |
| 34–36 | `red_wall`, `bounce_slice`, `per_bounce`: indirect light | power and bounce-count sliders | three (CPU radiosity on a coarse mesh) + raster slice; vulkan only if the CPU solve is too slow |
| 37–39 | `tiny_lights`, `sky_sphere`, `occluder`: light from a whole sky | radius slider; tile position | three + controls |

## 4. Export to the public site (design, not built)

Everything an export needs is already data on the built talk:

- each step and cue has its audience HTML (`SlideShowInstance.presentationState().content.html`),
  its `figures` (box uid, name, channels) and its control values (`SlideStep.controls`, cumulative
  per view exactly as the engine applies them);
- each figure renders alone at `index.html?file=<doc>&figure=<uid>&channels=...`, the page the
  audience shows, which is what a capture should photograph.

An exporter walks the views in order: apply the view's controls, wait for the figures, capture each
figure page with the existing capture browser (PNG; MP4 for a `loop` figure; for an `audio` figure,
render its patch offline to WAV with the `wav-file` sink, `writeStereo16`), and write Markdown: the
slide's prose, then each figure as an image, video or audio player in its slot's place, with the
credit line at the top. Two things to settle then: whether each step becomes one section (likely)
and how cues fold (likely the last view of each step, plus captures of the intermediate views where
a cue moves a control). Nothing here changes the deck format.

## 5. The brief for the three deck agents

### Read first

- `public/docs/presentation_studio.md`, section **Live figures beside prose**, and this page.
- The pilot, `dev_docs/agents/transcripts/explainer_sine_pitch.yaml`: one figure box (`sine`) and
  one deck box (`deck`). Copy its shape.
- `/Users/marc/.claude/projects/-Users-marc-field4/memory/field-box-idioms-from-marc.md` for how box
  code reads: the box is the implicit receiver, `thisBox` only when you must hold it, no atomics,
  `showSignals` for anything over time, `16 { i -> }` for lists, and the shortest figure that works first.
- The essay itself, for structure only.

### Conventions every deck shares

- **One document per deck**, named `explainer_sound`, `explainer_cameras`, `explainer_lights`,
  shipped as one operation transcript of the same name in `dev_docs/agents/transcripts/` and
  registered as `uv run python tools/check_transcript_registration.py` asks (index heading,
  `COMPILE_ONLY` in `DemoTranscriptExecSmokeTest`, since decks open Electron windows and most play sound).
- **Boxes:** one `deck` box holding the `presentation { }`, and one box per figure, named with the
  figure-table name (`snake_case`, unique in the document). Stable ids `es_` (sound), `ec_`
  (cameras), `el_` (lights) plus the name. End the deck with `.arrangeBoxes().present()`: running
  `deck` puts it and every figure box in one row in slide order, each clear of the previous one's
  output panels (**Arrange Boxes by Presentation**, `PresentationArrangement.kt`). The frames in
  the transcript are only where boxes start: a grid of 160 × 50 frames whose outputs are 640 px
  wide piles the outputs on top of one another.
- **Sections** follow the essay's section order, `section("Air") { ... }`, with step ids in
  `kebab-case` that stay stable across edits.
- **First slide:** the title, two or three sentences of our own on what the talk covers, and the
  credit line in exactly this form, as `p(classes = "muted") { +"..." }`:
  `After “<Essay title>” by Bartosz Ciechanowski, ciechanow.ski/<slug>. Words and figures made in Field.`
  The **last slide** is a short "Further reading" of our own choosing plus the credit with the full URL.
- **Slides:** one idea each. Linking prose in `audience { +"""...""".md }`; a figure slide is
  `figureBeside("<figure>") { prose }`, figure on the right; at most two figures on a slide
  (`div(classes = "columns") { figure(...); figure(...) }`). Figure slides use `##` headings.
  Keep the default black theme: no deck stylesheet unless all three adopt the same one.
- **Figures:** output about 640 px wide; cyan `vec(0.4, 0.9, 1.0, 1.0)` for the primary trace,
  orange `vec(1.0, 0.6, 0.2, 1.0)` for a second, white at 35 % alpha for guides. Sliders are named
  for the quantity in plain words (`frequency`, not `f`). Put controls in the `controls` channel and
  the picture in a named channel (`wave`, `scene`, `side`).
- **Sound** never starts by itself. A figure that sounds has a `sound` checkbox (or hold buttons)
  and gain 0 until the reader or a cue (`control("x", "sound", true)`) turns it on. Every step that
  shows a sounding figure and should be silent declares `control("x", "sound", false)`, so Back is
  silent too. Keep gain at or below 0.15.
- **Cues** move controls (`control(..., glideMs = 1200..2000)`) rather than replacing the slide.
- Every view a cue changes has a `markdownNote` telling the presenter what Next will do.

### Capture recipe (macOS, this checkout)

1. Build and start Field on one of your ports with a disposable workspace:
   `PORT=<port> AFIELD_SKIP_WORKSPACE_PERSIST=1 ./gradlew runJvm --args="--workspace <WT>/.nightly-tmp/ws"`.
2. Create the document and run the transcript through the backdoor:
   `uv run --project . --group backdoor python tools/field_backdoor.py --json --port <port> --attach-existing --document <doc> --command 'open <doc> --create --keep-running' --command 'macro write <doc> --file dev_docs/agents/transcripts/<doc>.yaml' --command 'macro run <doc>'`.
   Run each figure box on its own first (`launch <id>`, `wait-output <id> --kind display`,
   `screenshot-box <id> <png>`, `stop <id>`) and look at the picture.
3. `./gradlew electronInstall` once, then start this checkout's Electron with a CDP port, recording its PID:
   `cd electron && APP_URL='https://localhost:<port>/index.html?file=<doc>' ./node_modules/electron/dist/Electron.app/Contents/MacOS/Electron --remote-debugging-port=<cdp> . &`.
4. Run the deck box again (`run <deck id>` through the backdoor) so `present()` reaches the Electron window.
5. Drive the presenter over CDP: evaluate `window.fieldPresentation.action("next")` in the
   `presentation-control.html` target. **Bring the audience forward before each capture**
   (`window.fieldPresentation.action("show-audience")`): while the presenter window covers it, macOS
   stops compositing the covered figure views, and a capture shows the slide with an empty slot.
6. Capture the real composited window with `screencapture -x -o -l <CGWindowID> out.png`, where the
   window id of the "Field audience" window comes from `CGWindowListCopyWindowInfo` (a ten-line
   Swift lister, compiled with `swiftc`, is enough). A CDP `Page.captureScreenshot` of the audience
   target does **not** include the figure views; never composite the pieces yourself.
7. Stop what you started: `kill -INT` the Electron PID you recorded and the Field JVM listening on
   your port, then check both ports are free.

### Recommended split

Each agent owns one deck end to end: transcript, figure boxes, slides, captures, and its own table
above if a figure changes. All three start from the pilot.

- **Sound** (largest, most audio): also owns any engine work audio figures need (hold-button
  gating, microphone permission in Electron, a wavetable for drawn waves).
- **Cameras and Lenses**: also owns the CPU ray-tracing helpers for pinhole and lens images
  (raster), and proving that `three` figures orbit correctly inside a figure view.
- **Lights and Shadows**: also owns the CPU irradiance and visibility helpers for `three` meshes,
  and, only if a figure needs it, proving the Vulkan RTT view inside a figure view.

Engine changes go in small commits with a test on the deck branch, and are reported back so the
other two can pick them up.
