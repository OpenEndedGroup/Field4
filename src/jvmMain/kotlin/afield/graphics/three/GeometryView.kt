@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.graphics.three

import afield.document.graph.Box
import afield.geometry.mesh.GeometryBuilder
import afield.geometry.mesh.GeometryScene
import afield.geometry.mesh.GeometrySide
import afield.ai.tools.agentExtension
import afield.geometry.mesh.toCapturedMesh
import afield.runtime.lifecycle.Frames
import afield.runtime.lifecycle.frames

/**
 * Draw a [GeometryScene] in this view: every part as a mesh with its own material, the studio look,
 * the scene's background, and the toolbox's three-quarter camera.
 *
 * The camera is a [camera] spec, so re-running the recipe — or animating it — keeps wherever you
 * orbited to.
 */
fun ThreeSceneBuilder.geometry(scene: GeometryScene) {
    for (part in scene.parts) {
        val m = part.material
        val c = m.linearColor
        val e = m.linearEmissive
        mesh(
            part.mesh,
            label = part.name,
            id = part.name,
            wireframe = m.wireframe,
            cullBackFaces = m.side != GeometrySide.DOUBLE,
            flatShading = m.flat,
            opacity = m.opacity,
            material = ThreeSurfaceMaterial(
                color = c,
                roughness = m.roughness.toFloat(),
                metalness = m.metalness.toFloat(),
                emissive = e,
                emissiveIntensity = m.emissiveIntensity.toFloat(),
            ),
        )
    }
    val v = scene.view
    studio(ground = v.ground, grid = v.grid, groundY = v.groundY)
    background(v.background)
    camera(yaw = 43.0, pitch = 22.5, fieldOfView = 42.0, padding = 1.4)
}

/**
 * Run a [GeometryBuilder] recipe and show the result in a studio-lit `three` view.
 *
 * The box version of the madp geometry toolbox: the block is its `build()`, and running the box is
 * pressing compile. It returns the [GeometryScene], whose summary is the triangle count.
 *
 * ```kotlin
 * geometry {
 *     mat(roughness = 0.42, metalness = 0.06)
 *     color(hsl(195, 0.8, 0.6))
 *     tri(vec(-1, 0, 0), vec(1, 0, 0), vec(0, 1.5, 0))
 * }
 * ```
 *
 * @param channel the output channel the view opens in.
 * @param name the view's name within this box; two views in one box need two names.
 * @param height the view's height in pixels.
 * @param width the view's width in pixels, or `null` to fill the output column.
 */
fun Box.geometry(
    channel: String = "main",
    name: String = "geometry",
    height: Int = 460,
    width: Int? = 680,
    block: GeometryBuilder.() -> Unit,
): GeometryScene {
    val scene = GeometryBuilder().apply(block).build()
    three(channel = channel, name = name, height = height, width = width) { geometry(scene) }
    recordGeometryView(threeViewerUid(this, name), scene)
    return scene
}

/**
 * Run a [GeometryBuilder] recipe [fps] times a second with [GeometryBuilder.t] moving, and show each
 * result in the same studio-lit `three` view: the toolbox's **Animate** button.
 *
 * Return it from the box (it is a [Frames]) and launch the box; stopping the box stops the animation.
 * The view keeps its camera from frame to frame, so you can orbit while it moves. Nothing requires the
 * topology to stay fixed while it plays, though [afield.geometry.mesh.writeGlbAnimation] needs it to.
 *
 * ```kotlin
 * animateGeometry {
 *     surface(64, 28) { u, v ->
 *         val x = (u - 0.5) * 6.8
 *         val z = (v - 0.5) * 2.8
 *         val y = sin(x * 2.8 + t * 2.2) * 0.34
 *         vertex(x, y, z, hsl(188 + y * 120, 0.82, 0.48))
 *     }
 * }
 * ```
 */
