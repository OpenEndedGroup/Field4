# Field Document: keyframe_autosweep

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. renderer

<!-- field-box id="ks_renderer" index="0" -->

```edn [field-box id="ks_renderer" role="properties"]
{"id" "ks_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["ks_rig"] "properties" {"children" #{#afield/box "ks_rig"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ks_renderer_code_.kt" "lines" 15 "characters" 258 "sha256" "5d8395db0d55b1a4c2f2fa1d15531eb2125e0efcc928e927406fca662d766819"}]}
```

```kotlin [field-property box="ks_renderer" name="code" path="ks_renderer_code_.kt"]
import afield.four.util.Options

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false
Options.profiling = false

once.renderer {
    window = Window("Keyframe sweep")
    Renderer(window)
}

println("renderer ready")
start(renderer::run)
```

## 2. the rig, and ten lanes of it

<!-- field-box id="ks_rig" index="1" -->

### Narrative

From `code`

The half you would replace with your own sketch. Everything below the scene is the
contract a sweep needs: named lanes with a getter and a setter, and nothing else in the
document writing them.

```edn [field-box id="ks_rig" role="properties"]
{"id" "ks_rig" "index" 1 "name" "the rig, and ten lanes of it" "parents" ["root" "ks_renderer"] "children" ["ks_sweep"] "properties" {"children" #{#afield/box "ks_sweep"} "parents" #{#afield/box "root" #afield/box "ks_renderer"} "name" "the rig, and ten lanes of it" "_tombstone" false "frame" #afield/rect [40.0 200.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ks_rig_code_.kt" "lines" 157 "characters" 7892 "sha256" "6deaa6eb0a08a38678988d07a52bb47125044c2a564279f44f1a338391c12d00"}]}
```

