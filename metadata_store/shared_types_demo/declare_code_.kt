// One publish, and every box in this document can name these types.
// They are compiled as ordinary Kotlin into a directory on the shared
// classloader -- not declared in this box's snippet, which is why they
// escape this box at all.
println(sharedTypes("""
package shared

data class Particle(val x: Double, val y: Double, val mass: Double) {
    val momentum: Double get() = mass * kotlin.math.hypot(x, y)
}
"""))