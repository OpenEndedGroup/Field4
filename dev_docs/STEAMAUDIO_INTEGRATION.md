# Using `afield.four.audio.steamaudio` from Field

What you need to wire the bindings into a 2D game or into Field itself. The reasoning behind the
numbers is in [STEAMAUDIO.md](STEAMAUDIO.md), and every claim here was measured rather than
assumed.

Written as a handoff document for a consumer taking the bindings as a published dependency, and
kept in that shape because that is still the clearest way to read sections 2 onwards. Section 1
is the exception and has been rewritten for Field, which does not consume them that way.

**Target**: Kotlin/JVM, **JDK 25+**, Steam Audio **4.8.1**, macOS (universal) / Windows x64 /
Linux x64.

---

## 1. Getting at it from Field

Nothing to depend on: the bindings are in Field's own tree and its build graph, at
`src/jvmMain/kotlin/afield/four/audio/steamaudio`. Import and use them.

One setup step, once per checkout, because the binaries are not committed:

```bash
./gradlew fetchSteamAudioNatives
```

That unpacks this host's `libphonon` into `build/steamaudio`, verified against
`native/steamaudio/checksums.txt`. Every Field run and test task then points the loader at it
automatically. To use a library you built yourself instead -- Linux arm64 being the case that
needs one, since Valve ships no aarch64 build:

```bash
./gradlew <task> -PsteamaudioLibrary=/path/to/libphonon.so
```

Native access is already granted: Field passes `--enable-native-access=ALL-UNNAMED` on every
app and test launch, so there is nothing to add.

The published-artifact route -- `packageAll`, the three natives jars, `module-info.java` and
`verifyPackaging` -- is **not active here**; see *Where this lives* in
[STEAMAUDIO.md](STEAMAUDIO.md) for why, and the Packaging section there for what was learned
doing it, which is worth reading before anyone revives it.

---

## 2. Coordinates — read this before anything else

Your game: geometry in **xy**, listener translating in xy and rotating about **z**, both ears in
the plane of the geometry. `World2D` encapsulates the mapping:

```kotlin
val world = World2D(floorDepth = -1.75f, ceilingDepth = 1.75f, earDepth = 0f)

val listener = world.listenerFacing(x, y, facingX, facingY)   // or listenerAtAngle(x, y, rad)
val sourcePos = world.point(sx, sy)
```

Game `(gx, gy, gz)` becomes world `(gx, gz, -gy)`. Game `+y` maps to world **`-z`**, not `+z`.

**Do not convert by hand.** The obvious mapping is orientation-reversing under these
conventions and silently swaps the listener's ears — levels, reverb, distance and occlusion all
stay correct and only left/right invert. `World2DHandednessTest` renders real impulses and
asserts which ear leads, for twelve azimuths plus the cardinal cases. It caught exactly this bug
in `CoordinateSpace3.lookingAt` during development.

Converting *back* (e.g. for the pathing visualiser): game `x` = world `x`, game `y` = **minus**
world `z`.

### Walls

```kotlin
val mesh = WallSet(world, area.segments.map {
    WallSegment(it.x0, it.y0, it.x1, it.y1, Material.ROCK)
}).build(scene, bounds = floatArrayOf(minX, minY, maxX, maxY))
mesh.addTo(scene)
scene.commit()
```

**Leave `capped = true`.** Extruded line segments alone form an open-ended box; rays escape
through the gap and the reverb collapses. If a room sounds far deader than its materials
suggest, a missing cap is the first thing to check.

Ten materials are provided (`Material.ROCK`, `CONCRETE`, `METAL`, `CURTAIN`, `ANECHOIC`, …).

### Moving segments

```kotlin
val door = MovableWall(scene, world, material = Material.WOOD)
door.addTo(scene)
// per frame:
door.moveTo(scene, x0, y0, x1, y1)     // or door.hide(scene)
scene.commit()                          // ONCE per frame, covering every moved segment
```

Use `MovableWall.sharing(...)` when there are many — one quad, many transforms.

---

## 3. Threading — the part that breaks audio if you get it wrong

| operation | thread | measured cost |
|---|---|---|
| `Simulator.runReflections` | **its own thread** | 10–15 ms @ 4096 rays/16 bounces; 54 ms @ 16384/32 |
| `Simulator.runPathing` | **its own thread** | expensive |
| `Simulator.runDirect` | game thread is fine | cheap |
| `Scene.commit` / `Simulator.commit` | simulation thread | at the timer floor; must not overlap a run |
| `Source.getOutputs` | anywhere, including audio | cheap, non-blocking |
| every effect's `apply` / `getTail` | **audio thread** | see [QualityPreset] |

