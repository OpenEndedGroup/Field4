package afield.four.fabricated

import afield.four.Renderer
import afield.four.util.Camera
import afield.stdlib.linalg.Vector3f
import kotlinx.coroutines.delay
import kotlin.math.abs

/** Real GPU readback assertions. Run from a Field box below a live renderer; no placeholder captures. */
object PathTraceGpuSmoke {
    suspend fun run(renderer: Renderer) {
        val backends = mutableListOf(PathTracingRenderer.Backend.COMPUTE_BVH)
        if (renderer.device.supportsRayQuery) backends += PathTracingRenderer.Backend.RAY_QUERY
        val results = mutableListOf<FloatArray>()
        for (backend in backends) {
            val camera = Camera(renderer).also { it.at.set(0f,0f,3f); it.target.set(0f,0f,0f) }
            val missScene = PathTraceScene().apply {
                val m = material(PathTraceMaterial())
                triangle(Vector3f(100f,0f,0f),Vector3f(101f,0f,0f),Vector3f(100f,1f,0f),m)
            }
            PathTracingRenderer(renderer,missScene,PathTraceEnvironment.constant(0.5f,1f,2f),63,47,camera,backend).use { tracer ->
                tracer.indirectClamp = 0.001f // Directly visible HDR must not be clamped.
                tracer.install()
                awaitSamples(tracer,8)
                tracer.paused = true
                var pixels = tracer.readLinear()
                for (i in pixels.indices step 4) {
                    check(abs(pixels[i]-0.5f)<1e-5 && abs(pixels[i+1]-1f)<1e-5 && abs(pixels[i+2]-2f)<1e-5)
                    check(pixels[i+3] == 1f)
                }
                val previous = tracer.samples
                tracer.exposure = 2f
                check(tracer.samples == previous)
                tracer.environmentIntensity = 0.25f
                tracer.paused = false
                awaitReset(tracer,previous)
                tracer.paused = true
                pixels = tracer.readLinear()
                check(abs(pixels[0]-0.125f)<1e-5 && abs(pixels[2]-0.5f)<1e-5) { "Lighting reset retained old radiance" }
                tracer.paused = false
                awaitSamples(tracer,10)
                tracer.paused = true
                val beforeCamera = tracer.samples
                camera.at.x += 0.2f
                tracer.paused = false
                awaitReset(tracer,beforeCamera)
                tracer.paused = true
                check(tracer.samples < beforeCamera) { "Camera failed to reset accumulation" }
                tracer.showEnvironment = false
                tracer.background = afield.stdlib.linalg.Vector4f(0.2f, 0.4f, 0.8f, 0.5f)
                tracer.paused = false
                kotlinx.coroutines.delay(100)
                awaitSamples(tracer, 4)
                tracer.paused = true
                pixels = tracer.readLinear()
                for (j in pixels.indices step 4) {
                    check(abs(pixels[j]-0.1f)<1e-6 && abs(pixels[j+1]-0.2f)<1e-6 && abs(pixels[j+2]-0.4f)<1e-6 && pixels[j+3]==0.5f)
                }
                tracer.background = afield.stdlib.linalg.Vector4f(0f)
                tracer.paused = false
                // Observe a dispatch even if the old sample count was only one.
                kotlinx.coroutines.delay(100)
                awaitSamples(tracer, 4)
                tracer.paused = true
                check(tracer.readLinear().all { it == 0f }) { "Transparent background retained environment or old samples" }
                println("PASS GPU $backend: constant HDR, 63x47 edge workgroups, accumulation, lighting/camera resets")
            }
            val scene = PathTraceScene()
            val colors = listOf(floatArrayOf(2f,0f,0f),floatArrayOf(0f,3f,0f),floatArrayOf(0f,0f,4f),floatArrayOf(1f,2f,3f))
            for (row in 0..1) for (col in 0..1) {
                val color = colors[row*2+col]
                val material = scene.material(PathTraceMaterial.emissive(color[0],color[1],color[2]))
                val x = if (col==0) -20f else 0f
                val y = if (row==0) 0f else -20f
                scene.quad(Vector3f(x,y,0f),Vector3f(x+20,y,0f),Vector3f(x+20,y+20,0f),Vector3f(x,y+20,0f),material)
            }
            camera.at.set(0f,0f,3f)
            PathTracingRenderer(renderer,scene,PathTraceEnvironment.constant(0f),64,48,camera,backend).use { tracer ->
                tracer.indirectClamp=0.001f
                tracer.maxBounces=1; tracer.install(); awaitSamples(tracer,8); tracer.paused=true
                val pixels=tracer.readLinear(); results += pixels
                check(pixels.all { it.isFinite() && it >= 0f })
                for (row in 0..1) for (col in 0..1) {
                    val index=((12+row*24)*64+16+col*32)*4
                    for (channel in 0..2) check(abs(pixels[index+channel]-colors[row*2+col][channel])<1e-5) {
                        "GPU material/orientation mismatch row=$row col=$col channel=$channel: ${pixels[index+channel]}"
                    }
                }
                println("PASS GPU $backend: BVH traversal, triangle/material layout, HDR emission and image orientation")
            }
            // A white rough dielectric in a constant environment exercises BRDF/PDF/MIS code,
            // unlike the exact miss and emissive tests above. Spatial noise must fall with accumulation.
            val whiteScene = PathTraceScene().apply {
                val white = material(PathTraceMaterial.plastic(1f,1f,1f,0.4f))
                quad(Vector3f(-20f,-20f,0f),Vector3f(20f,-20f,0f),Vector3f(20f,20f,0f),Vector3f(-20f,20f,0f),white)
            }
            PathTracingRenderer(renderer,whiteScene,PathTraceEnvironment.constant(),64,48,camera,backend).use { tracer ->
                tracer.indirectClamp=0.001f // All lighting on this isolated surface is direct.
                tracer.maxBounces=2; tracer.install(); awaitSamples(tracer,8); tracer.paused=true
                fun centralMoments(pixels: FloatArray): Pair<Double,Double> {
                    check(pixels.all { it.isFinite() && it >= 0f })
                    val values=(12 until 36).flatMap { y -> (16 until 48).map { x -> pixels[(y*64+x)*4].toDouble() } }
                    val mean=values.average()
                    return mean to values.sumOf { (it-mean)*(it-mean) }/values.size
                }
                val initial=centralMoments(tracer.readLinear())
                tracer.samplesPerFrame=16; tracer.paused=false; awaitSamples(tracer,1024); tracer.paused=true
                val converged=centralMoments(tracer.readLinear())
                check(converged.first in 0.9..1.05) { "White-furnace energy out of range: $converged" }
                check(converged.second < initial.second*0.25) { "Accumulation did not reduce variance: $initial -> $converged" }
                tracer.showEnvironment = false
                tracer.background = afield.stdlib.linalg.Vector4f(0f)
                tracer.paused = false
                awaitReset(tracer, tracer.samples)
                awaitSamples(tracer, 1024)
                tracer.paused = true
                val hidden = centralMoments(tracer.readLinear())
                check(abs(hidden.first-converged.first)<0.01) { "Hiding background changed surface lighting: $converged -> $hidden" }
                check(tracer.readLinear().filterIndexed { index, _ -> index % 4 == 3 }.all { it == 1f })
                println("PASS GPU $backend: white-furnace mean=${converged.first}, variance ${initial.second} -> ${converged.second}")
            }
        }
        val replacementScene = PathTraceScene().apply {
            val m = material(PathTraceMaterial())
            triangle(Vector3f(100f,0f,0f),Vector3f(101f,0f,0f),Vector3f(100f,1f,0f),m)
        }
        val first = PathTracingRenderer(renderer, replacementScene, PathTraceEnvironment.constant(), 16, 16).install()
        first.blitToScreen()
        val second = PathTracingRenderer(renderer, replacementScene, PathTraceEnvironment.constant(0.25f), 16, 16).install()
        second.blitToScreen()
        first.close()
        check(renderer.computeList.others["path-tracer"] === second)
        check(renderer.renderList.preTargets.containsKey("path-tracer-output"))
        check(renderer.renderList.modules.containsKey("path-tracer-display"))
        awaitSamples(second, 4)
        second.paused = true
        check(abs(second.readLinear()[0]-0.25f)<1e-6)
        second.close()
        check("path-tracer" !in renderer.computeList.others && "path-tracer-output" !in renderer.renderList.preTargets)
        println("PASS named replacement, stale close, separate RTT presentation, hidden/transparent backgrounds and unchanged surface lighting")
        for (backend in backends) {
            runTransparency(renderer,backend)
            PathTraceGltfGpuSmoke.run(renderer,backend)
        }
        if (results.size==2) {
            for (i in results[0].indices) if (i%4!=3) check(abs(results[0][i]-results[1][i])<0.15f) { "Compute/ray query disagree at $i" }
            println("PASS GPU backend agreement")
        } else println("Ray-query device execution unavailable; shader compilation is checked separately")
        println("PASS path-tracing GPU smoke")
    }

