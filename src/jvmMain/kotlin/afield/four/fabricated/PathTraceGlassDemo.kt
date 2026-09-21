package afield.four.fabricated

import afield.four.Renderer
import afield.four.util.Camera
import afield.stdlib.linalg.Vector3f

/**
 * All four of the path tracer's glass materials in one scene: clear, absorbing, frosted, and a
 * thin pane.
 *
 * `public/docs/path_tracing.md` prints this scene as its glass figure and
 * `dev_docs/agents/transcripts/path_tracing_glass.yaml` launches it, so what the file builds is
 * what those two are written about. Left to right: a sphere of `PathTraceMaterial.glass()`, one
 * given an `attenuationColor`, one given a `roughness` of 0.32, and a quad of `thinGlass`.
 *
 * Most of the geometry here is background, since what a transparent material shows is mostly
 * whatever is behind it. The floor is a checkerboard of 0.55-unit tiles and the wall behind the
 * spheres is eighteen horizontal stripes, both built from the same two diffuse greys, one ten times
 * the reflectance of the other. Each material moves that pattern its own way: refraction displaces
 * the stripes, absorption tints them by how far the ray travelled inside the solid, roughness
 * scatters them, and the thin pane leaves their positions where they are, because `thinGlass`
 * transmits per crossing and has no thickness to refract across.
 *
 * The returned renderer is set to ten bounces rather than the default five, because a ray that
 * enters and leaves a sphere has spent two of them before reaching anything else, and to an
 * exposure of 1.5 for a floor this dark. With no `environmentPath` the light is a constant white
 * environment of radiance one; a path is read by `PathTraceEnvironment.fromFile`, which takes EXR,
 * Radiance and ordinary image files.
 */

/** Clear glass, absorbing green glass, frosted glass and a thin blue pane, from left to right. */
object PathTraceGlassDemo {
    /**
     * Builds the scene, points a camera at it and installs a [PathTracingRenderer] under
     * `renderer`, returning it so a box can `blitToScreen` or reconfigure it.
     */
    fun install(renderer: Renderer, environmentPath: String? = null, width: Int = 640, height: Int = 360): PathTracingRenderer {
        val scene=PathTraceScene()
        val dark=scene.material(PathTraceMaterial.diffuse(0.055f,0.055f,0.055f))
        val light=scene.material(PathTraceMaterial.diffuse(0.55f,0.55f,0.55f))
        for (x in -10 until 10) for (z in -6 until 8) {
            val a=x*0.55f; val b=z*0.55f; val m=if ((x+z) and 1 == 0) light else dark
            scene.quad(Vector3f(a,0f,b),Vector3f(a,0f,b+0.55f),Vector3f(a+0.55f,0f,b+0.55f),Vector3f(a+0.55f,0f,b),m)
        }
        // Narrow stripes reveal refraction and frosting against a common background.
        for (i in 0 until 18) {
            val y=i*0.18f
            scene.quad(Vector3f(-6f,y,-2.5f),Vector3f(6f,y,-2.5f),Vector3f(6f,y+0.18f,-2.5f),
                Vector3f(-6f,y+0.18f,-2.5f),if (i and 1 == 0) light else dark)
        }
        val clear=scene.material(PathTraceMaterial.glass())
        val green=scene.material(PathTraceMaterial.glass(attenuationColor=Vector3f(0.45f,0.9f,0.65f)))
        val frosted=scene.material(PathTraceMaterial.glass(roughness=0.32f))
        val sheet=scene.material(PathTraceMaterial.thinGlass(tint=Vector3f(0.65f,0.85f,1f)))
        scene.sphere(Vector3f(-2.7f,0.8f,0f),0.8f,clear,48,24)
        scene.sphere(Vector3f(-0.9f,0.8f,0f),0.8f,green,48,24)
        scene.sphere(Vector3f(0.9f,0.8f,0f),0.8f,frosted,48,24)
        scene.quad(Vector3f(2f,0.05f,0.1f),Vector3f(3.4f,0.05f,0.1f),Vector3f(3.4f,1.7f,0.1f),Vector3f(2f,1.7f,0.1f),sheet)
        val camera=Camera(renderer).also { it.at.set(0f,2.7f,9.5f); it.target.set(0f,0.8f,0f); it.p.fov=0.52 }
        return PathTracingRenderer(renderer,scene,environmentPath?.let(PathTraceEnvironment::fromFile)
            ?: PathTraceEnvironment.constant(),width,height,camera).also {
            it.maxBounces=10; it.exposure=1.5f; it.install()
        }
    }
}