```kotlin
// Simulation thread, ~15 Hz. Reverb changes slowly; block rate is waste.
for (w in movedWalls) w.moveTo(scene, ...)
scene.commit()
simulator.setSharedInputs(flags, sharedInputs)
for (s in sources) s.setInputs(flags, s.inputs)
simulator.runDirect()
simulator.runReflections()          // blocks here

// Audio thread, every block. Only ever READS the last completed result.
source.getOutputs(flags, outputs)
outputs.copyDirectInto(directParams)
directParams.flags = /* which components to apply */
reflectionParams.copySimulatedFrom(outputs)
```

`copySimulatedFrom` exists because a blind struct copy would clobber `type`, `channelCount` and
`irSize` — the simulator does not own those three, the effect does. Same for
`PathEffectParams.copySimulatedFrom`, which preserves `order`, `binaural`, `hrtf`, `listener`
and `normalizeEq`.

**A mixed static/dynamic scene needs no special arrangement.** Steam Audio maintains the
acceleration structure incrementally: commit after moving instances is at the timer floor and
does not grow with static geometry volume (measured to 76,800 triangles), nor is the work
deferred into the next ray query. Put static geometry straight in the scene and move dynamic
pieces as instanced meshes. **Budget the ray count, not the triangle count.** The one cost that
does scale with geometry is the initial `StaticMesh` build — pay it at area load.

---

## 4. The signal chain

```kotlin
val quality = QualityPreset.HIGHEST      // CONVOLUTION, Ambisonic order 2, BILINEAR

// Once, shared. ~15 MB of native memory (measured) -- one per listener, never per source.
val hrtf = Hrtf.default(context, audioSettings)

// Per source, from a long-lived Arena so the audio thread never allocates.
val dry      = AudioBuffer(arena, 1, frameSize)
val shaped   = AudioBuffer(arena, 1, frameSize)
val direct   = AudioBuffer(arena, 2, frameSize)
val ambisonic = AudioBuffer(arena, quality.ambisonicChannelCount, frameSize)
val reflected = AudioBuffer(arena, 2, frameSize)

// Per block:
directEffect.apply(directParams, dry, shaped)          // distance, air, occlusion, transmission
binaural.apply(binauralParams, shaped, direct)         // mono -> stereo, HRTF
reflectionEffect.apply(reflectionParams, dry, ambisonic)
decodeParams.orientation = listenerFrame               // head rotation, cheaply
decode.apply(decodeParams, ambisonic, reflected)
// sum `direct` + `reflected`
```

### Settings, measured

- **`BILINEAR` HRTF interpolation.** `NEAREST` produced only 14 distinct ear-balance values
  across 46 azimuth samples with a worst step of 6.46 dB — an audible zipper on a moving
  source. `BILINEAR`: 45 values, 1.76 dB.
- **`CONVOLUTION` reflections.** Cheaper *and* more accurate than `HYBRID` at every duration
  from 1 to 10 s, because hybrid pays for the convolution and the feedback network both. Use
  `PARAMETRIC` for distant or background sources only: five times cheaper, but it loses early
  reflection detail and glitches audibly on a moving foreground source.
- **Ambisonic order 2** for highest quality, order 1 for balanced. See `QualityPreset.HIGHEST`
  for an honest statement of how strong that evidence is — it is consistent in sign but close
  to the noise floor of a stochastic simulation, and it costs 2.3× per block.
- **`spatialBlend = 1.0`** for anything the player should be able to place. 0.75 already loses
  22% of the ear-balance cue.
- **Never binauralise twice.** A binaural Ambisonic decode is already an HRTF render; a
  `BinauralEffect` after it doubles the head-shadowing and smears the image.
- **Use a `ReflectionMixer`** once you have several sources: each reflection effect accumulates
  into one Ambisonic bus so the decode happens once rather than per source.

### Tails are not optional

In a 1.7-second space the tail is most of what you hear. When a source stops, call `getTail`
each block until it returns `TAIL_COMPLETE` — `AudioEffect.drainTail` wraps the loop. Cutting
instead of draining is immediately audible.

### Parameters are ramped across a block

Steam Audio interpolates parameter changes over the block rather than at sample zero, which is
what stops moving sources clicking. Consequence: a one-shot shorter than a block, triggered in
the same block that positions it, is partly rendered at the *previous* position. Set parameters
a block early if precise placement of short sounds matters.

---

## 5. Pathing

Pathing is what makes sound route *around* a corner rather than merely attenuate through it.
Measured in a two-room-with-a-doorway scene: **+33.6 dB** over occlusion alone, the image
**relocating 5.8 dB toward the doorway**, and a monotonic dark tilt (`pathEQ` 0.71 / 0.36 /
0.21) because low frequencies diffract and high ones do not.