    private suspend fun runTransparency(renderer: Renderer, backend: PathTracingRenderer.Backend) {
        fun slab(material: PathTraceMaterial, thickness: Float): PathTraceScene = PathTraceScene().apply {
            val m=material(material)
            quad(Vector3f(-100f,-100f,0f),Vector3f(100f,-100f,0f),Vector3f(100f,100f,0f),Vector3f(-100f,100f,0f),m)
            quad(Vector3f(-100f,-100f,-thickness),Vector3f(-100f,100f,-thickness),
                Vector3f(100f,100f,-thickness),Vector3f(100f,-100f,-thickness),m)
        }
        suspend fun measure(scene: PathTraceScene, camera: Camera, label: String, expected: List<Float>): FloatArray {
            PathTracingRenderer(renderer,scene,PathTraceEnvironment.constant(),32,24,camera,backend).use { tracer ->
                tracer.maxBounces=12; tracer.samplesPerFrame=16; tracer.install()
                awaitSamples(tracer,1024); tracer.paused=true
                val pixels=tracer.readLinear()
                check(pixels.all { it.isFinite() && it>=0f }) { "Nonfinite or negative glass result: $label: ${pixels.withIndex().filter { !it.value.isFinite() || it.value < 0f }.take(8)}" }
                val means=FloatArray(3) { c -> (0 until 32*24).sumOf { pixels[it*4+c].toDouble() }.toFloat()/(32*24) }
                expected.forEachIndexed { c,e -> check(abs(means[c]-e)<0.015f) { "$label channel=$c expected=$e actual=${means[c]}" } }
                println("PASS GPU $backend: $label RGB=${means.toList()}")
                return pixels
            }
        }
        val camera=Camera(renderer).also { it.at.set(0f,0f,3f); it.target.set(0f,0f,0f); it.p.fov=0.002 }
        camera.at.set(0.37f,0.19f,3f); camera.target.set(0.37f,0.19f,0f)
        // Keep this narrow view away from the oversized quad diagonal, where float precision
        // can let a ray slip between triangles.
        // Two index-matched interfaces put the environment at bounce two, with no stochastic
        // Fresnel choice. This isolates the indirect clamp, its hue preservation and reset behavior.
        PathTracingRenderer(renderer,slab(PathTraceMaterial.glass(ior=1f),1f),
            PathTraceEnvironment.constant(2f,1f,0.5f),32,24,camera,backend).use { tracer ->
            tracer.maxBounces=4; tracer.install(); awaitSamples(tracer,32); tracer.paused=true
            for (invalid in listOf(-1f,Float.NaN,Float.POSITIVE_INFINITY)) {
                check(runCatching { tracer.indirectClamp=invalid }.isFailure)
            }
            check(tracer.indirectClamp==0f)
            suspend fun verifyClamp(limit: Float) {
                val old=tracer.samples
                tracer.indirectClamp=limit; tracer.paused=false
                awaitReset(tracer,old); awaitSamples(tracer,32); tracer.paused=true
                val scale=if (limit==0f) 1f else limit/(2f*0.2126f+0.7152f+0.5f*0.0722f)
                val expected=floatArrayOf(2f*scale,scale,0.5f*scale,1f)
                val pixels=tracer.readLinear()
                for (i in pixels.indices) check(abs(pixels[i]-expected[i%4])<0.002f) {
                    "Indirect clamp=$limit channel=${i%4}: expected=${expected[i%4]} actual=${pixels[i]}"
                }
            }
            verifyClamp(0.5f)
            verifyClamp(0f)
            println("PASS GPU $backend: indirect clamp, hue preservation, invalid limits and accumulation reset")
        }
        camera.at.set(0f,0f,3f); camera.target.set(0f,0f,0f)
        measure(slab(PathTraceMaterial.glass(),1f),camera,"clear slab Fresnel and delta MIS",listOf(1f,1f,1f))
        val tinted=PathTraceMaterial.glass(ior=1f,attenuationColor=Vector3f(0.5f,0.8f,1f))
        measure(slab(tinted,1f),camera,"Beer-Lambert unit thickness",listOf(0.5f,0.8f,1f))
        measure(slab(tinted,2f),camera,"Beer-Lambert double thickness",listOf(0.25f,0.64f,1f))
        val pane=PathTraceScene().apply {
            val m=material(PathTraceMaterial.thinGlass(tint=Vector3f(0.5f,0.8f,1f)))
            quad(Vector3f(-100f,-100f,0f),Vector3f(100f,-100f,0f),Vector3f(100f,100f,0f),Vector3f(-100f,100f,0f),m)
        }
        measure(pane,camera,"thin pane Fresnel and tint",listOf(7f/13f,10.6f/13f,1f))
        camera.at.set(0f,0f,-3f)
        measure(pane,camera,"thin pane reverse face",listOf(7f/13f,10.6f/13f,1f))
        camera.at.set(0f,0f,3f); camera.p.fov=0.3
        for (roughness in listOf(0.15f,0.35f,0.6f)) {
            PathTracingRenderer(renderer,slab(PathTraceMaterial.glass(roughness=roughness),1f),
                PathTraceEnvironment.constant(),32,24,camera,backend).use { tracer ->
                fun moments(p: FloatArray): Pair<Double,Double> {
                    check(p.all { it.isFinite() && it>=0f })
                    val values=(0 until 32*24).map { p[it*4].toDouble() }
                    val mean=values.average()
                    return mean to values.sumOf { (it-mean)*(it-mean) }/values.size
                }
                tracer.maxBounces=12; tracer.install(); awaitSamples(tracer,8); tracer.paused=true
                val initial=moments(tracer.readLinear())
                tracer.samplesPerFrame=16; tracer.paused=false; awaitSamples(tracer,2048); tracer.paused=true
                val final=moments(tracer.readLinear())
                check(final.first in 0.8..1.03) { "Rough glass energy: roughness=$roughness $final" }
                check(final.second < initial.second*0.15) { "Rough glass did not converge: $initial -> $final" }
                println("PASS GPU $backend: frosted roughness=$roughness mean=${final.first}, variance ${initial.second} -> ${final.second}")
            }
        }
        camera.p.fov=0.002
        camera.at.set(0f,0f,-0.5f); camera.target.set(0f,0f,0f)
        measure(slab(PathTraceMaterial.glass(),1f),camera,"camera inside glass radiance scaling",listOf(2.25f,2.25f,2.25f))
        camera.target.set(1.7320508f,0f,0.5f)
        measure(slab(PathTraceMaterial.glass(),1f),camera,"total internal reflection",listOf(0f,0f,0f))
    }

