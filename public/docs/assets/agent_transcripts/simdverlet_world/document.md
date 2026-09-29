# Field Document: simdverlet_world

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. 2 - A sphere tearing through a pinned sheet

<!-- field-box id="world_tear" index="0" -->

```edn [field-box id="world_tear" role="properties"]
{"id" "world_tear" "index" 0 "name" "2 - A sphere tearing through a pinned sheet" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - A sphere tearing through a pinned sheet" "_tombstone" false "frame" #afield/rect [500.0 40.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "world_tear_code_.kt" "lines" 50 "characters" 2221 "sha256" "73e3af63fd43590faaf3fff38fb885a99b5bead777e1ef1232d2950188094129"}]}
```

```kotlin [field-property box="world_tear" name="code" path="world_tear_code_.kt"]
import afield.four.physics.*
import afield.stdlib.vec

// Tearing, with something doing the tearing: a sphere pushed through a sheet that is pinned
// all round its border. The sheet is in the x/y plane and the sphere travels along z, so the
// view is straight down the barrel and the hole opens toward you.
val sim = SimdVerlet(SimdVerlet.State3.empty())
val n = 26
val cloth = sim.addCloth(
    width = n,
    height = n,
    spacing = 0.2f,
    stiffness = 60f,
    curvatureStrength = 0f,
    position = { c, r -> vec((c - (n - 1) * 0.5f) * 0.2f, (r - (n - 1) * 0.5f) * 0.2f, 0f) },
    pinned = { c, r -> c == 0 || r == 0 || c == n - 1 || r == n - 1 },
)

val punch = SphereCollider(0f, 0f, -1.6f, radius = 0.55f).also { it.friction = 0.4f }
val world = sim.addColliders(punch)

// An edge is retired once it is 24 % longer than it should be. `enableTearing` rather than
// `sim.addTearing` because a Cloth needs one more thing done: its curvature force is written
// from the GRID, not from the constraint set, so without the hook it goes on pulling the two
// lips of the rip together with no spring behind it and the sheet heals in the picture while
// the solver says it is torn.
//
// maxBreaksPerStep spreads the failure over frames, strongest edge first. It is a DIFFERENT
// rip and not merely a slower one -- a sheet that fails a few edges at a time goes on carrying
// load while it fails, so more of it reaches the threshold.
val tear = cloth.enableTearing(threshold = 0.24f, maxBreaksPerStep = 3)

val view = Rect(-2.9, -2.9, 5.8, 5.8)
var frames = 0

loop {
    // Drive the sphere through and let it come to rest on the far side, so what is left on
    // screen is the hole it made.
    punch.centerZ = (-1.6f + frames * 0.0038f).coerceAtMost(3.2f)
    cloth.step(dt = 1f / 60f, damping = 0.99f, gravity = 0f, iterations = 16)
    frames++

    showPlane(
        sim.debugLines(points = false, thickness = 1.0) + world.outline(thickness = 2.0),
        window = view,
        title = "a sphere tearing through a pinned sheet",
        caption = "frame $frames · ${tear.brokenTotal} edges torn · ${sim.liveEdgeCount()} left · " +
            "peak strain ${"%.2f".format(sim.peakStrain())}",
    )
}
```

## 2. 4 - Everything at once, and one knob

<!-- field-box id="world_all" index="1" -->

```edn [field-box id="world_all" role="properties"]
{"id" "world_all" "index" 1 "name" "4 - Everything at once, and one knob" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 - Everything at once, and one knob" "_tombstone" false "frame" #afield/rect [500.0 190.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "world_all_code_.kt" "lines" 99 "characters" 5309 "sha256" "29930e475d21c9367e50e9ab682c855ef75c254e517b9d41b9818f588985e9a6"}]}
```

