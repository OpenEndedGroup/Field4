package afield.four.fabricated

import afield.four.Renderer
import afield.four.Target
import afield.four.util.Camera
import afield.four.util.KeyCamera
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.tan
import kotlin.math.exp

/** Real GPU history/visibility checks, including a measured moving-camera noise comparison. */
object PathTraceTemporalGpuSmoke {
    private suspend fun step(tracer: PathTracingRenderer) {
        tracer.paused=false
        val deadline=System.nanoTime()+30_000_000_000L
        while (!tracer.paused) { check(System.nanoTime()<deadline) { "Temporal dispatch timed out" }; delay(1) }
    }
    private fun latch(tracer: PathTracingRenderer) {
        tracer.paused=true
        tracer.materialModule.current.after["temporal-test-step"] = Target { _, _ -> tracer.paused=true }
        tracer.install()
    }
    private fun centre(p: FloatArray, stride: Int=1): List<Double> =
        (8 until 24).flatMap { y -> (8 until 40).map { x -> p[(y*48+x)*stride].toDouble() } }
    private fun variance(p: FloatArray): Double {
        val v=centre(p,4);val mean=v.average();return v.sumOf { (it-mean)*(it-mean) }/v.size
    }
    suspend fun runFog(renderer: Renderer) {
        val backends=listOf(PathTracingRenderer.Backend.COMPUTE_BVH) +
            if (renderer.device.supportsRayQuery) listOf(PathTracingRenderer.Backend.RAY_QUERY) else emptyList()
        for (backend in backends) {
            val camera=Camera(renderer).also { it.at.set(0f,0f,3f);it.target.set(0f,0f,0f);it.p.fov=0.7 }
            val scene=PathTraceScene().apply {
                val m=material(PathTraceMaterial.emissive(2f,0.25f,0.5f))
                quad(Vector3f(-200f,-200f,0f),Vector3f(200f,-200f,0f),Vector3f(200f,200f,0f),Vector3f(-200f,200f,0f),m)
            }
            PathTracingRenderer(renderer,scene,PathTraceEnvironment.constant(2f,0.5f,0.25f),9,9,camera,backend).use { t ->
                // Isolate the analytic fog curve from the emitter's dielectric reflection of the environment.
                t.pixelJitter=0f;t.maxBounces=1;t.environmentIntensity=0f;latch(t)
                val base=floatArrayOf(2f,0.25f,0.5f)
                fun expect(rgb: FloatArray, alpha: Float=1f) {
                    val pixels=t.readLinear()
                    check(pixels.all { it.isFinite() })
                    val actual=pixels.copyOfRange(160,164)
                    check((0..2).all { abs(actual[it]-rgb[it])<2e-4f } && abs(actual[3]-alpha)<1e-6f) {
                        "Fog RGB/alpha: ${actual.toList()}, expected ${rgb.toList()}, $alpha"
                    }
                }
                fun fogged(depth: Float): FloatArray {
                    val transmission=exp(-t.fogDensity*(depth-t.fogStartDistance).coerceAtLeast(0f))
                    val c=t.fogColor;val color=floatArrayOf(c.x,c.y,c.z)
                    return FloatArray(3) { base[it]*transmission+color[it]*(1f-transmission) }
                }
                step(t);expect(base)
                val fog=Vector3f(0.1f,0.3f,0.7f);t.fogColor=fog;fog.x=10f
                check(t.fogColor.x==0.1f);t.fogColor.x=20f;check(t.fogColor.x==0.1f)
                t.fogDensity=0.4f;step(t);expect(fogged(3f));check(t.samples==1)
                repeat(4) { step(t) };expect(fogged(3f));check(t.samples==5)
                t.fogStartDistance=3f;step(t);expect(base);check(t.samples==1)
                t.fogStartDistance=1f;step(t);expect(fogged(3f))
                t.fogDensity=0f;step(t);expect(base)
                // Raw history must survive dense fog, then be fogged at the new camera distance.
                t.temporalReuse=true;t.temporalMotionSensitivity=0f;t.temporalRoughnessSensitivity=0f
                t.fogDensity=1f;t.fogStartDistance=0f;camera.at.z=100f;t.samplesPerFrame=16;step(t)
                expect(fogged(100f));camera.at.z=1f;t.samplesPerFrame=1;step(t)
                check(t.readHistorySampleCounts()[40]>15f) { "Fog test did not retain history" }
                expect(fogged(1f));repeat(4) { step(t) };expect(fogged(1f))
                t.fogColor=Vector3f(0.4f,0.1f,0.2f);step(t);expect(fogged(1f))
                check(t.readHistorySampleCounts().all { it==1f }) { "Fog colour edit retained stale history" }
                camera.lookAlong(Vector3f(0f,0f,3f),Vector3f(0f,0f,1f));t.environmentIntensity=1f;step(t)
                expect(floatArrayOf(0.4f,0.1f,0.2f))
                t.fogBackground=false;step(t);expect(floatArrayOf(2f,0.5f,0.25f))
                t.showEnvironment=false;t.background=Vector4f(0.2f,0.4f,0.8f,0.25f);step(t)
                expect(floatArrayOf(0.05f,0.1f,0.2f),0.25f)
                t.fogBackground=true;step(t);expect(floatArrayOf(0.1f,0.025f,0.05f),0.25f)
                t.background=Vector4f(0f);step(t);expect(FloatArray(3),0f)
                t.fogDensity=0f;step(t);expect(FloatArray(3),0f)
                for (bad in listOf(-1f,Float.NaN,Float.POSITIVE_INFINITY)) {
                    check(runCatching { t.fogDensity=bad }.isFailure)
                    check(runCatching { t.fogStartDistance=bad }.isFailure)
                    check(runCatching { t.fogColor=Vector3f(bad,0f,0f) }.isFailure)
                }
                println("PASS GPU $backend depth fog: analytic distance/start, disabled equivalence, dense-to-clear reprojection, stationary accumulation, resets, HDR background and premultiplied alpha")
            }
            camera.lookAt(Vector3f(0f,0f,3f),Vector3f())
            val edgeScene=PathTraceScene().apply {
                val m=material(PathTraceMaterial.diffuse(1f,1f,1f))
                triangle(Vector3f(-0.8f,-0.8f,0f),Vector3f(0.8f,-0.8f,0f),Vector3f(0f,0.8f,0f),m)
            }
            PathTracingRenderer(renderer,edgeScene,PathTraceEnvironment.constant(),9,9,camera,backend).use { t ->
                t.samplesPerFrame=64;t.maxBounces=1;t.fogDensity=100f;t.fogColor=Vector3f(0.2f,0.4f,0.6f)
                t.showEnvironment=false;t.background=Vector4f(0f);t.focusDistance=6f;latch(t)
                for (reuse in listOf(false,true)) for (aperture in listOf(0f,0.25f)) {
                    t.temporalReuse=reuse;t.apertureRadius=aperture;repeat(2) { step(t) }
                    val pixels=t.readLinear()
                    check(pixels.indices.step(4).any { pixels[it+3] in 0.01f..0.99f }) { "Fog coverage test had no antialiased edge" }
                    for (i in pixels.indices step 4) {
                        val a=pixels[i+3]
                        check(abs(pixels[i]-0.2f*a)<1e-5f && abs(pixels[i+1]-0.4f*a)<1e-5f && abs(pixels[i+2]-0.6f*a)<1e-5f) {
                            "Fog broke premultiplied silhouette coverage: reuse=$reuse, aperture=$aperture"
                        }
                    }
                }
                println("PASS GPU $backend depth fog: jittered and defocused transparent silhouettes, history on/off")
            }
        }
    }
    suspend fun run(renderer: Renderer, verifyKeyboard: Boolean = true) {
        val backends=listOf(PathTracingRenderer.Backend.COMPUTE_BVH) +
            if (renderer.device.supportsRayQuery) listOf(PathTracingRenderer.Backend.RAY_QUERY) else emptyList()
        for (backend in backends) {
            // Keyboard checks require exclusive input; disable them when a user is driving Field alongside this test.
            if (verifyKeyboard) {
            // Captured from a live keyboard camera that alternated by two ULPs forever when idle.
            val vertical=Camera(renderer).also {
                it.at.set(0.9632708f,8.943225f,-1.362556f);it.target.set(0f,0.65f,0f)
                it.up.set(0.4941822f,-0.19653562f,-0.84685177f)
            }
            val keys=KeyCamera(renderer,vertical,"temporal-idle-camera-check")
            try {
                PathTracingRenderer(renderer,PathTraceScene().apply {
                    val m=material(PathTraceMaterial.diffuse(1f,1f,1f))
                    quad(Vector3f(-20f,0f,-20f),Vector3f(-20f,0f,20f),Vector3f(20f,0f,20f),Vector3f(20f,0f,-20f),m)
                },PathTraceEnvironment.constant(),48,32,vertical,backend).use { t ->
                    t.samplesPerFrame=1;latch(t)
                    val up=Vector3f(vertical.up)
                    for (reuse in listOf(false,true)) {
                        t.temporalReuse=reuse;t.reset()
                        repeat(32) { step(t) }
                        check(t.samples==32 && vertical.up==up) { "Idle keyboard camera kept resetting convergence: samples=${t.samples}, up=${vertical.up}, at=${vertical.at}, actions=${keys.actions.keys}" }
                    }
                    // Exercise one real controller action and its release, then require convergence again.
                    keys.decay=0.0
                    keys.actions["test-pitch"] = 1.0 to { _: Double -> vertical.pitch(0.01) }
                    delay(100)
                    while (keys.actions.isNotEmpty()) delay(1)
                    step(t);val start=t.samples
                    repeat(32) { step(t) }
                    check(t.samples==start+32) { "Released keyboard camera failed to settle" }
                    println("PASS GPU $backend idle keyboard camera: live near-vertical pose, reuse on/off and released rotation converge")
                }
            } finally { renderer.window.onPoll.remove(keys.name) }
            }
            val camera=Camera(renderer).also { it.at.set(0f,0f,3f);it.target.set(0f,0f,0f);it.p.fov=0.7 }
            fun plane(material: PathTraceMaterial)=PathTraceScene().apply {
                val m=material(material)
                quad(Vector3f(-20f,-20f,0f),Vector3f(20f,-20f,0f),Vector3f(20f,20f,0f),Vector3f(-20f,20f,0f),m)
            }
            PathTracingRenderer(renderer,plane(PathTraceMaterial.diffuse(1f,1f,1f)),PathTraceEnvironment.constant(),48,32,camera,backend).use { t ->
                t.temporalReuse=true;t.maxBounces=2;t.samplesPerFrame=16;latch(t)
                repeat(32) { step(t) }
                check(t.readHistorySampleCounts().all { it==512f })
                t.samplesPerFrame=1;camera.moveWorld(0.02,0.0,0.0);step(t)
                check(t.samples==1)
                val reused=t.readLinear();val counts=t.readHistorySampleCounts()
                check(centre(counts).average()>10) { "Camera motion discarded all useful history: ${centre(counts).average()}" }
                check(counts.all { it in 1f..17.001f }) { "Motion history exceeded the cap" }
                step(t)
                val stationary=t.readHistorySampleCounts()
                check(stationary.indices.all { abs(stationary[it]-counts[it]-1f)<1e-4 }) { "Stationary history didn't resume exact accumulation" }
                t.temporalReuse=false;step(t)
                val cold=t.readLinear()
                val ratio=variance(reused)/variance(cold)
                check(ratio<0.45) { "Moving-camera variance did not improve: ratio=$ratio" }
                check(abs(centre(reused,4).average()-centre(cold,4).average())<0.06) { "Temporal reuse biased the white furnace excessively" }
                t.temporalReuse=true;step(t)
                check(t.readHistorySampleCounts().all { it==1f })
                t.samplesPerFrame=16;repeat(4) { step(t) };t.samplesPerFrame=1
                camera.yaw(0.005);step(t)
                check(centre(t.readHistorySampleCounts()).average()>8) { "Rotation failed to reproject" }
                camera.p.fov=0.71;step(t)
                check(centre(t.readHistorySampleCounts()).average()>8) { "FOV change failed to reproject" }
                repeat(12) {
                    camera.moveWorld(0.01,0.0,0.0);step(t)
                    check(t.samples==1 && centre(t.readHistorySampleCounts()).average()>7) {
                        "Continuous motion stopped retaining useful history"
                    }
                }
                t.environmentIntensity=0.5f;step(t)
                check(t.readHistorySampleCounts().all { it==1f }) { "Lighting edit retained stale history" }
                t.reset();step(t);check(t.readHistorySampleCounts().all { it==1f })
                t.pixelJitter=0f;step(t);check(t.readHistorySampleCounts().all { it==1f })
                t.apertureRadius=0.3f;t.focusDistance=6f;camera.lookAt(Vector3f(0f,0f,3f),Vector3f());step(t)
                t.samplesPerFrame=16;repeat(4) { step(t) };t.samplesPerFrame=1
                camera.moveWorld(0.02,0.0,0.0);step(t)
                check(t.readHistorySampleCounts().all { it==1f }) { "Strong DOF blur incorrectly reused a point correspondence" }
                t.focusDistance=3f;step(t);t.samplesPerFrame=16;repeat(4) { step(t) };t.samplesPerFrame=1
                camera.moveWorld(0.02,0.0,0.0);step(t)
                check(centre(t.readHistorySampleCounts()).average()>8) { "In-focus DOF surface lost valid history" }
                t.focusDistance=null;step(t);repeat(3) { step(t) }
                camera.target.z=-3f;step(t)
                check(t.readHistorySampleCounts().all { it==1f }) { "Focus-only edit retained the old blur" }
                t.apertureRadius=0f;t.temporalMaxSamples=4;step(t);t.samplesPerFrame=16;repeat(4) { step(t) };t.samplesPerFrame=1
                camera.moveWorld(0.02,0.0,0.0);step(t)
                check(t.readHistorySampleCounts().all { it in 1f..5.001f })
                for (bad in listOf(0,257)) check(runCatching { t.temporalMaxSamples=bad }.isFailure)
                println("PASS GPU $backend temporal: motion variance ratio=$ratio, mean retained weight=${centre(counts).average()-1}, " +
                    "continuous motion/rotation/FOV, stationary growth, reset/toggle/lens invalidation, DOF rejection and bounded counts")
            }
            camera.lookAt(Vector3f(0f,0f,3f),Vector3f());camera.p.fov=0.7
            PathTracingRenderer(renderer,plane(PathTraceMaterial.diffuse(1f,1f,1f)),PathTraceEnvironment.constant(),48,32,camera,backend).use { t ->
                t.temporalReuse=true;t.pixelJitter=0f;latch(t)
                for (sensitivity in listOf(0f,0.5f,1f)) {
                    t.temporalMotionSensitivity=sensitivity;t.samplesPerFrame=16;repeat(4) { step(t) };t.samplesPerFrame=1
                    camera.moveWorld(if (camera.at.x<0.4f) 0.8 else -0.8,0.0,0.0);step(t)
                    val weights=t.readHistorySampleCounts()
                    // Only compare pixels inside both views; the larger move exposes the right edge.
                    val count=(12 until 20).flatMap { y -> (16 until 32).map { x -> weights[y*48+x].toDouble() } }.average()
                    check(abs(count-(1+16*(1-sensitivity)))<0.02) {
                        "Motion sensitivity $sensitivity retained unexpected weight: $count; " +
                            (12 until 20).flatMap { y -> (16 until 32).mapNotNull { x ->
                                weights[y*48+x].takeIf { abs(it-(1+16*(1-sensitivity)))>0.02 }
                                    ?.let { "($x,$y)=$it" }
                            } }.joinToString()
                    }
                }
                t.temporalMotionSensitivity=0f;step(t)
                check(t.readHistorySampleCounts().all { it==1f }) { "Motion policy edit failed to reset history" }
                t.apertureRadius=0.1f;t.focusDistance=6f;t.samplesPerFrame=16;repeat(4) { step(t) };t.samplesPerFrame=1
                camera.moveWorld(0.02,0.0,0.0);step(t)
                check(t.readHistorySampleCounts().all { it==1f })
                t.temporalMaxBlurRadius=2f;step(t)
                check(t.readHistorySampleCounts().all { it==1f }) { "Blur policy edit failed to reset history" }
                t.samplesPerFrame=16;repeat(4) { step(t) };t.samplesPerFrame=1
                camera.moveWorld(0.02,0.0,0.0);step(t)
                check(centre(t.readHistorySampleCounts()).average()>8) { "Relaxed defocus policy failed to retain history" }
                for (bad in listOf(-0.1f,1.1f,Float.NaN,Float.POSITIVE_INFINITY)) {
                    check(runCatching { t.temporalMotionSensitivity=bad }.isFailure)
                    check(runCatching { t.temporalRoughnessSensitivity=bad }.isFailure)
                }
                for (bad in listOf(-1f,Float.NaN,Float.POSITIVE_INFINITY))
                    check(runCatching { t.temporalMaxBlurRadius=bad }.isFailure)
                println("PASS GPU $backend history policy: continuous motion weights, relaxed defocus, reset and input validation")
            }
            camera.lookAt(Vector3f(0f,0f,3f),Vector3f())
            PathTracingRenderer(renderer,plane(PathTraceMaterial.metal(1f,1f,1f,0f)),PathTraceEnvironment.constant(),48,32,camera,backend).use { t ->
                t.temporalReuse=true;latch(t);t.samplesPerFrame=16;repeat(4) { step(t) };t.samplesPerFrame=1
                camera.moveWorld(0.02,0.0,0.0);step(t)
                check(t.readHistorySampleCounts().all { it==1f }) { "Default policy reused a mirror" }
                t.temporalRoughnessSensitivity=0f;step(t)
                check(t.readHistorySampleCounts().all { it==1f })
                t.samplesPerFrame=16;repeat(4) { step(t) };t.samplesPerFrame=1
                camera.moveWorld(0.02,0.0,0.0);step(t)
                check(centre(t.readHistorySampleCounts()).average()>15) { "Artistic policy failed to retain mirror history" }
                t.temporalRoughnessSensitivity=1f;step(t)
                check(t.readHistorySampleCounts().all { it==1f })
                println("PASS GPU $backend history policy: optional full-weight mirror reuse and conservative restoration")
            }
            // Dense tessellation must not erase history merely because jitter crosses triangle edges.
            camera.lookAt(Vector3f(0f,0f,3f),Vector3f())
            val grid=PathTraceScene().apply {
                val m=material(PathTraceMaterial.diffuse(1f,1f,1f))
                for (y in -12 until 12) for (x in -12 until 12) {
                    val px=x*0.2f;val py=y*0.2f
                    quad(Vector3f(px,py,0f),Vector3f(px+0.2f,py,0f),Vector3f(px+0.2f,py+0.2f,0f),Vector3f(px,py+0.2f,0f),m)
                }
            }
            PathTracingRenderer(renderer,grid,PathTraceEnvironment.constant(),48,32,camera,backend).use { t ->
                t.temporalReuse=true;t.samplesPerFrame=16;latch(t);repeat(32) { step(t) };t.samplesPerFrame=1
                camera.moveWorld(0.02,0.0,0.0);step(t)
                val retained=centre(t.readHistorySampleCounts()).count { it>8.0 }.toDouble()/512.0
                check(retained>0.95) { "Triangle seams discarded valid surface history: retained fraction=$retained" }
                println("PASS GPU $backend tessellated surface: retained fraction=$retained after jittered convergence")
                camera.lookAt(Vector3f(0f,-4f,1f),Vector3f())
                t.reset();t.samplesPerFrame=16;repeat(32) { step(t) };t.samplesPerFrame=1
                camera.moveWorld(0.01,0.0,0.0);step(t)
                val grazing=centre(t.readHistorySampleCounts()).count { it>8.0 }.toDouble()/512.0
                check(grazing>0.5) { "Grazing surface discarded its stretched footprint: retained fraction=$grazing" }
                println("PASS GPU $backend grazing tessellated surface: retained fraction=$grazing")
            }
            for ((label,material) in listOf("polished" to PathTraceMaterial.metal(0.8f,0.8f,0.8f,0.05f),
                "glass" to PathTraceMaterial.thinGlass(), "alpha" to PathTraceMaterial(opacity=0.5f,alphaMode=PathTraceMaterial.AlphaMode.BLEND))) {
                camera.lookAt(Vector3f(0f,0f,3f),Vector3f())
                PathTracingRenderer(renderer,plane(material),PathTraceEnvironment.constant(),48,32,camera,backend).use { t ->
                    t.temporalReuse=true;t.samplesPerFrame=16;latch(t);repeat(4) { step(t) };t.samplesPerFrame=1
                    camera.moveWorld(0.02,0.0,0.0);step(t)
                    check(t.readHistorySampleCounts().all { it==1f }) { "$label reused invalid primary-surface history" }
                    println("PASS GPU $backend temporal rejection: $label")
                }
            }
            val occlusion=PathTraceScene().apply {
                val red=material(PathTraceMaterial.emissive(1f,0f,0f))
                val blue=material(PathTraceMaterial.emissive(0f,0f,1f))
                quad(Vector3f(-20f,-20f,0f),Vector3f(20f,-20f,0f),Vector3f(20f,20f,0f),Vector3f(-20f,20f,0f),blue)
                quad(Vector3f(-20f,-20f,1f),Vector3f(0f,-20f,1f),Vector3f(0f,20f,1f),Vector3f(-20f,20f,1f),red)
            }
            val sameMaterialLayers=PathTraceScene().apply {
                val m=material(PathTraceMaterial.diffuse(1f,1f,1f))
                quad(Vector3f(-20f,-20f,0f),Vector3f(20f,-20f,0f),Vector3f(20f,20f,0f),Vector3f(-20f,20f,0f),m)
                quad(Vector3f(-0.4f,-20f,0.75f),Vector3f(0.4f,-20f,0.75f),Vector3f(0.4f,20f,0.75f),Vector3f(-0.4f,20f,0.75f),m)
            }
            camera.lookAt(Vector3f(0f,0f,3f),Vector3f());camera.p.fov=0.7
            PathTracingRenderer(renderer,sameMaterialLayers,PathTraceEnvironment.constant(),48,32,camera,backend).use { t ->
                t.temporalReuse=true;t.pixelJitter=0f;t.samplesPerFrame=16;latch(t);repeat(8) { step(t) }
                camera.moveWorld(0.6,0.0,0.0);t.samplesPerFrame=1;step(t)
                val counts=t.readHistorySampleCounts();var revealed=0
                for (x in 0 until 48) {
                    val dx=((x+0.5)/48.0*2.0-1.0)*tan(0.35)*1.5
                    val backgroundX=0.6+3.0*dx
                    val currentForegroundX=0.6+2.25*dx
                    val oldForegroundX=backgroundX*0.75
                    if (abs(currentForegroundX)>0.43 && abs(oldForegroundX)<0.37) {
                        revealed++
                        check(counts[16*48+x]==1f) { "Same-material depth layers leaked history at x=$x: ${counts[16*48+x]}" }
                    }
                }
                check(revealed>0)
                println("PASS GPU $backend same-material depth rejection: $revealed revealed columns restart")
            }
            camera.lookAt(Vector3f(0f,0f,3f),Vector3f())
            PathTracingRenderer(renderer,occlusion,PathTraceEnvironment.constant(0f),48,32,camera,backend).use { t ->
                // The two emissive quads are colour TAGS for history here, not lights, and the
                // assertion below needs each pixel to be exactly pure red or exactly pure blue.
                // Direct emitter sampling would make the red panel at z=1 light the blue plane at
                // z=0 through its 4% dielectric lobe -- correctly, and at maxBounces=1, where a BSDF
                // continuation gathers nothing because a shadow ray reaches one segment further.
                t.emitterSampling=false
                t.temporalReuse=true;t.pixelJitter=0f;t.maxBounces=1;t.samplesPerFrame=16;latch(t);repeat(8) { step(t) }
                camera.moveWorld(0.2,0.0,0.0);t.samplesPerFrame=1;step(t)
                val image=t.readLinear();val counts=t.readHistorySampleCounts()
                check(image.indices.step(4).all { i ->
                    (image[i]<1e-5 && abs(image[i+2]-1f)<1e-5) || (abs(image[i]-1f)<1e-5 && image[i+2]<1e-5)
                }) { "Disocclusion carried red history onto a newly revealed blue surface" }
                val blue=image.indices.step(4).filter { image[it+2]>0.99f }.map { counts[it/4] }
                check(blue.any { it==1f } && blue.any { it>2f }) { "Expected both newly exposed and retained blue pixels: ${blue.minOrNull()}..${blue.maxOrNull()}" }
                t.showEnvironment=false;t.background=Vector4f(0f);step(t)
                camera.lookAlong(Vector3f(0f,0f,3f),Vector3f(0f,0f,1f));step(t)
                check(t.readLinear().all { it==0f }) { "Camera cut leaked history into transparent sky" }
                check(t.readHistorySampleCounts().all { it==1f })
                println("PASS GPU $backend temporal: disocclusion, occlusion, camera cut and transparent background")
            }
        }
    }
}
