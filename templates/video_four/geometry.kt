import afield.animation.keyframes.KeyframeScope
import afield.document.workspace.FileManager
import afield.graphics.scene.video.StereoVideoScreenLayout
import afield.ui.visualization.menu
import kotlin.math.*

// Geometry has its own keys: changing the room never recalls a colour look or seeks the movie.
displayClearAll()
val geometryFields = setOf("radius", "fill", "x", "y", "z")
val geometryKeys = KeyframeScope()
val geometryDirectory = FileManager.current().path("%document/keys/ring-geometry")
val geometryWorking = geometryDirectory.resolve("working geometry")
prop<String>(geometryName)
once.geometryName { "large-ring" }
var geometryError = ""
val geometryMirrors = mutableListOf<() -> Unit>()

// The existing layout controls remain authoritative for size and centre, too.
fun geometryValue(name: String): Double = when (name) {
    "radius" -> carousel.radius.toDouble()
    "fill" -> carousel.fill.toDouble()
    "x" -> carousel.center.x.toDouble()
    "y" -> carousel.center.y.toDouble()
    "z" -> carousel.center.z.toDouble()
    else -> error("Unknown geometry field: $name")
}

geometryFields.forEach { name ->
    geometryKeys.doubleTarget(name, { geometryValue(name) }) { error("Geometry applies as a batch") }
}
geometryKeys.applicationBatch("ring-geometry", geometryFields) { incoming ->
    val next = geometryFields.associateWith { geometryValue(it) }.toMutableMap()
    incoming.forEach { (name, value) -> next[name] = (value as Number).toDouble() }
    require(next.values.all { it.isFinite() }) { "Geometry values must be finite" }
    require(next.getValue("radius") in .025..50.0) { "Radius must be 2.5 cm to 50 m" }
    require(next.getValue("fill") in 0.0..4.0) { "Sector fill must be 0..4" }
    val apply: () -> Unit = {
        renderer.runOnQueueThread {
            carousel.editLayout {
                radius = next.getValue("radius").toFloat()
                fill = next.getValue("fill").toFloat()
                center.set(next.getValue("x").toFloat(), next.getValue("y").toFloat(), next.getValue("z").toFloat())
                screenLayouts = List(surfaces.size) { StereoVideoScreenLayout() }
            }
        }
    }
    apply
}
fun keepGeometry() { geometryKeys.capture().save(geometryWorking) }
fun geometryAction(action: () -> Unit) {
    runCatching { action(); keepGeometry(); geometryError = ""; showGeometryPresets() }
        .onFailure { geometryError = it.message ?: "Geometry failed" }
}
fun setGeometry(name: String, value: Double) {
    geometryAction { geometryKeys.Key(name to value)() }
}

// Seed two stationary room arrangements, then retain the user's working geometry on rerun.
if (geometryKeys.savedKeyNames(geometryDirectory).isEmpty()) {
    val large = geometryKeys.capture()
    large.save(geometryDirectory.resolve("large-ring"))
    val small = geometryKeys.Key(large.values.toMutableMap())
    small["radius"] = .25
    small["y"] = geometryValue("y") - .15
    small["z"] = geometryValue("z") - .8
    small.save(geometryDirectory.resolve("small-ring"))

}
if (java.nio.file.Files.isRegularFile(geometryWorking)) geometryKeys.loadKey(geometryWorking)()
else keepGeometry()
// Recover the matching name on reload; float placement fields may differ by a few ulps.
val currentGeometry = geometryKeys.capture().values
geometryKeys.savedKeyNames(geometryDirectory).filter { it != "working geometry" }.firstOrNull { name ->
    val candidate = geometryKeys.loadKey(geometryDirectory.resolve(name)).values
    currentGeometry.all { (lane, value) ->
        abs((value as Number).toDouble() - (candidate.getValue(lane) as Number).toDouble()) < 1e-5
    }
}?.let { geometryName = it }

fun showGeometryPresets() {
    controlGroup("geometry presets", restoreValues = false) {
        val names = geometryKeys.savedKeyNames(geometryDirectory).filter { it != "working geometry" }
        menu("Arrangement", names, initialIndex = names.indexOf(geometryName).coerceAtLeast(0)) { _, name ->
            geometryName = name
            geometryAction { geometryKeys.loadKey(geometryDirectory.resolve(name))() }
        }
        stringInput("Save as", initialValue = geometryName) { geometryName = it }
        button("Store geometry") { down -> if (down) geometryAction {
            require(geometryName.isNotBlank() && geometryName.none { it == '/' || it == '\\' || it.isISOControl() } && geometryName !in listOf(".", ".."))
            geometryKeys.capture().save(geometryDirectory.resolve(geometryName))
        } }
    }
}
fun afield.ui.output.ControlGroupBuilder.geometrySlider(name: String, label: String,
    min: Double, max: Double, step: Double, unit: String, factor: Double = 1.0) {
    val handle = slider(label, min = min, max = max, step = step,
        initialValue = geometryValue(name) * factor, unit = unit) { setGeometry(name, it / factor) }
    geometryMirrors += { handle.set(geometryValue(name) * factor) }
}
showGeometryPresets()
controlGroup("ring size and position", restoreValues = false) {
    geometrySlider("radius", "Diameter", .05, 10.0, .01, "m", factor = 2.0)
    geometrySlider("fill", "Sector fill", 0.0, 2.0, .01, "×")
    for (axis in listOf("x", "y", "z")) geometrySlider(axis, "Centre $axis", -5.0, 5.0, .01, "m")
}
listOf("geometry presets", "ring size and position").forEach { displayOpen(it) }
var lastGeometryStatus = 0L
loop {
    val now = System.nanoTime()
    if (now - lastGeometryStatus >= 250_000_000L) {
        lastGeometryStatus = now
        geometryMirrors.forEach { it() }
        markdown("geometry status", if (geometryError.isNotEmpty()) geometryError else
            "**$geometryName** · diameter **${(geometryValue("radius") * 2).f2} m** · colour/exposure and playback unchanged.", open = false)
    }
    frame()
}.end { keepGeometry() }