```kotlin [field-property box="world_all" name="code" path="world_all_code_.kt"]
import afield.four.physics.*
import afield.stdlib.vec

// Everything at once, and one knob.
//
// A sheet pinned along its top edge, a rope tied to the middle of its bottom edge with a heavy
// weight on the end, a sphere and a floor beneath, gusty wind across it, and tearing on all of it.
// Five subsystems in one simulation, all going through the one `step()`.
//
// ---------------------------------------------------------------------------------------------
// THE KNOB. Change this and re-run. Measured on exactly this scene over 900 frames
// (SimdVerletTearingTest.theWholeWorldLayerInOneSceneBehavesAsTheDemoSaysItWill asserts it):
//
//     0.15  ->  154 edges torn   the sheet shreds; the pieces land on the sphere and the floor
//     0.25  ->   50 edges torn   a large rip, spreading from where the rope pulls
//     0.40  ->   23 edges torn   a local tear at the knot, and the sheet survives
//     0.60  ->    0 edges torn   it holds -- and never touches the sphere or the floor at all
//
// The shipped 0.6 is the last row, and that is on purpose: an intact sheet hangs clear of
// everything under it, so "the collider contact count went from 0 to 236" and "the sheet failed"
// are the same event, and the scene is watchable for as long as you leave it running.
//
// One warning before you turn it down. Below about 0.5 the torn sheet keeps being blown
// downwind by a wind that never stops, and over thousands of frames it stretches into a thin web
// pinned across the whole room: by frame 2500 the cloth's bounding box was the room.
// The sheet stops telling you anything after a few hundred frames; re-run the box to reset.
// ---------------------------------------------------------------------------------------------
val tearThreshold = 0.6f

// y grows DOWN the plane view, so gravity is positive and the sheet hangs from row 0.
val sim = SimdVerlet(SimdVerlet.State3.empty())

val cloth = sim.addCloth(
    width = 20,
    height = 12,
    spacing = 0.22f,
    stiffness = 60f,
    curvatureStrength = 0f,
    position = { c, r -> vec(c * 0.22f, r * 0.22f, 0f) },
    pinned = { _, r -> r == 0 },
)

// A rope, tied to the sheet. `Strand` spends only two of its four constraint slots on the chain,
// which is what leaves attachTo something to spend: the knot is an ordinary symmetric distance
// constraint, so the solver, the colliders and the tearing all already understand it -- and the
// knot is among the edges that can fail, with nothing written to make it so.
//
// It has to be tied to a grid node with a free slot. A cloth's bottom edge has one; its interior
// has none, and attachTo will tell you so by index rather than failing quietly.
val rope = sim.addStrand(
    count = 16,
    spacing = 0.16f,
    stiffness = 150f,
    bendStiffness = 0.2f,   // 0..1, a projection: how tightly the rope is allowed to fold
    position = { n -> vec(10 * 0.22f, 11 * 0.22f + 0.16f * (n + 1), 0f) },
)
rope.attachTo(cloth[10, 11], node = 0)

// The weight on the end of the rope. invMass 1/24 is a mass of 24 -- this is what loads the sheet.
sim.state.invMass!![rope[15]] = 1f / 24f

// The world. Projections, not forces, and they run after integration inside step().
// A room rather than a floor, and that is a lesson rather than a preference: left running with a
// steady wind, a torn-free piece of sheet leaves the view and never comes back. Measured on the
// earlier version of this box -- at frame 17654 the picture was empty while the solver still held
// 443 live edges. A box with `inside = true` keeps the whole thing on screen indefinitely.
val world = sim.addColliders(
    SphereCollider(1.1f, 4.6f, 0f, radius = 0.7f).also { it.friction = 0.6f },
    BoxCollider(-0.9f, -0.4f, -1.6f, 7.4f, 6.4f, 1.6f, inside = true).also { it.friction = 0.9f },
)

// Air -- and only the per-particle drag pass here, which is a deliberate omission with a reason.
// `cloth.addWind`'s per-quad force acts along the sheet's NORMAL, and this sheet's normal points
// out of the screen: left running with it on, the whole thing swings into the y/z plane and its
// x/y projection collapses onto the pinned row. Measured, at frame 23811 the picture was a single
// horizontal line while the solver still held 443 edges and reported 236 contacts. Box 3 is the
// view that shows the per-quad model properly; this box wants a wind it can draw.
val wind = sim.addWind(velocityX = 3f, drag = 0.25f, turbulence = 0.8f, turbulenceScale = 0.4f, seed = 5)

// Tearing over the WHOLE simulation, so the knot and the rope are candidates like any other edge.
val tear = sim.addTearing(threshold = tearThreshold, maxBreaksPerStep = 2)

val view = Rect(-1.0, -0.6, 7.6, 7.6)
var frames = 0

loop {
    cloth.step(dt = 1f / 60f, damping = 0.99f, gravity = 3f, iterations = 16)
    frames++

    showPlane(
        sim.debugLines(points = false, thickness = 0.9) +
            world.outline(thickness = 2.0) +
            wind.windLines(-0.8f, 0f, 0f, 7.2f, 7.2f, 0f, columns = 9, rows = 9, scale = 0.05f),
        window = view,
        title = "everything at once - cloth, rope, colliders, wind, tearing",
        caption = "frame $frames · threshold $tearThreshold · ${tear.brokenTotal} edges torn · " +
            "${world.contacts} contacts · ${sim.liveEdgeCount()} edges left",
    )
}
```

