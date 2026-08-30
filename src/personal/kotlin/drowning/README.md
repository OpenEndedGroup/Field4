# `drowning` — streaming geometry readers for Field

## Production cue integration (`dust_three`)

`DrowningCue` reads and validates one `splat2-vr-circle-mosaic-v2` directory. It makes the 15 cell
transforms, atlas rectangles, disparity/flow decoders and inverse-depth calibration available from one
object. `DrowningCue.writePalette` writes the same data as a 15 × 14-`vec4` std430 buffer for live GLSL.

`DrowningTransport` is both the global splat/MXG playhead and a `PlaybackClock` for `VideoTexture`; its
15 frame offsets align per-cell resources without creating 15 unrelated clocks. `MxgCueBank` performs
random-access decoding of all 15 MXGs on one worker and publishes complete circles atomically.
`SplatCueBank` reads the 15 compressed PLYs in parallel and also transforms/sorts/packs the combined
circle on its coordinator thread. The renderer only copies the resulting packed buffer (about 0.5 ms
for the example pack) rather than doing a 20–40 ms pack inside `record_gfx`.

`DrowningAnchors` is the small bridge from already-presented MXG frames to particle interaction. It
selects a bounded number of useful tracked nodes per cell, preserves track-to-slot identity through
short losses, and advances a generation token on slot reuse or cue wrap. Consumers can therefore fade
and reacquire rather than snapping when a track disappears. It does no file access and owns no decoder.

Data-bearing `.vrpack.mp4` files must use `VideoTexture(pixelFormat = VideoDecodeFormat.RGBA8)`. Their
R/G/B channels contain disparity and optical-flow values, so the normal P010 path would lose information
through YUV conversion and chroma subsampling. Set `backgroundDecode = true` for production atlases:
FFmpeg decode and RGB conversion then run on a worker while Vulkan upload remains on the render loop.

### Exact presentation, not just a shared clock

`DrowningPresentation` owns a two-phase barrier. Register the video and the two banks with the boxes'
upload callbacks, then schedule **only this presenter** before the main RTT (after advancing the cue
transport). After frame N presents, it immediately asks all three workers for N+1, but holds the
completed set until its source-rate deadline. That gives loading the entire display interval instead
of beginning after the frame is already due. Ordinary wall-clock advancement cannot replace useful
in-flight work; only an explicit seek or resource reconfiguration can. A genuine underrun therefore
holds the previous complete set and then continues sequentially rather than entering a deadline-chasing
skip loop. Hidden draw modules still register so re-enabling is aligned.

While this presenter owns the resources it also owns advancement of `DrowningTransport`: the public
playhead is the complete frame actually committed, not a wall clock several frames ahead of it. Score
the palette after `DrowningPresentation.render`, so an underrun cannot move transforms/effects ahead
of the video, MXG and splats. Setting `transport.position` remains an explicit seek and supersedes the
prepared set normally.

The pacer retains its ideal deadline across ordinary render-frame quantization, so a succession of
small 60 Hz misses does not turn requested 8 fps into 7 fps. A miss of a whole source interval resets
the phase rather than bursting to catch up. Short looping videos keep a second decoder cold-side: it
is primed during the first pass and alternates with the active decoder while the exhausted one rewinds
off-thread. Thus frame 123→0 does not put FFmpeg's rewind latency into the presentation cadence.

`VideoTexture.prepareFrame / isFrameReady / uploadPreparedFrame` separate background decode from
presentation. This exact path is for zero-based constant-frame-rate assets; it checks actual decoded
PTS, does not publish intermediate seek frames, and retains the requested frame until upload. Do not
also schedule the video's normal automatic update target. `MxgFrame.writeInto(command = command)`
records transfers into the presenter's command buffer instead of submitting independent uploads.

The presenter's `describe()` reports requested, pending, and actually presented source frames for
video, splats, and MXG, plus total wait, asset-ready time, deadline headroom and presentation/render
counters. Positive `leadMs` means the set was ready before its deadline; negative means an actual
underrun. Nonzero per-cell offsets are
intentional geometry offsets; the atlas itself still has one global video time. Frame-count mismatches
fail explicitly instead of silently wrapping a short geometry sequence against a longer movie.

