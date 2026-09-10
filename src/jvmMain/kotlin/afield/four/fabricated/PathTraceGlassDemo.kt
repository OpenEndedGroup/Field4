package afield.four.fabricated

import afield.four.Renderer
import afield.four.util.Camera
import afield.stdlib.linalg.Vector3f

/** Clear glass, absorbing green glass, frosted glass and a thin blue pane, from left to right. */
object PathTraceGlassDemo {
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
