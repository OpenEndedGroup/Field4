package afield.four.fabricated

import afield.four.Renderer
import afield.four.modules.*
import afield.four.util.Camera
import afield.stdlib.linalg.Vector4f
import kotlin.math.abs
import kotlin.math.pow

/** Known texture values checked through actual GPU traversal, material evaluation and accumulation. */
object PathTraceGltfGpuSmoke {
    suspend fun run(renderer: Renderer, backend: PathTracingRenderer.Backend) {
        val camera=Camera(renderer).also { it.at.set(0f,0f,3f);it.target.set(0f,0f,0f);it.p.fov=0.6 }
        val bytes=listOf(intArrayOf(128,64,32),intArrayOf(32,128,64),intArrayOf(64,32,128),intArrayOf(255,255,255))
        val alpha=floatArrayOf(0f,0.5f,128f/255f*0.5f,0.5f)
        fun linear(v: Int): Float { val c=v/255f;return if(c<=0.04045f)c/12.92f else ((c+0.055f)/1.055f).pow(2.4f) }
        for (mode in PathTraceMaterial.AlphaMode.entries) {
            val scene=PathTraceScene()
            scene.importGltf("dev_docs/agents/fixtures/path_tracing/quad.glb",material={ _,m -> m.copy(alphaMode=mode,alphaCutoff=0.3f) })
            PathTracingRenderer(renderer,scene,PathTraceEnvironment.constant(0f),24,24,camera,backend).use { tracer ->
                tracer.maxBounces=1;tracer.samplesPerFrame=16;tracer.showEnvironment=false;tracer.background=Vector4f(0f)
                tracer.install();PathTraceGpuSmoke.awaitSamples(tracer,1024);tracer.paused=true
                val pixels=tracer.readLinear()
                for(q in 0..3) {
                    val x=if(q%2==0)6 else 18;val y=if(q<2)6 else 18;val index=(y*24+x)*4
                    val coverage=when(mode) { PathTraceMaterial.AlphaMode.OPAQUE->1f;PathTraceMaterial.AlphaMode.MASK->if(alpha[q]>=0.3f)1f else 0f;PathTraceMaterial.AlphaMode.BLEND->alpha[q] }
                    val tolerance=if(mode==PathTraceMaterial.AlphaMode.BLEND)0.035f else 1e-5f
                    for(c in 0..2) check(abs(pixels[index+c]-linear(bytes[q][c])*coverage)<tolerance) {
                        "$mode quad=$q channel=$c expected=${linear(bytes[q][c])*coverage} actual=${pixels[index+c]}"
                    }
                    check(abs(pixels[index+3]-coverage)<tolerance) { "$mode coverage ${pixels[index+3]} vs $coverage" }
                }
                println("PASS GPU $backend glTF $mode: texture orientation, sRGB emission and alpha coverage")
            }
        }
        val scene=PathTraceScene.fromGltf("dev_docs/agents/fixtures/path_tracing/quad.gltf")
        for (normal in listOf(false,true)) {
            val hook=if(normal) "material.emission.rgb = surface.normal*0.5+0.5;" else "material.emission.rgb = vec3(material.base.w,material.emission.w,material.base.r);"
            PathTracingRenderer(renderer,scene,PathTraceEnvironment.constant(0f),24,24,camera,backend,
                moduleFactory={ p ->
                    p.compute=p.compute.replace("Material shadeMaterial(Material material, PathTraceSurface surface) { return material; }",
                        "Material shadeMaterial(Material material, PathTraceSurface surface) { $hook return material; }")
                    ComputeModule(p).pointer()
                }).use { tracer ->
                tracer.maxBounces=1;tracer.install();PathTraceGpuSmoke.awaitSamples(tracer,8);tracer.paused=true
                val pixels=tracer.readLinear();val offset=(6*24+6)*4
                val n=afield.stdlib.linalg.Vector3f(128f/255f*2-1,-(204f/255f*2-1),230f/255f*2-1).normalize()
                val expected=if(normal) floatArrayOf(n.x*.5f+.5f,n.y*.5f+.5f,n.z*.5f+.5f)
                    else floatArrayOf(.8f*64f/255f,.6f*128f/255f,.8f*.5f*linear(128))
                for(c in 0..2) check(abs(pixels[offset+c]-expected[c])<0.002f) {
                    "glTF normal=$normal channel=$c expected=${expected[c]} actual=${pixels[offset+c]}"
                }
                println("PASS GPU $backend glTF ${if(normal) "normal map" else "metallic/roughness and vertex color"}")
            }
        }
    }
}