**Unlike every other effect it is not purely geometric at runtime.** It searches a precomputed
probe-to-probe visibility graph, so it needs, at tool time or area load:

```kotlin
val probes = ProbeArray(context)
probes.generate(scene, ProbeGenerationParams(spacing = 1.5f, height = 1.75f, transform = areaBox))
val batch = ProbeBatch(context)
batch.addProbeArray(probes)
batch.commit()
PathBaker.bake(context, PathBakeSettings(scene, batch)) { progress -> report(progress) }
// then ProbeBatch.save into a SerializedObject and persist it
```

98 probes at 1.5 m spacing over a 20 × 10 m room took under a second and produced 51 KB.

**The graph assumes bake-time geometry.** Pathing will route through a doorway a moving wall has
since closed, and will not find one it has since opened. Re-baking is not a per-frame
operation. Either bake with doors open and let occlusion and transmission handle the closed
case, or keep connectivity-changing segments out of the graph and let reflections carry them.

`PathEffectParams.hasNoPath()` distinguishes "found nothing" from "found a quiet route" — the
two failure modes that sound identical. `PathingVisualizationCallback` hands you every
probe-to-probe segment the search examined with an `occluded` flag; draw those on the map and
the graph becomes visible.

---

## 6. Lifetime

Everything is reference-counted native memory behind `AutoCloseable`. Use `use { }`.

- `close()` is idempotent; a `Cleaner` is a safety net, not a substitute.
- `retain()` returns an independently closeable wrapper sharing the native object. Each must be
  closed exactly once.
- Use-after-close throws `IllegalStateException` rather than segfaulting.
- Objects do not retain their dependencies: an `Hrtf` must outlive every effect and parameter
  block naming it, and a sub-`Scene` must outlive its `InstancedMesh`.

Verified with a counting allocator installed via `MemoryAllocator`: every create+close pair
returns **exactly** its memory (0 bytes outstanding over 40 cycles, for every object type).
That same hook is available to you if the engine wants Steam Audio's allocations on its own
heap.

---

## 7. Known gaps

- **No Linux arm64.** Valve ships no aarch64 Linux binary. macOS is universal; Windows and
  Linux are x64 only. Struct layouts are already verified identical on aarch64-linux, so
  building `libphonon` from source is the only missing piece.
- **Not yet wrapped**: `PanningEffect`, `VirtualSurroundEffect`, `AmbisonicsPanningEffect`.
  Layer 1 binds all 216 functions, so these are wrappers waiting to be written, not gaps in
  coverage. Nothing in the binaural path needs them.
- **Direct path carries no propagation delay.** `DirectEffect` applies gain and spectrum but not
  time-of-flight, while `ReflectionEffect` honours the simulated delay. This is the upstream
  idiom; add the delay yourself if you want true time-of-flight on the direct path.
- **No CI yet.** The build and 34 tests pass on macOS arm64. Linux and Windows are untested in
  anger, though the bindings are platform-independent by construction.
- **Front/back confusion is the standing localisation limit** for a 2D game: with both ears in
  the geometry plane every source is at zero elevation, so ahead and behind produce nearly
  identical cues. Let facing rotate smoothly rather than snapping, and lean on reverb and
  occlusion differences as well as direction.

---

## 8. Commands

```bash
./gradlew fetchSteamAudioNatives      # unpack + verify this host's libphonon; needed once
./gradlew jvmTest --tests 'afield.four.audio.steamaudio.*'
./gradlew caveDemo                    # reverberant space, dynamic planes, live reflections
./gradlew pathingDemo                 # sound routing around a corner through a doorway
./gradlew orbitDemo                   # a source flying around the head
./gradlew hrtfProbe                   # per-azimuth ILD/ITD table
./gradlew binauralQuality             # interpolation, Ambisonic order, spatial blend
./gradlew geometryBenchmark           # commit and runReflections cost versus geometry size
./gradlew reflectionHandednessProbe   # which ear the reflected field arrives from
```

Demo overrides, forwarded by every demo task: `-Dsteamaudio.demo.order=N`,
`-Dsteamaudio.demo.reflectionType=CONVOLUTION`, `-Dsteamaudio.demo.duration=S`,
`-Dsteamaudio.demo.reflectionsOnly=true`, `-Dsteamaudio.demo.pathingOnly=true`. The output file
is named after whichever were set, so runs do not overwrite each other.

The natives are **not committed**. `native/steamaudio/checksums.txt` pins the upstream release
zip and every file taken from it by SHA-256; `fetchSteamAudioNatives` downloads, verifies, and
refuses to write on a mismatch.