## Maxnet `.mxg`

The MXG path is split at the renderer boundary: [MxgSource](MxgSource.kt) decodes MessagePack and
zlib records on a background thread, while the Field box copies each published frame into exactly
two vertex-pulled buffers. One editable shader pair then draws closed contours, max-tree parent
edges, and node/maxima marks as three meshes. No Vulkan object is touched off the render loop.

| file | what it is |
|---|---|
| `Mxg.kt` | indexed, append-safe reader; CRC checks and direct contour-to-segment packing |
| `MxgSource.kt` | timestamp-paced background decode, looping, following, pause and seek |
| `MxgGpu.kt` | the two bounded buffer uploads and live `MxgLook` controls |
| `MxgShaders.kt` | one monochrome vertex/fragment pair for all three draw modes |
| `MxgView.kt` | aspect-correct projection and the complete two-box Field recipe |
| `MxgMain.kt` | describe/profile/verify the stream, or dump the editable shaders |
| `~/Documents/AFieldWorkspace/maxnet_one/` | the live `renderer -> stream` Field document |

The `maxnet_one` stream box defaults to `~/temp/maxnet/output.mxg`. Run its `maxnet — renderer`
parent, then launch `maxnet — stream`; its Kotlin and editable shaders live in the document's
sidecars rather than in the personal source tree.
Its startup profile allocates exact fixed capacities for the complete records currently in the file;
growing streams can supply explicit future capacities to `MxgSource.open`.

Controls in the supplied box are `C` contours, `E` edges, `N` nodes, space to pause, and `[`/`]` to
seek. The exported `mxg` and `look` values remain live-editable from Field.

## Plane subdivision `.psub`

The `.psub` plane-subdivision format is a depth map segmented into a hierarchy of planes:
`~/Field4/dev_docs/PSUB_FORMAT.md` is the spec, `~/temp/planesubdivgame` is the producer, and
`psub.py` there is the reference reader to cross-check against.

| file | what it is |
|---|---|
| `Psub.kt` | the reader. A view over a memory mapping; nothing decodes, nothing allocates per point |
| `PsubVerify.kt` | the checks from `psub.py`, plus a camera-block one it does not make |
| `PsubGpu.kt` | four verbatim section copies into buffers the box owns, and `PsubLook` |
| `PsubShaders.kt` | the GLSL: an NPR point cloud and the plane annotations |
| `PsubView.kt` | framing helpers, **and the box recipe** — start reading here |
| `PsubMain.kt` | `psub.py` for the JVM: describe, verify, and dump the shaders to paste |
| `PsubViewerMain.kt` | a standalone window. The self-test, not the way to use this |
| `PsubPlateProbe.kt` | measures a plate's frame against its own points — `--plates` |
| `../../resources/drowning/` | the starting point's three boxes and its builder — **not** here, see below |

## Start here

The document `~/Documents/AFieldWorkspace/psub` is the starting point: three boxes, `KeyCamera`,
and keys for walking up and down the tree. Run the `window` box, then `points`, then `planes`.

Rebuild it — **two commands, in this order**, because the document embeds the GLSL and the GLSL is
generated from `PsubShaders.kt`:

```bash
java -cp build/personal/afield-personal.jar:<kotlin-stdlib> drowning.PsubMainKt --shaders /tmp/psub
```

```bash
cd ../../resources/drowning && python3 build_starting_point.py /tmp/psub ~/Documents/AFieldWorkspace/psub/metadata.edn
```

The box sources live under `src/personal/resources/drowning/`, not next to this README, and that is
load-bearing: `src/personal/kotlin` is a real Kotlin compilation, and a box snippet is not compilable
Kotlin — it leans on the box preamble for `moduleHere`, `Mesh()`, `@exported`, `thisBox`, `window` and
the `_name` inherited properties. Put one in `kotlin/` and `./field.sh` dies at
`:compilePersonalKotlinJvm` with a hundred unresolved references. Resources are copied, not compiled.