fun Box.animateGeometry(
    fps: Double = 30.0,
    channel: String = "main",
    name: String = "geometry",
    height: Int = 460,
    width: Int? = 680,
    block: GeometryBuilder.() -> Unit,
): Frames {
    require(fps.isFinite() && fps > 0) { "fps must be positive, got $fps" }
    val uid = threeViewerUid(this, name)
    // The first frame is drawn now, so a box that is run rather than launched still shows its t = 0.
    var scene = GeometryBuilder(0.0, 0, 1 / fps).apply(block).build()
    three(channel = channel, name = name, height = height, width = width) { geometry(scene) }
    recordGeometryView(uid, scene)
    return frames {
        val start = System.nanoTime()
        var last = 0.0
        var count = 0
        while (frame()) {
            val t = (System.nanoTime() - start) / 1e9
            if (t - last < 1 / fps) continue
            count++
            scene = GeometryBuilder(t, count, t - last).apply(block).build()
            last = t
            val builder = ThreeSceneBuilder(48).apply { geometry(scene) }
            threeViewFor(uid).update(builder.payload(uid, frameWidth = width, frameHeight = height))
            recordGeometryView(uid, scene)
        }
    }
}

// -------------------------------------------------------------------------------------------------
// AI4
// -------------------------------------------------------------------------------------------------

/** The last scene each geometry view drew, so an agent can be told what it is looking at. */
private class GeometryViewRecord(val box: java.lang.ref.WeakReference<Box>, val uid: String, @Volatile var scene: GeometryScene)

private val geometryViews = java.util.concurrent.ConcurrentHashMap<String, GeometryViewRecord>()

private fun Box.recordGeometryView(uid: String, scene: GeometryScene) {
    geometryViews.compute(uid) { _, old ->
        old?.takeIf { it.box.get() === this }?.also { it.scene = scene } ?: GeometryViewRecord(java.lang.ref.WeakReference(this), uid, scene)
    }
}

/**
 * The [GeometryBuilder] language, written for a model: what the madp toolbox's **Copy Guide** button put
 * on the clipboard. [geometryAgent] gives it to this document's AI4 chat before every turn.
 */
val GEOMETRY_GUIDE: String = """
# geometry { } — models as recipes of triangles

A box draws a model with `geometry { … }` (or `animateGeometry { … }`, which re-runs the block 30 times a
second with `t` in seconds). The block is Kotlin and stateful; running the box rebuilds the model.

State, applied to what follows:
- `mesh("name")` names the part that follows. Parts with the same name and material are drawn as one.
- `mat(color = "#ffffff", roughness = 0.7, metalness = 0.04, emissive = "#000000", emissiveIntensity = 0.0,
  opacity = 1.0, side = GeometrySide.DOUBLE, wireframe = false, flat = true)` — named arguments, merged into
  the current material. Changing it after triangles were emitted starts a new part.
- `color(hsl(195, 0.8, 0.6))` or `color("#ffe4a0")` — the colour for vertices without their own.
- `push()` / `pop()` save and restore name, material, colour and transform;
  `translate(x, y, z)` (or a `Vec3`), `rotateX/Y/Z(radians)`, `scale(s)` / `scale(x, y, z)`, `identity()`.

Emitters (everything is triangles, through the transform):
- `tri(a, b, c, flip = false)`, `quad(a, b, c, d)` — corners are `vec(x, y, z)` or `vertex(x, y, z, colour)`.
- `surface(cols, rows) { u, v -> vertex(x, y, z, hsl(…)) }` — a grid sampled over u, v in 0..1.
- `revolve(listOf(profile(r, y, colour), …), segments)` — a lathe round the y axis.
- `tube(segments, sides, closed = true) { s -> section(vec(…), radius = 0.1, color = hsl(…)) }`.
- `box(center, size)` with `size` a `Vec3` or a number.

Helpers: `vec`, `mix(a, b, t)`, `polar(r, angle, y)`, `spherical(r, theta, phi)`, `TAU`, `seed(n)`,
`rand()`, `rand(max)`, `rand(min, max)`, `randInt(min, max)`, `noise(x, y)` (Perlin, about -1..1), and
`t`, `frame`, `dt` for animation. `view(background = "#05080a", ground = true, grid = true, groundY = -1.25)`.

Colours: Field's `hsl(h, s, l)` (hue in degrees or turns), `rgb(…)`, or CSS hex strings. Models are
built to stand on a ground at y = -1.25 and be a few units across. Use `geometry.look` to see the result
after you run a box.
""".trimIndent()

