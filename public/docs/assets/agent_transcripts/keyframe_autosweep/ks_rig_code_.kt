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