Skipping the first is a real trap and has bitten once: the document's Kotlin binds push-constant
members by name, so a shader that is one revision behind produces a box that throws
`can't find <name> in a mesh in this module` the moment it runs, while looking perfectly fine on the
page. `build_starting_point.py` now refuses to write when the Kotlin binds a member its own shader
does not declare — but it cannot catch a stale shader whose *members* happen to match, so dump first.

The first command is also how to get the GLSL anywhere else; `PsubShaders.kt` is the single source of
truth for it.

## The two boxes a record carries

`obb_u`/`obb_v`/`half_extent` is the **PCA** box — eigenvectors of the point covariance, and the frame
the producer's fit, cut sweep and `fill_ratio` are defined in. It is the wrong box to draw: it orients
by second moments, so the reference scene's back wall comes out 4.5 degrees off its own edges, and a
near-square group comes out **64 degrees** off with 1.74x the area it needs, because two near-equal
eigenvalues leave the orientation undetermined.

`NODE_RECT`/`GROUP_RECT` (sections 12, 13, added upstream for this) is the **tight** box: the
minimum-area rectangle by rotating calipers over the in-plane convex hull. `PsubLook.useTightRect`
picks between them, `R` toggles it in the starting point, and flipping back and forth is the fastest
way to see what either box is telling you.

## The split, and why

The box owns the module, the mesh, the buffers and the shaders; this package owns the file, the
packing, and the knobs. Same division as `light.*`, for the same reason: Field draws by vertex
pulling and the shader lives in the box's own editable properties, so a compiled `Module` built from
a string is a shader you cannot edit while it runs. `PsubViewerMain` does build one — deliberately,
because it is the only way to check the GLSL renders without a live Field.

## What is verified, and how

- **The reader** agrees with `psub.py` on four generated fixtures (synthetic, tiny, no-merge,
  overlap-membership): same tree, same group table, same ownership chain, and every recomputed leaf
  rms within 1e-8 of the stored one. `drowning.PsubMainKt <file>` reruns all of it and exits 2 on a
  failure, same convention as `psub.py`.
- **The shaders** compile under `glslc --target-env=vulkan1.2`, and `spirv-dis` confirms the vertex
  and fragment stages of each pair agree on all 20 (point) and 17 (plane) push-constant offsets.
- **The picture** — `PsubViewerMain` renders the 320x240 reference scene and captures it. Group,
  flags and residual colouring were each checked against what the producer reports about the same
  file.
- **The tight rectangles** are checked independently on both sides: `psub.py` and `PsubVerify` each
  rebuild every rectangle's frame from the file and confirm it contains its own points (~2e-7) and is
  never larger than the PCA box. That check caught the first version of the producer's calipers pass,
  which stored a frame rotated away from the centre it had computed and missed its points by 0.69
  units while looking entirely plausible.
- **Not verified**: the box path's generated push-constant accessors, which need a running Field.
  The two conversions they force are documented in `PsubView.kt`.

## Regenerating test files

```bash
cd ~/temp/planesubdivgame && make
./psub_demo --size 320 240 --merge --out ~/temp/psub_test/scene.psub
./psub_demo --size 96 72 --out ~/temp/psub_test/tiny_nomerge.psub          # no group layer
./psub_demo --size 128 96 --merge --overlap 0.01 --out ~/temp/psub_test/overlap.psub
```

## Particles `dust`

A GPU particle system whose **force laws are StackSL strings the CPU sends to the running shader**, not
GLSL that has to be edited and reloaded. Everything else is in service of that: an orthant neighbourhood
network so the particles are tied to each other in every direction, a two-layer multiview target so it
is stereo from the first frame, and three debug draws so you can see what the network is doing.

