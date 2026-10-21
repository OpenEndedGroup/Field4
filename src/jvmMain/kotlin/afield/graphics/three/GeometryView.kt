@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.graphics.three

import afield.document.graph.Box
import afield.geometry.mesh.GeometryBuilder
import afield.geometry.mesh.GeometryScene
import afield.geometry.mesh.GeometrySide
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
        }
    }
}