## 3. 3 - A flag in gusty wind, seen from above

<!-- field-box id="world_wind" index="2" -->

```edn [field-box id="world_wind" role="properties"]
{"id" "world_wind" "index" 2 "name" "3 - A flag in gusty wind, seen from above" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - A flag in gusty wind, seen from above" "_tombstone" false "frame" #afield/rect [40.0 190.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "world_wind_code_.kt" "lines" 55 "characters" 2294 "sha256" "c537976d357c6d40befc41473fc9c4ad8a7e75eb212cde674286a50e04c25abb"}]}
```

```kotlin [field-property box="world_wind" name="code" path="world_wind_code_.kt"]
import afield.four.physics.*
import afield.graphics.FLine
import afield.stdlib.vec

// A flag. It lies in the x/y plane and flaps in z, which is why the view at the bottom is
// from ABOVE: seen from the front this is a rectangle standing perfectly still.
val sim = SimdVerlet(SimdVerlet.State3.empty())
val cloth = sim.addCloth(
    width = 26,
    height = 16,
    spacing = 0.2f,
    stiffness = 60f,
    curvatureStrength = 0f,
    position = { c, r -> vec(c * 0.2f, r * 0.2f, 0f) },
    pinned = { c, _ -> c == 0 },
)

// Two models of air, and they are not alternatives.
//
// `sim.addWind` is per-particle drag, F = drag * (w - v). It works on anything and it cannot
// tell which way the fabric is facing -- two identical sheets, one face-on and one edge-on,
// get exactly the same force out of it. So it leans a sheet; it does not fly one.
val wind = sim.addWind(
    velocityX = 9f,
    drag = 0.05f,
    turbulence = 0.7f,       // gusts, as a fraction of the mean speed
    turbulenceScale = 0.5f,  // inverse gust size, in world units
    seed = 12,               // same seed, same weather, forever
)

// `cloth.addWind` is the per-quad model, which uses the sheet's own grid: normal from the
// diagonals, area from half its length, F = 0.5 rho Cd A (n . v_rel) |n . v_rel| n quartered
// onto the corners. That single (n . v_rel) is the flutter: a quad turning edge-on stops being
// pushed, overshoots, catches the wind on its other face, and the wave runs down the sheet.
// Comment this line out and watch the flag stop flying and start leaning.
val aero = cloth.addWind(wind)

// The view from above: keep x, and put z where y usually goes.
fun topView(lines: List<FLine>) = lines.map { it.transform { p -> vec(p.x, p.z, 0.0) } }

val view = Rect(-0.5, -1.3, 6.0, 2.6)
var frames = 0

loop {
    cloth.step(dt = 1f / 60f, damping = 0.98f, gravity = 2f, iterations = 16)
    frames++

    showPlane(
        topView(sim.debugLines(points = false, thickness = 0.9)) +
            topView(wind.windLines(-0.4f, 0f, -1.1f, 5.6f, 0f, 1.1f, columns = 10, rows = 7, scale = 0.05f)),
        window = view,
        title = "a flag in gusty wind, seen from above",
        caption = "frame $frames · aero impulse ${"%.1f".format(aero.lastImpulse)} · green: the wind field",
    )
}
```