```kotlin [field-property box="ks_rig" name="code" path="ks_rig_code_.kt"]
// The half you would replace with your own sketch. Everything below the scene is the
// contract a sweep needs: named lanes with a getter and a setter, and nothing else in the
// document writing them.
import afield.four.fabricated.StandardRenderer
import afield.four.fabricated.applyScenePlan
import afield.stdlib.KeyframeCollection
import afield.stdlib.KeyframeScope
import afield.stdlib.keyFilesMatching
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f
import afield.stdlib.path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

// Fully qualified, because `prop<>` rewrites this box's code and the rewrite does not
// carry the snippet's own imports.
prop<afield.four.fabricated.StandardRenderer.Bundle>(bundle)
prop<afield.stdlib.KeyframeScope>(keys)

val r: Renderer = fromContext()
val dim = 512

// Plain locals rather than `prop<>`s: a `Path` in a document property would have to
// survive .edn, and every box that wants these can spell them in one line.
val pool = "%document/looks".path

once.keys {
    val b = StandardRenderer.build(
        r,
        StandardRenderer.Config(renderWidth = dim, renderHeight = dim, backgroundColor = Vector3f(0.02f, 0.02f, 0.03f))
    )
    val scene = b.scene

    // A square target must be projected square. `Projection(renderer)`'s `auto` lambda
    // re-resizes to the *window* inside every evaluateUniform, so a one-off `resize` is
    // overwritten next frame and every sphere comes out an ellipse. Replace the lambda.
    scene.projection.auto = { scene.projection.resize(dim, dim) }
    scene.projection.resize(dim, dim)

    scene.camera.at.set(0f, 2.0f, -3.2f)
    scene.camera.target.set(0f, 0f, 0f)
    scene.lightDirection.set(Vector3f(-0.5f, -1f, -0.3f).normalize())
    scene.lightIntensity = 3.4f
    scene.shadowStrength = 1f
    scene.aoStrength = 0.8f

    val grid = 3
    val spheres = (0 until grid * grid).map {
        b.registerSphere(name = "ball-$it", radius = 0.3f, slices = 24, stacks = 24, renderer = r)
    }
    // A floor, and not for decoration: `shadow.strength` and `ao.strength` are two of the
    // ten lanes, and with nothing to receive a shadow they are two lanes a sweep could
    // move all night without changing a pixel.
    b.registerCube(name = "floor", size = 1f, renderer = r)
        .setModel(Matrix4f().translate(0f, -0.34f, 0f).scale(11f, 0.04f, 11f))
    b.applyScenePlan()

    var spread = 0.85f
    var visible = grid * grid
    // Hidden from the outside in. Hiding the tail of a row-major grid takes its last row
    // away and shoves the rest off to one side, which would make `grid.visible` half a
    // camera move as well as a count.
    val centreOut = (0 until grid * grid).sortedBy {
        val ix = it / grid - (grid - 1) / 2
        val iz = it % grid - (grid - 1) / 2
        ix * ix + iz * iz
    }
    // A lambda rather than a local `fun`: a named function declared in a box snippet can
    // reach K2 REPL codegen paths that a lambda does not, and one of them fails here with
    // `IrFunctionImpl has no callableId`. Box code, and only box code.
    val layout = {
        centreOut.forEachIndexed { rank, i ->
            val ix = i / grid - (grid - 1) / 2
            val iz = i % grid - (grid - 1) / 2
            spheres[i].setModel(
                if (rank >= visible) Matrix4f().scale(0f)
                else Matrix4f().translate(ix * spread, 0f, iz * spread)
            )
        }
    }
    layout()

    // The discrete lane: a name that selects a whole sub-state. There is no midpoint
    // between "warm" and "acid", which is why a blend takes it whole from one parent.
    val palettes = mapOf(
        "warm" to (Vector3f(0.10f, 0.05f, 0.03f) to Vector3f(1.0f, 0.78f, 0.55f)),
        "cool" to (Vector3f(0.03f, 0.05f, 0.11f) to Vector3f(0.62f, 0.78f, 1.0f)),
        "acid" to (Vector3f(0.04f, 0.10f, 0.04f) to Vector3f(0.75f, 1.0f, 0.35f))
    )
    var palette = "warm"
    val applyPalette = { name: String ->
        val chosen = palettes[name] ?: palettes.getValue("warm")
        scene.ambientColor.set(chosen.first)
        scene.lightColor.set(chosen.second)
        palette = name
    }
    applyPalette(palette)

    bundle = b
    KeyframeScope(schemaVersion = 1).apply {
        vector3Target("camera.at", { scene.camera.at }) { scene.camera.at.set(it) }
        vector3Target("camera.target", { scene.camera.target }) { scene.camera.target.set(it) }
        vector3Target("light.direction", { scene.lightDirection }) {
            scene.lightDirection.set(if (it.lengthSquared() > 1e-8f) Vector3f(it).normalize() else it)
        }
        floatTarget("light.intensity", { scene.lightIntensity }) { scene.lightIntensity = it }
        vector3Target("background", { scene.backgroundColor }) { scene.backgroundColor.set(it) }
        floatTarget("ao.strength", { scene.aoStrength }) { scene.aoStrength = it }
        floatTarget("shadow.strength", { scene.shadowStrength }) { scene.shadowStrength = it }
        floatTarget("grid.spread", { spread }) { spread = it; layout() }
        intTarget("grid.visible", { visible }, 1..(grid * grid)) { visible = it; layout() }
        stringTarget("palette", { palette }) { applyPalette(it) }
    }
}

bundle.present(renderer = r)
bundle.attachTo(r)

// Four starter looks, written only if the pool is empty — so editing them here and
// deleting the directory is how you reshape the space. The fourth is deliberately
// *incomplete*: it predates `ao.strength` and `palette`, the way a key saved three weeks
// ago does, and the sweep's manifests record what it had to back-fill.
pool.createDirectories()
if (keyFilesMatching(pool).isEmpty()) {
    val look = { name: String, values: Map<String, Any?> ->
        pool.resolve(name).writeText(keys.encodeKeyJson(keys.Key(values.toMutableMap())))
    }
    look("high-and-cool", mapOf(
        "camera.at" to Vector3f(0f, 3.0f, -3.0f), "camera.target" to Vector3f(0f, 0f, 0f),
        "light.direction" to Vector3f(-0.5f, -1f, -0.3f).normalize(), "light.intensity" to 3.4f,
        "background" to Vector3f(0.012f, 0.020f, 0.050f), "ao.strength" to 0.85f,
        "shadow.strength" to 1.0f, "grid.spread" to 0.85f, "grid.visible" to 9, "palette" to "cool"))
    look("low-and-warm", mapOf(
        "camera.at" to Vector3f(1.8f, 1.2f, -2.6f), "camera.target" to Vector3f(0f, 0.1f, 0f),
        "light.direction" to Vector3f(0.7f, -0.4f, -0.5f).normalize(), "light.intensity" to 6.2f,
        "background" to Vector3f(0.050f, 0.025f, 0.012f), "ao.strength" to 0.35f,
        "shadow.strength" to 0.6f, "grid.spread" to 1.45f, "grid.visible" to 4, "palette" to "warm"))
    look("flat-and-acid", mapOf(
        "camera.at" to Vector3f(-2.2f, 1.8f, -2.4f), "camera.target" to Vector3f(0.2f, 0.2f, 0f),
        "light.direction" to Vector3f(-0.1f, -0.95f, 0.1f).normalize(), "light.intensity" to 1.9f,
        "background" to Vector3f(0.010f, 0.045f, 0.015f), "ao.strength" to 1.0f,
        "shadow.strength" to 0.15f, "grid.spread" to 0.55f, "grid.visible" to 9, "palette" to "acid"))
    look("older-partial-key", mapOf(
        "camera.at" to Vector3f(0f, 1.6f, -3.6f), "camera.target" to Vector3f(0f, 0f, 0f),
        "light.direction" to Vector3f(-0.8f, -0.6f, 0.1f).normalize(), "light.intensity" to 4.6f,
        "background" to Vector3f(0.020f, 0.020f, 0.020f),
        "shadow.strength" to 0.8f, "grid.spread" to 1.05f, "grid.visible" to 6))
}

// The pool as the browser sees it: which lanes every key has, and which it does not.
val census = KeyframeCollection.load(pool, scope = keys)
println("pool: ${census.files.size} keys in $pool")
census.files.forEach { f ->
    val missing = keys.capture().values.keys - f.values.keys
    println("  ${f.relative}: ${f.values.size} lanes" + if (missing.isEmpty()) "" else ", missing $missing")
}
census.skipSummary()?.let { println("  ($it)") }
```

