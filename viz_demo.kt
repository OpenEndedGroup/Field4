// Value Visualizer Demo
// This demonstrates the new viz() function for compact value display

// Basic types
viz("primitives", 42)
viz("primitives", 3.14159)
viz("primitives", true)
viz("primitives", "Hello, Field4!")

// Very long string
viz("long string", "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris.")

// JOML vectors
viz("vectors", vec(1.0, 2.0))
viz("vectors", vec(1.0, 2.0, 3.0))
viz("vectors", vec(1.0, 2.0, 3.0, 1.0))

// JOML quaternion
viz("quaternion", quat(45.0, vec(0, 1, 0)))

// JOML matrix
val m = mat4()
viz("matrix", m)

// Lists
viz("list", listOf(1, 2, 3, 4, 5))

// Large list with numbers - shows summary stats
val largeList = (1..100).map { it * 0.1 }
viz("large list", largeList)

// Map
viz("map", mapOf("x" to 1.0, "y" to 2.0, "z" to 3.0, "name" to "test"))

// Set
viz("set", setOf("apple", "banana", "cherry", "date", "elderberry"))

// Arrays
viz("int array", intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12))
viz("float array", floatArrayOf(1.1f, 2.2f, 3.3f, 4.4f, 5.5f, 6.6f, 7.7f, 8.8f))

// Byte array
viz("bytes", byteArrayOf(0x48, 0x65, 0x6c, 0x6c, 0x6f))

// Nested structures
val nested = mapOf(
    "position" to vec(10.0, 20.0, 30.0),
    "rotation" to quat(0.0, vec(0, 1, 0)),
    "scale" to vec(1.0, 1.0, 1.0),
    "children" to listOf("child1", "child2", "child3")
)
viz("nested", nested)

// Custom printer example
data class Point3D(val x: Double, val y: Double, val z: Double)

// Register a custom printer
registerValuePrinter<Point3D> { point ->
    "<span style='color: #f80; font-weight: bold;'>Point3D</span><span style='color: #aaa;'>(${point.x}, ${point.y}, ${point.z})</span>"
}

viz("custom type", Point3D(1.0, 2.0, 3.0))
viz("custom list", listOf(Point3D(1.0, 0.0, 0.0), Point3D(0.0, 1.0, 0.0), Point3D(0.0, 0.0, 1.0)))

// ToHTML interface example
data class ComplexData(val name: String, val values: List<Double>) : ToHTML {
    override fun toHTML(): String {
        return """
            <div style='border-left: 2px solid #8cf; padding-left: 10px;'>
                <div style='color: #8cf; font-weight: bold;'>${name}</div>
                <div style='color: #888; font-size: 11px;'>${values.size} values: ${values.take(3).joinToString(", ")}...</div>
            </div>
        """.trimIndent()
    }
}

viz("tohtml", ComplexData("Dataset A", (1..50).map { it * 1.5 }))

// Circular reference test
class Node(val name: String) {
    var next: Node? = null
}

val node1 = Node("A")
val node2 = Node("B")
val node3 = Node("C")
node1.next = node2
node2.next = node3
node3.next = node1  // Creates a circular reference

viz("circular", node1)

// Self-referencing list
val selfRefList = mutableListOf<Any>()
selfRefList.add("item1")
selfRefList.add("item2")
selfRefList.add(selfRefList)  // Add itself to create circular reference

viz("self-ref list", selfRefList)

println("Value visualizer demo complete!")