    /** Thin-lens focus, coverage, jitter, and automatic reset checks on real GPU pixels. */
    suspend fun runCamera(renderer: Renderer) {
        val backends = listOf(PathTracingRenderer.Backend.COMPUTE_BVH) +
            if (renderer.device.supportsRayQuery) listOf(PathTracingRenderer.Backend.RAY_QUERY) else emptyList()
        for (backend in backends) {
            val camera = Camera(renderer).also {
                it.at.set(0f,0f,3f); it.target.set(0f,0f,0f); it.p.fov=Math.PI/2
            }
            val scene=PathTraceScene().apply {
                val white=material(PathTraceMaterial.emissive(1f,1f,1f))
                quad(Vector3f(0.035f,-20f,0f),Vector3f(20f,-20f,0f),
                    Vector3f(20f,20f,0f),Vector3f(0.035f,20f,0f),white)
            }
            PathTracingRenderer(renderer,scene,PathTraceEnvironment.constant(0f),48,32,camera,backend).use { tracer ->
                tracer.maxBounces=1; tracer.samplesPerFrame=16; tracer.pixelJitter=0f
                tracer.showEnvironment=false; tracer.background=afield.stdlib.linalg.Vector4f(0f)
                tracer.install()
                suspend fun capture(change: () -> Unit = {}): FloatArray {
                    val old=tracer.samples
                    change()
                    tracer.paused=false
                    if (old>0) awaitReset(tracer,old)
                    awaitSamples(tracer,512); tracer.paused=true
                    return tracer.readLinear().also { pixels ->
                        check(pixels.all { it.isFinite() && it in 0f..1.00001f })
                        for (i in pixels.indices step 4) check(abs(pixels[i]-pixels[i+3])<1e-5) {
                            "Lens coverage must match premultiplied white radiance"
                        }
                    }
                }
                fun softPixels(p: FloatArray)=p.indices.count { it%4==0 && p[it] in 0.02f..0.98f }
                val pinhole=capture()
                check(softPixels(pinhole)==0)
                val focused=capture { tracer.apertureRadius=1.2f }
                check(focused.indices.all { abs(focused[it]-pinhole[it])<1e-5 }) {
                    "A wide aperture blurred the focus plane (including off-axis pixels)"
                }
                val defocused=capture { tracer.focusDistance=6f }
                check(softPixels(defocused)>128) { "Changing focus did not blur the edge" }
                check(abs(defocused.average()-focused.average())<0.02) { "Aperture changed exposure" }
                val targetFocused=capture { tracer.focusDistance=null; camera.target.z=-3f }
                check(targetFocused.indices.all { abs(targetFocused[it]-defocused[it])<0.03f })
                val retargeted=capture { camera.target.z=0f }
                check(retargeted.indices.all { abs(retargeted[it]-pinhole[it])<1e-5 }) {
                    "Changing only target distance failed to reset focus history"
                }
                val jittered=capture { tracer.apertureRadius=0f; tracer.pixelJitter=1f }
                check(softPixels(jittered) in 1..64) { "Pixel jitter did not antialias the subpixel edge" }
                val centred=capture { tracer.pixelJitter=0f }
                check(centred.contentEquals(pinhole)) { "Turning jitter off retained old samples" }
                for (bad in listOf(-1f,Float.NaN,Float.POSITIVE_INFINITY)) {
                    check(runCatching { tracer.apertureRadius=bad }.isFailure)
                    check(runCatching { tracer.focusDistance=bad }.isFailure)
                    check(runCatching { tracer.pixelJitter=bad }.isFailure)
                }
                check(runCatching { tracer.focusDistance=0f }.isFailure)
                check(runCatching { tracer.pixelJitter=1.01f }.isFailure)
                println("PASS GPU $backend camera: sharp focus plane, ${softPixels(defocused)} defocused pixels, " +
                    "${softPixels(jittered)} antialiased pixels, premultiplied coverage and focus/jitter resets")
            }
        }
    }