/**
 * Bridge this document's geometry boxes to AI4: the chat agent learns the [GeometryBuilder] language
 * ([GEOMETRY_GUIDE], before every turn) and gets a `geometry.look` tool that returns a picture of a
 * geometry view as it is on screen — the toolbox's screenshot-to-code loop, with Field's own chat.
 * `/geometry` in the chat shows the guide.
 *
 * Call it once, in any box of the document; every [geometry] and [animateGeometry] view in the
 * document can then be looked at by its box's name. A view that is not open is drawn by the CPU
 * rasterizer instead, without the studio's lighting, and the agent is told so.
 */
fun Box.geometryAgent() {
    val owner = this
    agentExtension("geometry") {
        context("geometry language", changesOften = false) { GEOMETRY_GUIDE }
        command("geometry", "the geometry { } language") { GEOMETRY_GUIDE }
        tool("geometry.look") {
            describe(
                "Look at geometry { } boxes in this document as they are on screen: a picture of each view, " +
                    "with its triangle and part counts. Run a box after editing it, then look."
            )
            val target by param<String>("A box's name or id; empty for every geometry view in this document", default = "")
            action {
                val scope = threeDocumentScope(owner)
                val wanted = target().trim()
                val views = geometryViews.values.mapNotNull { r -> r.box.get()?.let { it to r } }
                    .filter { (b, _) -> threeDocumentScope(b) == scope }
                    .filter { (b, _) -> wanted.isEmpty() || b.id == wanted || b.name == wanted }
                if (views.isEmpty()) return@action toolError(
                    if (wanted.isEmpty()) "No geometry view in this document has drawn yet; run a box that calls geometry { }."
                    else "No geometry view belongs to a box named or with id \"$wanted\"; run it first."
                )
                val urls = ArrayList<String>()
                val notes = ArrayList<String>()
                for ((b, r) in views.sortedBy { it.first.name }) {
                    val live = threeViewFor(r.uid).snapshot()
                    val png = live ?: cpuPreview(r.scene)
                    urls += "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png)
                    notes += "${b.name.ifEmpty { b.id }}: ${r.scene.triangleCount} triangles in ${r.scene.parts.size} " +
                        "part(s)${if (live == null) " (the view is not open: CPU preview, plain lighting)" else ""}"
                }
                images(urls, notes.joinToString("; "))
            }
        }
    }
}

/** The scene through the CPU rasterizer, its linear vertex colours encoded back to sRGB for it. */
private fun cpuPreview(scene: GeometryScene): ByteArray {
    val mesh = scene.toTriMesh()
    val colors = mesh.colors?.let { c -> FloatArray(c.size) { linearToSrgb(c[it]) } }
    val shown = afield.geometry.mesh.TriMesh(mesh.positions, mesh.indices, mesh.normals, null, colors)
    return afield.graphics.visionos.VisionGeometryPreview.renderPng(
        shown.toCapturedMesh(),
        afield.graphics.visionos.VisionGeometryPreview.Camera(yawDegrees = 43.0, pitchDegrees = 22.5),
        afield.graphics.visionos.VisionGeometryPreview.Style(wireframe = false, cullBackFaces = false, caption = ""),
    )
}

private fun linearToSrgb(c: Float): Float =
    if (c <= 0.0031308f) 12.92f * c else (1.055f * Math.pow(c.toDouble(), 1 / 2.4).toFloat() - 0.055f)
