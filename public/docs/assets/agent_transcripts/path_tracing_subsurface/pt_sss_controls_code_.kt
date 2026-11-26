import afield.graphics.scene.fabricated.*

var scale = 1.0
var choice = AlbedoRemap.REFERENCE
// Rewrite the four remapped spheres in place; the scene and its BVH stay as they are.
fun apply() {
    val r = Vector3f(radius).mul(scale.toFloat())
    AlbedoRemap.entries.forEachIndexed { i, remap ->
        val use = if (i == AlbedoRemap.entries.lastIndex) choice else remap
        tracer.setMaterial(remapped[i], PathTraceMaterial.subsurface(skin, r, use))
    }
    println("radius x $scale, right-hand sphere through $choice")
}
controlGroup("subsurface") {
    slider("radius", min = 0.1, max = 4.0, step = 0.05, initialValue = 1.0, law = ControlLaw.LOGARITHMIC) {
        scale = it
        apply()
    }
    slider("remap", min = 0.0, max = 3.0, step = 1.0, initialValue = 3.0) {
        choice = AlbedoRemap.entries[it.toInt()]
        apply()
    }
}
