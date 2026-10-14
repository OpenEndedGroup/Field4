package afield.solidcraft

import kotlin.math.abs

/** Run with ./gradlew smokeSolidCraft after opting into the native build. */
object SolidCraftSmokeTest {
    @JvmStatic fun main(args: Array<String>) {
        check(SolidCraftLibrary.isAvailable) { SolidCraftLibrary.describe() }
        SolidCraftDesign.openBundledExample().use { design ->
            val before = design.summary()
            val bodies = before.getJSONArray("bodies")
            check(bodies.length() == 1) { "expected one evaluated body: $before" }
            val initialVolume = bodies.getJSONObject(0).getDouble("volume")
            check(initialVolume > 0.0)

            val initialMesh = design.mesh(tolerance = 0.1)
            check(initialMesh.positions.isNotEmpty() && initialMesh.indices.isNotEmpty())
            check(initialMesh.faceIds.size == initialMesh.indices.size / 3)
            check(initialMesh.toTriMesh().triangleCount == initialMesh.faceIds.size)

            design.setParameter("width", "50 mm")
            val after = design.summary()
            val editedVolume = after.getJSONArray("bodies").getJSONObject(0).getDouble("volume")
            check(abs(editedVolume - initialVolume) > 1.0) {
                "width edit had no geometric effect: $initialVolume -> $editedVolume"
            }
            check(design.mesh(tolerance = 0.1).indices.isNotEmpty())

            val step = design.exportStep()
            check(step.size > 100 && String(step, Charsets.UTF_8).contains("ISO-10303-21"))
            val stl = design.exportStl()
            check(stl.size > 84)

            val saved = design.saveDesign()
            SolidCraftDesign.open(saved).use { reopened ->
                val reopenedVolume = reopened.summary().getJSONArray("bodies").getJSONObject(0).getDouble("volume")
                check(abs(reopenedVolume - editedVolume) < 1e-6)
            }

            val failure = runCatching { design.setParameter("width", "invalid_expression") }.exceptionOrNull()
            check(failure != null) { "invalid parameter unexpectedly succeeded" }
            val retainedVolume = design.summary().getJSONArray("bodies").getJSONObject(0).getDouble("volume")
            check(abs(retainedVolume - editedVolume) < 1e-6) { "failed edit changed the live model" }
            println("SolidCraft FFM smoke passed: volume $initialVolume -> $editedVolume, ${initialMesh.faceIds.size} triangles, ${step.size} STEP bytes")
        }
    }
}
