package afield.four.fabricated

import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f
import kotlin.math.abs

/**
 * FIELD_TEST
 * id: four.pathtracing.gltf
 * runner: jvm-main
 * main: afield.four.fabricated.PathTraceGltfTestMain
 * timeoutSec: 90
 * tags: smoke, graphics
 * END_FIELD_TEST
 */
object PathTraceGltfTestMain {
    @JvmStatic fun main(args: Array<String>) {
        val folder="dev_docs/agents/fixtures/path_tracing/"
        val external=PathTraceScene.fromGltf(folder+"quad.gltf")
        val embedded=PathTraceScene.fromGltf(folder+"quad.glb")
        for (scene in listOf(external,embedded)) {
            check(scene.triangles.size==2 && scene.textureData.size==3)
            val m=scene.materials[scene.triangles.first().material]
            check(abs(m.opacity-0.5f)<1e-6) { "Alpha must not be squared: ${m.opacity}" }
            check(abs(m.metallic-0.8f)<1e-6 && abs(m.roughness-0.6f)<1e-6)
            val texture=scene.textureData[m.baseColorTexture]
            check(abs(texture.pixels[0]-0.2158605f)<1e-6) { "Base color must decode sRGB" }
            check(abs(scene.textureData[m.metallicRoughnessTexture].pixels[1]-128f/255f)<1e-6) { "MR must stay linear" }
            check(texture.wrapU==1 && texture.wrapV==1 && texture.nearest) { "Sampler: ${texture.wrapU}, ${texture.wrapV}, nearest=${texture.nearest}" }
            check(m.doubleSided)
            for (t in scene.triangles) for (i in 0..2) {
                check(abs(t.uv!![i][0]-(t.p[i].x+2f)/4f)<1e-6)
                check(abs(t.uv[i][1]-(2f-t.p[i].y)/4f)<1e-6) { "UV image origin flipped" }
                check(t.color!![i].x==0.5f && t.color[i].z==0.25f)
            }
        }
        check(external.textureData.zip(embedded.textureData).all { (a,b)->a.pixels.contentEquals(b.pixels) })
        val transformed=PathTraceScene()
        val report=transformed.importGltf(folder+"transforms.gltf")
        check(report.boundsMin.distance(Vector3f(-1f,-4f,0f))<1e-5)
        check(report.boundsMax.distance(Vector3f(9f,8f,2f))<1e-5)
        transformed.triangles.forEach { t ->
            val face=Vector3f(t.p[1]).sub(t.p[0]).cross(Vector3f(t.p[2]).sub(t.p[0])).normalize()
            check(face.dot(t.n[0])>0.9999f) { "Mirrored winding/normal mismatch" }
        }
        val uvTransform=PathTraceScene.fromGltf(folder+"uv-transform.gltf")
        uvTransform.triangles.forEach { t -> for(i in 0..2) {
            val x=(t.p[i].x+2f)/4f*0.5f;val y=(2f-t.p[i].y)/4f*0.75f
            val c=kotlin.math.cos(0.3f);val sn=kotlin.math.sin(0.3f)
            check(abs(t.uv!![i][0]-(0.25f+c*x-sn*y))<1e-5)
            check(abs(t.uv[i][1]-(0.125f+sn*x+c*y))<1e-5) { "Texture transform V mismatch: ${t.uv[i].toList()}" }
        } }
        val second=external.importGltf(folder+"quad.glb", Matrix4f().translate(5f,0f,0f))
        check(external.triangles.size==4 && external.textureData.size==6)
        check(second.materialIds.values.all { it>=embedded.materials.size })
        check(external.materials[external.triangles.last().material].baseColorTexture>=3)
        check(runCatching { external.importGltf(folder+"unsupported-required.gltf") }.exceptionOrNull()?.message?.contains("Unsupported required") == true)
        val before=external.triangles.size
        check(runCatching { external.importGltf(folder+"quad.gltf",Matrix4f().scale(0f)) }.isFailure)
        check(external.triangles.size==before && external.textureData.size==6) { "Failed import mutated scene" }
        val car=PathTraceScene()
        val carReport=car.importGltf("public/docs/assets/fabricate_demo/ToyCar.glb",Matrix4f().scale(20f))
        check(carReport.triangleCount>100 && car.textureData.isNotEmpty())
        println("PASS glTF: external/embedded textures, sRGB, linear MR, alpha, vertex colors, hierarchy, mirrors, UV transforms, append remapping and transactional failure")
        println("ToyCar: ${carReport.triangleCount} triangles, ${car.textureData.size} textures, bounds ${carReport.boundsMin} .. ${carReport.boundsMax}; ${carReport.warnings}")
    }
}