## 3. the morning

<!-- field-box id="ks_archive" index="2" -->

### Narrative

From `code`

What a night looks like from the other end. Nothing here touches the renderer: an
archive describes itself, which is the whole point of the manifests.

```edn [field-box id="ks_archive" role="properties"]
{"id" "ks_archive" "index" 2 "name" "the morning" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "the morning" "_tombstone" false "frame" #afield/rect [1500.0 40.0 360.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ks_archive_code_.kt" "lines" 45 "characters" 2164 "sha256" "7f81195e6d47ea3fba820eee9c016d83b93cd78e9a0d36437d94144e4d152a0d"}]}
```

```kotlin [field-property box="ks_archive" name="code" path="ks_archive_code_.kt"]
// What a night looks like from the other end. Nothing here touches the renderer: an
// archive describes itself, which is the whole point of the manifests.
import afield.stdlib.path
import afield.stdlib.readSweepArchive
import afield.stdlib.sweepArchiveSummary
import java.util.Base64
import kotlin.io.path.exists
import kotlin.io.path.readBytes

val archive = "%document/sweep".path
val rows = readSweepArchive(archive)

println(sweepArchiveSummary(archive))

// A contact sheet, straight from the stills the sweep wrote. Base64 rather than a file
// URL so the panel needs no static mount.
val sheet = rows.joinToString("") { m ->
    val still = archive.resolve("renders/${m.id}/still.png")
    val img = if (!still.exists()) "{dim:no still}" else
        "<img src='data:image/png;base64,${Base64.getEncoder().encodeToString(still.readBytes())}' " +
            "width='150' style='border:1px solid #333;display:block'/>"
    val pushed = m.extrapolationGain?.let { " ✦" } ?: ""
    "<div style='display:inline-block;margin:2px;font:10px Iosevka,monospace;color:#999'>" +
        "$img${m.index}$pushed ${m.anchor.take(12)}</div>"
}
display("sheet", "<div style='width:500px'>$sheet</div>")

markdown("what went in", """
    #### ${rows.size} renders

    | # | parents and weights | pushed past the hull | discrete lane from |
    |---|---------------------|----------------------|--------------------|
    ${rows.joinToString("\n") { m ->
        "| ${m.index} | ${m.parents.joinToString("<br>") { "${it.key} ${"%.2f".format(it.weight)}" }} " +
            "| ${m.extrapolationGain?.let { g -> "x${"%.2f".format(g)} away from ${m.extrapolationPole}" } ?: "—"} " +
            "| ${m.discreteLanes.values.distinct().joinToString(", ")} |"
    }}
""", width = 500)

// One lane's trajectory through the night, read out of the keys themselves.
val intensities = rows.map { m ->
    val key = archive.resolve("renders/${m.id}/key.json")
    if (key.exists()) (afield.stdlib.KeyframeScope().loadKey(key)["light.intensity"] as? Number ?: 0).toFloat() else 0f
}
showSignals("light.intensity" to intensities, title = "one lane across the night", xTitle = "render")
```