| file | what it is |
|---|---|
| `Dust.kt` | the layout — the bytes the CPU writes and the shaders read, and the history header |
| `DustOrthant.kt` | the orthant neighbourhood network: 8 octants per particle, in parallel, trimmed to 6 |
| `DustShaders.kt` | the GLSL. Two compute shaders, three debug draws, a blur and a grade — **start here** |
| `DustPrograms.kt` | sixteen worked force programs in StackSL, and what a program can reach |
| `DustParams.kt` | every knob, as named-argument functions returning `Vec4` — **the parameter surface** |
| `DustGpu.kt` | the writes, the ping-pong rule, the history cursor, the stereo decision, the eye basis |
| `DustReference.kt` | the force step on the CPU. Not a simulator: it pins the sign conventions |
| `DustWorkout.kt` | the whole thing checked headlessly — `FIELD_TEST drowning.dust.workout` |
| `DustMain.kt` | dump the GLSL, measure a network, print a force program's GLSL |
| `../../resources/dust/` | the eleven boxes and their builder — **not** here, same reason as psub |

### Start here

The document `~/Documents/AFieldWorkspace/dust_two` is the starting point: eleven small boxes, left to
right, one job each. (`dust_one` is the first pass, kept as a snapshot — its GLSL and box code are
inline in its own `metadata.edn`, so it still runs. Don't rebuild onto it; the boxes here have moved on.)

```
window -> stereo -> field -> forces -> history
                         \-> points
                         \-> edges
                         \-> trails
                         \-> blur -> grade -> screen
```

Run them in that order — `./field.sh ~/Documents/AFieldWorkspace/dust_one`, then the boxes left to
right — or run the whole thing at once with the macro the builder ships:

```bash
python3 tools/field_backdoor.py --workspace ~/Documents/AFieldWorkspace --document dust_two \
  --command 'macro run run-all'
```

Rebuild it — **two commands, in this order**, for the same reason as psub:

```bash
java -cp build/personal/afield-personal.jar:<field classpath> drowning.DustMainKt --shaders /tmp/dust
python3 src/personal/resources/dust/build_starting_point.py /tmp/dust ~/Documents/AFieldWorkspace/dust_two
```

The builder refuses to write when the box Kotlin binds a push-constant member its own shader does not
declare, and it deletes the document's sidecars first. That second part is load-bearing and cost two
debugging rounds to find: `#afield/file-inline` writes a `<uid>_code_.kt` beside `metadata.edn` the
first time Field opens the document, and on the *next* open **the file wins over the inline text** — so
a rebuilt `metadata.edn` is silently ignored and you debug a box you have already fixed.

### The one idea

The force compute shader has four `{{ stack.auto.* }}` slots. A box binds each to a string; Field
lowers the StackSL to GLSL, patches that module's pipeline on a background thread, and leaves the
previous binding alone if it does not compile. So this is a live edit with no reload and no way to take
the frame down with a typo:

```kotlin
forceModule.edgeForce = "input x 12 *"                              // Hooke, stiffness 12
forceModule.edgeForce = "input x =e <e <e * <e * 160 *"              // a rope: slack, then very hard
forceModule.edgeForce = "?gEdgeLen ?gRestLen - ?gHash 0.5 + * 20 *"  // per-particle stiffness
```

The boxes carry their programs **inline, as text**, not as `DustPrograms.ROPE` — for the same reason the
knobs are no longer on a class. `DustPrograms` is in the personal jar, so editing one of its constants
needs a Gradle build and a restart; the string in the box needs a Cmd-Enter. Treat `DustPrograms` as the
menu (sixteen worked programs; `DustMain --programs` prints them all) and paste from it into the box.

The setter is on the **module**, not the mesh — a `stack.auto` slot is a property of the compiled
shader and the mesh only carries push constants. `forces.edgeForce = ...` gives a receiver-type
mismatch naming both `ModulePointer` overloads, which is a confusing way to learn that.

A generated slot is a *free function*, so it cannot see any local of its caller. Everything it might
want is therefore a module-scope global it reads with StackSL's `?name` escape: `?gP ?gV ?gR ?gA
?gHash ?gTime ?gMeanLen`, plus `?gEdgeDir ?gEdgeLen ?gRestLen` inside an `edgeForce` call, plus the
whole push block (`?pc.knobA` and friends are three free `vec4`s for exactly this). No texture words —
the shader declares no sampler.

**The sign.** `gEdgeDir` points from a particle *toward* its neighbour, so a **positive** `edgeForce`
result pulls them together. Hooke's law is `input x 12 *`, not `-12`. Getting it backwards makes the
network push hardest where it is already overstretched: the field does not wobble, it detonates, and
what is left is every particle pinned motionless against the bounding box — a picture that reads as a
rendering bug. `DustReference` asserts that the shipped gain contracts a perturbed network and that the
flipped one does not; measured, `+12` takes the mean edge extension from 0.26 to 0.0005 and `-12` takes
it to 1.6e13.

### The network

For each particle, the nearest other particle in each of the eight sign-octants around it. Not k-nearest,
because k-nearest is directionally blind: a particle at the edge of a clump gets all k neighbours from
the clump side and none from the open side, so the spring network has one-sided stiffness. Every octant
is asked separately, so a particle is tied in eight directions if there is anything in them.

A link record holds six slots, and the trim keeps a particle's **own** orthant edges first, filling any
slot left over with edges other particles chose toward it. Filling from the symmetrised union instead —
the obvious thing — defeats the whole point: a particle in a dense region is *chosen* by many close
neighbours, so its slots fill with their short edges and evict its own edges toward the sparse side.
Measured on 40k uniform random particles, own-first retains 75% of the orthant edges; union-then-trim
retained 51%, and it was the long half that went.

```
$ DustMainKt --onn 40000
dust: 40000 particles in [-1.00..1.00] x [-1.00..1.00] x [-1.00..1.00], 1.92 MB of state
onn: 318756 directed edges (of 320000 octants, 234103 distinct pairs)
     -> 239998 slots: 239798 own + 200 borrowed, 119999 undirected
     retained 75% of the search, degree 6:39999, mean rest length 0.0575
     search 173 ms (parallel), trim 281 ms
```

### Where the parameters live, and why not in a class

Every knob is a **named `vec4` push member in the shader**, written from a named-argument function in
`DustParams.kt` that returns a `Vec4`, and read through a `#define` that names its lane:

```kotlin
points.dot   = { dustDot(size = 0.012f, minPixels = 0.9f, viewportHeight = h) }
forces.gains = { dustGains(edge = 1f, noise = 2.4f, rest = 0.2f, damping = 0.985f) }
```

```glsl
vec4 gains;                              // x edge  y noise  z rest  w damping
#define DUST_NOISE_GAIN pc.gains.y
```

The first pass put all of this on a `DustLook` data class instead. Typesafe, and the wrong trade twice
over:

- **Adding a knob needed a full teardown.** `DustLook` lives in the personal jar, so a new field meant a
  Gradle build and a restart of the running sketch — the opposite of live coding, at exactly the moment
  you want a new knob. A new lane plus a `#define` is one hot-reloaded shader edit.
- **It hid the parameters from the keyframer.** `KeyframeScope` binds *named values in named shaders* —
  `target("gains", forces.push)` reads and writes that member — and `KValue` persists `vec4` natively.
  A knob that only exists as a field on a Kotlin class the shader never sees cannot be snapshotted,
  interpolated or saved at all. So the same members that live-code also keyframe:

```kotlin
var keys = keyframes {
    target("gains", forces.push)
    target("bloom", grade.push)
    stackAuto(forceModule, "noiseForce")     // the force *program*, as a string
    camera(camera)
}
var calm = keys.capture()
```

The cost of grouping is exactly one kind of mistake — two `#define`s quietly meaning the same lane, so
setting one clobbers the other — and it is invisible until two parameters move together. `DustWorkout`
checks that no two lane names alias, that every lane's group is declared, and that 21 named arguments
land in the lanes the `#define`s say they do. `DustLook` is left standing, marked as what it is, because
deleting it would break `dust_one`.

### Six things that are not obvious

**Billboards need the projection, not the view-projection.** A sprite quad offset in clip space by
`q * radius * vp[0][0]` looks right and is wrong: `vp` is `projection * view`, so its diagonal is the
projection's scale *mixed with the view rotation*. Sitting still it is very nearly right; turn the
camera and the sprites shear and keep their original orientation while the scene rotates. `dust_two`
builds the quad in **world space** along the camera's right and up (`dustBillboard`), which is a real
billboard and generalises — swap `camRight` for a velocity direction and the same code draws streaks.
The projection's own `(p00, p11)` still comes across as `projScale`, for the pixel floor only. Measured
with a few large sprites: on-axis median width/height 1.000, at 45° about y 0.977; the clip-space
version gives about 0.707 at 45°.

**The ping-pong has no `swap()`.** The force shader reads `prevState` and writes `state`, because an
edge force needs a neighbour's position and a neighbour is another invocation of the same dispatch.
Which buffer is which comes from `renderer.frameCounter`, not from a `swap()` in someone's `loop` — the
counter is constant for the whole of one frame's recording, so eleven boxes' bindings cannot disagree
about it. `DustHistoryCursor` is derived the same way, offset by the frame `rebase()` was called on.

**A binding is a lambda, so set it once.** `points.dot = { … }` installs a function Field evaluates once
per draw. Setting the bindings inside a `loop {}` leaves a window between `launch` and the first tick
where the buffer-reference members are unbound, and a frame recorded in that window reports
`risk of GPU null ref \`state\``.

**A StackSL definition with the wrong arity is pruned silently.** `=name` *copies* the top (`a -- a`);
`>name` *moves* it (`a --`). A definition must consume its input and leave its result, so opening one
with `=` leaves the input under the result and every call pushes two values. StackSL emits **no
diagnostic** — it prunes the surplus and everything downstream takes the wrong operand. In the default
noise program that meant one channel of the force reading a raw *coordinate* off the stack: a constant
of about `+6.8` on one axis, so turning the gain up translated the field into a wall instead of
agitating it. Found by a person, not a test. What StackSL *does* expose is
`analysis.functions[].finalStackDepth` — `2` for that definition and `6` for its root against `1` for
both, with `prunedValues = 15`. `DustWorkout` now asserts depth 1 for every definition, prints every
severity rather than filtering to errors (a second bug was sitting in a WARNING), and reads the programs
written inline in the box *resources* — those are unchecked code otherwise, which is how the fix landed
in `DustPrograms` while the box kept shipping the bug.

**StackSL's noise words are 2D, and on a 3D cloud that shows.** `fbm` and `noise` lower to
`fbm(vec2)` over `coord.xy + coord.zw * 0.07`. Handing them `vec4(pos.xyz, time) * 2.2` — the obvious
thing — gives `(2.2·x + 0.154·z, 2.2·y + 0.154·t)`: `x` at fourteen times the weight of `z`, and `z`
entering only as a shear on the x axis. The force field is then essentially `f(x, y)`, and viewed down
z it prints a **fixed stripy pattern at every depth** — reported from the running document, in the edge
strain, where it read as structure and was flat noise. The fix is three slices,
`f(x,y) + f(y,z) + f(z,x)`, for three times one fbm; `DustPrograms.DRIFT_FLAT` keeps the old form so
the comparison stays available, and the workout asserts the default samples `.xy`, `.yz` and `.zx`
while the flat one does not. Two smaller things in the same family: `fbm`'s five octave amplitudes sum
to **0.96875, not 1**, so the usual `0.5 - 2 *` leaves a constant `-0.03` bias (a constant force
integrates into a drift rather than averaging out) — normalise by `1.032258` first; and the three-slice
sum has *cubic* symmetry, so a faint 4-fold residual in the strain is expected rather than a bug.

**A `Camera`'s projection ignores `setAspect`.** Its default `SuperProjection(renderer)` installs an
`auto` lambda that calls `resize(window.width, window.height)` inside *every* `evaluateUniform()`, so a
square eye buffer silently gets the window's 16:9 and a cube of particles comes out compressed in x by
1/1.78. `DustEyes` replaces that lambda rather than calling `resize` once, because anything that calls
`resize` once is overwritten on the next evaluation.

### Watching it, without a readback

The force shader carries `{% include "fieldProbe" %}`, and the forces box does
`fieldProbeHere(2048).display("probe")`. Its output channel then shows a live GPU-reduced distribution
of whatever the `probe` lane selects — speed, `|force|`, mean edge strain, or displacement from rest:

```
calls 40000  finite 40000  sampled 2048  nan 0  inf 0
mean 7.8442e-04  stddev 2.9527e-04  IQR 4.3079e-04  MAD 2.1654e-04  skew 0.2616  p95 0.0013
min 3.7069e-05  median 7.8288e-04  max 0.0019      + a 25-bin histogram
```

That is strictly better than what `dust_one` did, which was to allocate the state buffer host-visible
and read it back to compute a min, a max and a count. The probe gives a *distribution* for free, it
counts the NaNs and infinities a clamp would otherwise have hidden, and it is sampled before the clamps
so you can watch `maxForce` fire while editing a program. The probe call sits deliberately *before*
both clamps for that reason.

### Stereo

One two-layer `VK_IMAGE_VIEW_TYPE_2D_ARRAY` colour target with `viewMask = 0b11`: one draw call covers
both eyes and `gl_ViewIndex` selects the per-eye view-projection. The eyes are separate array *layers*
rather than two halves of one wide image, which is the real advantage — a full-screen blur cannot bleed
across a seam that does not exist.

Every shader is written once and runs in both stereo modes. The difference is one template slot,
`{{ dust.stereo }}`, set by the stereo box and inherited by every box below it; after it, `DUST_EYE` is
the eye index. `DustWorkout` compiles all eleven shaders in both modes and asserts they differ *only*
where that slot expands.

Two device notes. `MultiviewSideBySideStereoTarget` in `afield.four.targets` refuses to construct on
macOS, but that guard is on *that class* — `RenderToTexture` only asks `device.supportsMultiview`, and
MoltenVK on an M1 Max does advertise it (`maxMultiviewViewCount = 32`), so a Mac takes the multiview
path here. But that same device reports **`multiViewport = false`**, so asking for two viewports (which
`MultiviewSideBySideStereoTarget` does) is a hard pipeline-creation failure. Multiview needs
`viewportCount == 1`; every view draws through the same viewport unless the shader writes
`gl_ViewportIndex`.

`DustStereoMode.MONO` is the fallback when the extension is missing: one layer, `DUST_EYE` a literal 0,
and the target still an array view so no shader changes.
# Demand-gated playback

`DrowningPresentation.splatsEnabled` and `.mxgEnabled` are geometry/decoder master switches. Register
each splat consumer with `registerSplatShade(name) { module.current.enabled && module.current.valid }`
and MXG with `registerMxgShade { ... }`. Stable names replace registrations after live re-launch;
include membership in the target's module map when removing a module should also end demand.

The splat bank stays active while any registered shade wants it. Both banks cancel queued work,
reject requests while inactive and park without periodic polling. In-flight reads/packing may
finish, but epoch/request checks prevent stale publication. Caches and GPU allocations are retained.
`decodedFrames` counts actual frame-read calls; `completedSets` and presenter upload counters make
this inspectable. Replacing a registered bank closes the old one.

Draws must respect `splatsVisible` / `mxgVisible` as well as their own module switch: those flags
remain false until a returning layer has joined a matching video/geometry presentation. The live
boxes do this with zero draw instances or zero vertices, preserving the user's module enabled state
and normal shader reload. Disabled layers are not prerequisites in the frame barrier. The atlas
continues for other consumers; this is decoder suspension, not asset unloading.
