package afield.four.fabricated

import afield.four.modules.Introspection
import afield.four.modules.ShaderKind
import afield.stdlib.linalg.Vector3f
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs

/*
FIELD_TEST
id: four.pathtracing.contracts
runner: jvm-main
entrypoint: afield.four.fabricated.PathTraceTestMain
timeoutSec: 90
tags: [graphics, pathtracing, smoke]
expect:
  exitCode: 0
  stdoutContains:
    - "PASS path-tracing scene, BVH, importance distribution and shader contracts"
*/
/** Pure scene/importance-distribution contracts plus compilation/reflection of BOTH GPU variants. */
object PathTraceTestMain {
    @JvmStatic fun main(args: Array<String>) {
        val scene = PathTraceScene()
        val material = scene.material(PathTraceMaterial.metal(0.9f, 0.7f, 0.4f))
        scene.sphere(Vector3f(), 1f, material, 16, 8)
        val packed = scene.pack()
        check(packed.triangles.size == 224)
        check(packed.nodes.first().escape == packed.nodes.size)
        val covered = mutableSetOf<Int>()
        packed.nodes.forEachIndexed { index, node ->
            check(node.escape in index+1..packed.nodes.size)
            if (node.count > 0) {
                check(node.escape == index+1)
                for (id in node.first until node.first+node.count) {
                    check(covered.add(id))
                    packed.triangles[id].p.forEach { p ->
                        check(p.x in node.lo.x..node.hi.x && p.y in node.lo.y..node.hi.y && p.z in node.lo.z..node.hi.z)
                    }
                }
            } else {
                val left = packed.nodes[index+1]; val right = packed.nodes[left.escape]
                check(right.escape == node.escape)
                listOf(left,right).forEach { c ->
                    check(c.lo.x >= node.lo.x && c.lo.y >= node.lo.y && c.lo.z >= node.lo.z)
                    check(c.hi.x <= node.hi.x && c.hi.y <= node.hi.y && c.hi.z <= node.hi.z)
                }
            }
        }
        check(covered.size == packed.triangles.size)
        val bytes = ByteBuffer.allocate(packed.triangles.size*240).order(ByteOrder.nativeOrder())
        packed.writeTriangles(bytes)
        check(bytes.position() == bytes.capacity())
        check(bytes.getFloat(12).toInt() == material)
        check(runCatching { PathTraceMaterial(roughness = Float.NaN) }.isFailure)
        check(runCatching { PathTraceScene().pack() }.isFailure)
        check(runCatching { scene.triangle(Vector3f(),Vector3f(),Vector3f(),material) }.isFailure)
        packed.triangles.forEach { t ->
            check(Vector3f(t.p[1]).sub(t.p[0]).cross(Vector3f(t.p[2]).sub(t.p[0])).dot(t.p[0]) > 0f) {
                "Sphere winding must point outward for medium transitions"
            }
        }
        val glassScene=PathTraceScene()
        glassScene.material(PathTraceMaterial.glass(attenuationColor=Vector3f(0.5f,0.25f,1f), attenuationDistance=2f))
        val glassBytes=ByteBuffer.allocate(PathTraceMaterial.STRIDE).order(ByteOrder.nativeOrder())
        // Use a triangle so packing exercises the same public builder path as a renderer.
        glassScene.triangle(Vector3f(),Vector3f(1f,0f,0f),Vector3f(0f,1f,0f),0)
        glassScene.pack().writeMaterials(glassBytes)
        check(glassBytes.position()==PathTraceMaterial.STRIDE && glassBytes.getFloat(32)==1f && glassBytes.getFloat(36)==1.5f)
        check(abs(glassBytes.getFloat(48)-0.3465736f)<1e-6 && glassBytes.getFloat(56)==0f)
        check(runCatching { PathTraceMaterial.glass(ior=0.9f) }.isFailure)
        check(runCatching { PathTraceMaterial.glass(attenuationDistance=0f) }.isFailure)
        check(runCatching { PathTraceMaterial.glass(attenuationColor=Vector3f(Float.NaN)) }.isFailure)
        check(runCatching { PathTraceMaterial.thinGlass().copy(roughness=0.1f) }.isFailure)
        val env = PathTraceEnvironment(4,2,FloatArray(32) { 1f })
        val total = env.distribution.indices.filter { it%2==1 }.sumOf { env.distribution[it].toDouble() }
        check(abs(total-1.0)<1e-6)
        check(env.distribution[14] == 1f)
        for (i in 0 until 8) check(abs(env.distribution[2*i+1]/(PI/2)-1/(4*PI)) < 1e-6)
        val brightPixels = FloatArray(64) { if (it < 4) 1000f else 0f }
        val bright = PathTraceEnvironment(4,4,brightPixels)
        check(bright.distribution[1] in 0.4f..0.8f)
        check(bright.distribution[3] > 0.05f && bright.distribution[7] > 0.05f) { "Filtered sun footprint must cross the longitude seam" }
        check(bright.distribution[9] > 0.05f) { "Filtered sun footprint must extend to the next row" }
        for (i in 0 until 16) {
            val previous = if (i == 0) 0f else bright.distribution[2*i-2]
            check(bright.distribution[2*i+1] == bright.distribution[2*i]-previous)
        }
        // Emitter selection table: power-weighted over emissive triangles, plus the per-triangle
        // inverse map the BSDF-hit MIS weight reads. Same two-float layout as the environment's.
        val lampScene = PathTraceScene()
        val dull = lampScene.material(PathTraceMaterial.diffuse(0.5f,0.5f,0.5f))
        val small = lampScene.material(PathTraceMaterial.emissive(6f,6f,6f))
        val wide = lampScene.material(PathTraceMaterial.emissive(1f,1f,1f))
        val masked = lampScene.material(PathTraceMaterial(emissionRed=9f, alphaMode=PathTraceMaterial.AlphaMode.MASK))
        val litGlass = lampScene.material(PathTraceMaterial.glass().copy(emissionRed=9f))
        lampScene.quad(Vector3f(-1f,0f,-1f),Vector3f(1f,0f,-1f),Vector3f(1f,0f,1f),Vector3f(-1f,0f,1f),dull)
        lampScene.triangle(Vector3f(0f,2f,0f),Vector3f(1f,2f,0f),Vector3f(0f,2f,1f),small)
        lampScene.triangle(Vector3f(0f,3f,0f),Vector3f(2f,3f,0f),Vector3f(0f,3f,2f),wide)
        lampScene.triangle(Vector3f(0f,4f,0f),Vector3f(1f,4f,0f),Vector3f(0f,4f,1f),masked)
        lampScene.triangle(Vector3f(0f,5f,0f),Vector3f(1f,5f,0f),Vector3f(0f,5f,1f),litGlass)
        val lamps = lampScene.pack()
        fun packedIds(material: Int) = lamps.triangles.indices.filter { lamps.triangles[it].material == material }
        check(lamps.emitterCount == 2) { "Emitter count ${lamps.emitterCount}" }
        check(lamps.emitterDistribution.size == 4 && lamps.emitterLookup.size == lamps.triangles.size*2)
        check(lamps.emitterByteSize == 16L && lamps.emitterLookupByteSize == lamps.triangles.size*8L)
        val tableIds = (0 until lamps.emitterCount).map { lamps.emitterDistribution[2*it+1].toInt() }
        check(tableIds.toSet() == (packedIds(small)+packedIds(wide)).toSet() && tableIds.size == tableIds.toSet().size) {
            "The table must hold exactly the sampler-visible emissive triangles, once each: $tableIds"
        }
        check(lamps.emitterDistribution[2*(lamps.emitterCount-1)] == 1f) { "Last cumulative must be exactly one" }
        var lampPrevious = 0f
        for (i in 0 until lamps.emitterCount) {
            val cumulative = lamps.emitterDistribution[2*i]
            check(cumulative >= lampPrevious)
            // The pdf comes from the uploaded interval, not the weight: a rounded CDF must stay normalized.
            check(lamps.emitterLookup[2*tableIds[i]] == cumulative - lampPrevious) {
                "Probability ${lamps.emitterLookup[2*tableIds[i]]} does not match interval ${cumulative-lampPrevious}"
            }
            lampPrevious = cumulative
        }
        // Area times emitted luminance: 0.5 x 6 against 2 x 1 is 3:2.
        check(abs(lamps.emitterLookup[2*packedIds(small).single()]-0.6f) < 1e-6) { "Area x luminance weighting" }
        check(abs(lamps.emitterLookup[2*packedIds(wide).single()]-0.4f) < 1e-6)
        check(abs(lamps.emitterLookup[2*packedIds(small).single()+1]-0.5f) < 1e-6) { "Stored triangle area" }
        check(abs(lamps.emitterLookup[2*packedIds(wide).single()+1]-2f) < 1e-6)
        (packedIds(dull)+packedIds(masked)+packedIds(litGlass)).forEach {
            check(lamps.emitterLookup[2*it] == 0f && lamps.emitterLookup[2*it+1] == 0f) {
                "Dark, stochastically masked and transmissive triangles must stay invisible to the direct sampler"
            }
        }
        // Both faces radiate, so a two-sided emitter of equal size and colour carries twice the power.
        val sidedScene = PathTraceScene()
        val oneSided = sidedScene.material(PathTraceMaterial.emissive(1f,1f,1f).copy(doubleSided=false))
        val twoSided = sidedScene.material(PathTraceMaterial.emissive(1f,1f,1f))
        sidedScene.triangle(Vector3f(0f,0f,0f),Vector3f(1f,0f,0f),Vector3f(0f,1f,0f),oneSided)
        sidedScene.triangle(Vector3f(0f,0f,1f),Vector3f(1f,0f,1f),Vector3f(0f,1f,1f),twoSided)
        val sided = sidedScene.pack()
        check(abs(sided.emitterLookup[2*sided.triangles.indices.single { sided.triangles[it].material==oneSided }]-1f/3f) < 1e-6)
        check(abs(sided.emitterLookup[2*sided.triangles.indices.single { sided.triangles[it].material==twoSided }]-2f/3f) < 1e-6)
        // A scene with no emitter at all still uploads a buffer, and the shader reads none of it.
        val darkScene = PathTraceScene()
        darkScene.triangle(Vector3f(),Vector3f(1f,0f,0f),Vector3f(0f,1f,0f),
            darkScene.material(PathTraceMaterial.diffuse(1f,1f,1f)))
        val dark = darkScene.pack()
        check(dark.emitterCount == 0 && dark.emitterByteSize == 8L && dark.emitterLookupByteSize == 8L)
        check(dark.emitterDistribution.all { it == 0f } && dark.emitterLookup.all { it == 0f })
        // Many equal emitters: monotone cumulative, exact normalization, every triangle present once.
        val shellScene = PathTraceScene()
        val shell = shellScene.material(PathTraceMaterial.emissive(0.5f,0.5f,0.5f))
        shellScene.sphere(Vector3f(), 1f, shell, 8, 4)
        val shellPacked = shellScene.pack()
        check(shellPacked.emitterCount == shellPacked.triangles.size)
        val shellProbabilities = (0 until shellPacked.emitterCount).map {
            shellPacked.emitterLookup[2*shellPacked.emitterDistribution[2*it+1].toInt()].toDouble()
        }
        check(abs(shellProbabilities.sum()-1.0) < 1e-6) { "Emitter probabilities sum to ${shellProbabilities.sum()}" }
        check((0 until shellPacked.emitterCount).all { it == 0 ||
            shellPacked.emitterDistribution[2*it] >= shellPacked.emitterDistribution[2*it-2] })
        check(shellPacked.emitterLookup.filterIndexed { i, _ -> i%2 == 1 }.all { it > 0f })
        println("PASS emitter table: ${lamps.emitterCount} of ${lamps.triangles.size} triangles sampled, " +
            "${shellPacked.emitterCount}-emitter shell normalized to ${shellProbabilities.sum()}")

        val directions = PathTracingRenderer.sobolWords
        check(directions.size == 100*32)
        fun sobol(index: Int, dimension: Int): Double {
            var i=index xor (index ushr 1); var bit=0; var word=0
            while (i!=0) { if ((i and 1)!=0) word=word xor directions[dimension*32+bit]; i=i ushr 1; bit++ }
            return Integer.toUnsignedLong(word)/4294967296.0
        }
        val reference=doubleArrayOf(0.0,0.5,0.75,0.25,0.375,0.875,0.625,0.125)
        reference.indices.forEach { check(sobol(it,0)==reference[it]) }
        // Cross-decision projections, not just the reused 2D pairs that formerly hid a noise floor.
        for ((a,b) in listOf(2 to 7, 4 to 9, 11 to 17, 32 to 97, 0 to 98, 1 to 99, 98 to 99)) {
            val counts=IntArray(16)
            repeat(1024) { counts[(sobol(it,a)*4).toInt()*4+(sobol(it,b)*4).toInt()]++ }
            check(counts.all { it in 48..80 }) { "Poor Sobol projection $a/$b: ${counts.toList()}" }
        }
        for (hardware in listOf(false,true)) {
            val errors = mutableListOf<String>()
            val shader = Introspection(PathTracingRenderer.shader(hardware), ShaderKind.COMPUTE_SHADER,
                errorHandler = { message, _ -> errors += message })
            check(shader.valid) { "Shader hardware=$hardware: ${errors.joinToString("\n")}" }
            check(shader.pushConstants.isEmpty()) { "Unexpected push allocation: ${shader.pushConstants}" }
            val sceneBuffers = shader.ubos.single { it.name == "SceneBuffers" }
            check(sceneBuffers.members.map { it.offset } == listOf(0,8,16,24,32,40,48,56,64,72,80)) {
                "SceneBuffers pointer layout: ${sceneBuffers.members.map { it.name to it.offset }}"
            }
            check(sceneBuffers.members.map { it.name }.takeLast(2) == listOf("emitterTable","emitterLookup"))
            check(shader.ubos.map { it.name }.toSet() == setOf("Frame", "SceneBuffers"))
            val frameUbo = shader.ubos.single { it.name == "Frame" }
            check(frameUbo.size == 288) { "Frame size ${frameUbo.size}" }
            check(frameUbo.members.last().name == "emitters")
            check(shader.accelerationStructures.size == if (hardware) 1 else 0)
            println("PASS path-tracing shader hardware=$hardware, SceneBuffers=${sceneBuffers.size}, Frame=${frameUbo.size}")
        }
        println("PASS path-tracing scene, BVH, importance distribution and shader contracts")
    }
}