## 4. the sweep

<!-- field-box id="ks_sweep" index="3" -->

### Narrative

From `code`

The whole of it. Twelve renders here so the box finishes while you watch; set `count`
to 5000 and `maxDurationMs` to whatever "stop at 7am" is, and walk away.

```edn [field-box id="ks_sweep" role="properties"]
{"id" "ks_sweep" "index" 3 "name" "the sweep" "parents" ["root" "ks_rig"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "ks_rig"} "name" "the sweep" "_tombstone" false "frame" #afield/rect [40.0 360.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ks_sweep_code_.kt" "lines" 61 "characters" 2826 "sha256" "c41a922c34a5fdbfe3709d83b7c3a087b9fae7c335d9e66c2742ba55d694b8fa"}]}
```

```kotlin [field-property box="ks_sweep" name="code" path="ks_sweep_code_.kt"]
// The whole of it. Twelve renders here so the box finishes while you watch; set `count`
// to 5000 and `maxDurationMs` to whatever "stop at 7am" is, and walk away.
import afield.stdlib.KeyframeScope
import afield.stdlib.KeyframeSweep
import afield.stdlib.SweepConfig
import afield.stdlib.awaitFrames
import afield.stdlib.captureClip
import afield.stdlib.captureStill
import afield.stdlib.path
import afield.stdlib.rendersPerHour

val r: Renderer = fromContext()
val keys: KeyframeScope = fromContext()
val bundle: afield.four.fabricated.StandardRenderer.Bundle = fromContext()
val pool = "%document/looks".path
val archive = "%document/sweep".path

val sweep = KeyframeSweep(
    keys, pool, archive,
    SweepConfig(
        seed = 20260916,
        count = 12,
        minArity = 2,
        maxArity = 3,
        // Below 1, a blend is mostly one key with the others as perturbation. Well above
        // it, every blend is the average of its participants — a night of mush.
        concentration = 0.7,
        extrapolationProbability = 0.3
    ),
    onRender = { m -> println("${m.id} ${m.status} — " + m.parents.joinToString(", ") { "${it.key} ${"%.2f".format(it.weight)}" }) }
)

// `frames { }` puts the run on the Runloop, so the callback can wait for real frames and
// the stop key cancels it between renders. The blend is already applied when it is called.
// `frames` rather than `start`: `start` has a receiver overload and a receiverless one, and
// a bare `{ … }` satisfies both, so `start { … }` is an overload resolution ambiguity —
// everywhere, not just here. Nothing in the repo calls it that way (every call site passes
// a callable reference, `start(renderer::run)`), which is why the trap is unspent.
frames {
    val report = sweep.run { render ->
        r.awaitFrames(6)
        render.captureStill(r, bundle.postStack.output(), settleFrames = 0)
        render.captureClip(r, bundle.postStack.output(), frames = 8, fps = 12, settleFrames = 0) { _, t ->
            // A small orbit *around* the blended camera, so the clip moves without
            // overwriting the lane the key set.
            val at = render.key["camera.at"] as afield.stdlib.linalg.Vector3f
            val a = (t - 0.5) * 0.5
            bundle.scene.camera.at.set(
                (at.x * kotlin.math.cos(a) - at.z * kotlin.math.sin(a)).toFloat(),
                at.y,
                (at.x * kotlin.math.sin(a) + at.z * kotlin.math.cos(a)).toFloat()
            )
            r.awaitFrames(2)
        }
    }
    // Interpolated, not passed bare: `println(anything)` in a box goes through Field's
    // object inspector, which renders a data class field by field and ignores a hand
    // written `toString`.
    println("$report")
    println("that is ${"%.0f".format(report.rendersPerHour())} renders an hour")
}
```

