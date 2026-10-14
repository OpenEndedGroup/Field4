import afield.solidcraft.SolidCraftDesign
import afield.solidcraft.SolidCraftLibrary
import afield.stdlib.MeshRenderer
import afield.stdlib.MeshView
import afield.stdlib.showMeshGrid
import afield.stdlib.weld

run {
    if (!SolidCraftLibrary.isAvailable) {
        display("main", "<div style='padding:24px;background:#101318;color:#e6ecf4;font-family:Iosevka,monospace'>" +
            "SolidCraft is optional. Run <code>./gradlew buildSolidCraftNative</code>, restart Field, then run this box.</div>")
        println(SolidCraftLibrary.describe())
        return@run
    }

    // Change this value and run again. The sketch is 40 × 30 mm; the first extrusion is
    // width / 2 deep, while the circular cut remains exactly 20 mm deep.
    val width = 50
    val tolerance = 0.1

    SolidCraftDesign.openBundledExample().use { part ->
        val original = part.summary().getJSONArray("bodies").getJSONObject(0)
        val before = part.mesh(tolerance = tolerance).toTriMesh().weld()
        part.setParameter("width", "$width mm")
        val changed = part.summary().getJSONArray("bodies").getJSONObject(0)
        val after = part.mesh(tolerance = tolerance).toTriMesh().weld()

        println("B-rep volume: ${"%.1f".format(original.getDouble("volume"))} → " +
            "${"%.1f".format(changed.getDouble("volume"))} mm³")
        println("STEP ${part.exportStep().size} bytes; STL ${part.exportStl().size} bytes")
        println("The hole is blind at width > 40 mm: its cut depth stays 20 mm.")

        showMeshGrid(
            "40 mm: through cut" to before,
            "$width mm: blind cut" to after,
            columns = 2,
            cellWidth = 380,
            // Look from the cut's side of the XY plane so the blind hole stays visible.
            view = MeshView(yawDegrees = 145.0, pitchDegrees = 32.0, wireframe = false),
            renderer = MeshRenderer.CPU,
        )
    }
}