    private suspend fun awaitReset(tracer: PathTracingRenderer, old: Int) {
        val deadline=System.nanoTime()+20_000_000_000L
        while (tracer.samples>=old || tracer.samples==0) {
            check(System.nanoTime()<deadline) { "Timed out waiting for accumulation reset" }; delay(1)
        }
    }
    suspend fun awaitSamples(tracer: PathTracingRenderer, count: Int) {
        val deadline=System.nanoTime()+60_000_000_000L
        while (tracer.samples<count) {
            check(System.nanoTime()<deadline) { "Timed out waiting for $count samples; got ${tracer.samples}" }; delay(5)
        }
    }
}

/** Small reusable material study; the environment file is optional, with a constant white map as fallback. */
object PathTraceDemo {
    fun install(renderer: Renderer, environmentPath: String? = null, width: Int = 640, height: Int = 360): PathTracingRenderer {
        val scene=PathTraceScene()
        val floor=scene.material(PathTraceMaterial.diffuse(0.48f,0.48f,0.48f))
        val red=scene.material(PathTraceMaterial.plastic(0.65f,0.055f,0.025f,0.22f))
        val gold=scene.material(PathTraceMaterial.metal(0.95f,0.7f,0.3f,0.12f))
        val rough=scene.material(PathTraceMaterial.metal(0.55f,0.65f,0.8f,0.55f))
        scene.quad(Vector3f(-15f,0f,-15f),Vector3f(-15f,0f,15f),Vector3f(15f,0f,15f),Vector3f(15f,0f,-15f),floor)
        scene.sphere(Vector3f(-1.7f,0.8f,0f),0.8f,red,48,24)
        scene.sphere(Vector3f(0f,0.8f,0f),0.8f,gold,48,24)
        scene.sphere(Vector3f(1.7f,0.8f,0f),0.8f,rough,48,24)
        val camera=Camera(renderer).also { it.at.set(3.5f,2.4f,7.5f); it.target.set(0f,0.65f,0f); it.p.fov=0.62 }
        return PathTracingRenderer(renderer,scene,environmentPath?.let { PathTraceEnvironment.fromFile(it) }
            ?: PathTraceEnvironment.constant(),width,height,camera).install()
    }
}
