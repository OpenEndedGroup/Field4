// The same kind of value as Finder-style columns: choosing a row opens it in the next column.
data class Layer(val name: String, val visible: Boolean, val points: IntArray)

val document = linkedMapOf(
    "layers" to listOf(Layer("dots", true, intArrayOf(2, 4, 8, 16)), Layer("lines", false, intArrayOf())),
    "camera" to mapOf("x" to 10, "y" to 20, "zoom" to 1.5),
    "title" to "a document\nwith two lines",
)
column("columns", document)
