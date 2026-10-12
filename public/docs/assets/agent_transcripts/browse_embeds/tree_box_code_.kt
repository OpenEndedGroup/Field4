// Any value can go into tree(...): maps, lists, arrays, data classes, your own classes.
// Each row shows name, type and a one-line summary; click a triangle (or use the arrow keys) to open.
data class Particle(val position: Vec3, val age: Double, val tag: String)
class Node(val name: String) { var next: Node? = null }

val a = Node("a"); val b = Node("b"); a.next = b; b.next = a      // a loop: shown with ↺, not opened forever

val scene = linkedMapOf(
    "particle" to Particle(Vec3(1f, 2f, 0.5f), 0.25, "spark"),
    "weights" to doubleArrayOf(0.1, 0.4, 0.5),
    "many" to List(5000) { it * it },                               // arrives 200 rows at a time
    "byKey" to mapOf(Particle(Vec3(0f, 0f, 0f), 0.0, "origin") to "an object as a key", 1 to "one", "1" to "the string one"),
    "ring" to a,
    "missing" to null,
)
tree("tree", scene)