## 4. 1 - A cloth draping over a sphere

<!-- field-box id="world_drape" index="3" -->

```edn [field-box id="world_drape" role="properties"]
{"id" "world_drape" "index" 3 "name" "1 - A cloth draping over a sphere" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - A cloth draping over a sphere" "_tombstone" false "frame" #afield/rect [40.0 40.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "world_drape_code_.kt" "lines" 52 "characters" 2389 "sha256" "849cc8bbfea06af6a10fbc33f2e0ec34943f8b98c1f6180492199e381279985d"}]}
```

```kotlin [field-property box="world_drape" name="code" path="world_drape_code_.kt"]
import afield.four.physics.*
import afield.stdlib.vec

// A cloth, and something for it to land on.
//
// Convention first, because it decides every sign below: the plane view has y growing DOWN
// the screen, so gravity is POSITIVE, the sheet starts at y = 0 and the sphere is below it
// at y = 2.
val sim = SimdVerlet(SimdVerlet.State3.empty())
val cloth = sim.addCloth(
    width = 22,
    height = 22,
    spacing = 0.22f,
    stiffness = 60f,
    curvatureStrength = 0f,
    // Held out flat, face down, centred over the sphere. The sheet lives in the x/z plane
    // and falls along y, so what the view below shows is its silhouette.
    position = { c, r -> vec((c - 10.5f) * 0.22f, 0f, (r - 10.5f) * 0.22f) },
    pinned = { _, _ -> false },
)

// The world. A collider is a POST-SOLVE POSITION PROJECTION, not a penalty force. The
// constraint solve is force-based -- all `iterations` corrections land in one accumulator
// -- so a cloth spring of stiffness k acts at k*iterations while a bulk force runs once.
// A penalty collider is therefore sixteen times too soft at the setting below, and
// stiffening it by sixteen puts it past the explicit-integration limit: nothing in a 256x
// sweep of penalty stiffness got within two orders of magnitude of the projection.
// dev_docs/physics/SIMDVERLET_5_WORLD.md has the sweep.
val world = sim.addColliders(
    // friction is Coulomb, bounded by mu * penetration. Turn it down to 0f and watch the
    // sheet slide off the shoulder of the sphere instead of gripping it.
    SphereCollider(0f, 2f, 0f, radius = 1.05f).also { it.friction = 0.75f; it.margin = 0.02f },
    // A floor at y = 4.4, with the legal side ABOVE it: y grows down, so the normal is -y
    // and the offset is -4.4.
    PlaneCollider(0f, -1f, 0f, offset = -4.4f).also { it.friction = 0.9f },
)

val view = Rect(-3.0, -1.0, 6.0, 6.0)
var frames = 0

loop {
    // The colliders run inside step(), after integration -- see SimdVerlet.positionProjections.
    cloth.step(dt = 1f / 60f, damping = 0.99f, gravity = 9.8f, iterations = 16)
    frames++

    showPlane(
        sim.debugLines(points = false, thickness = 0.9) + world.outline(thickness = 2.0),
        window = view,
        title = "a 22 x 22 cloth draping over a sphere",
        caption = "frame $frames · ${world.contacts} contacts this frame · cyan: constraints · amber: colliders",
    )
}
```

