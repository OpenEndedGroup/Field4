package afield.graphics.scene.reading

/** A prepared graph and its independent score. Switching never needs a shader or font rebuild. */
data class ReadingExample(
    val title: String,
    val description: String,
    val player: ReadingDiagramPlayer,
)

class ReadingGallery(val examples: List<ReadingExample>) : ReadingScene {
    init {
        require(examples.isNotEmpty())
    }

    var selected = 0
        private set

    val current
        get() = examples[selected].player

    override val blurRequests: Map<ReadingText, Float>
        get() {
            val result = LinkedHashMap<ReadingText, Float>()
            for (example in examples) for ((shape, radius) in example.player.blurRequests)
                result.merge(shape, radius, ::maxOf)
            return result
        }

    override val maximumLabels = examples.maxOf { it.player.maximumLabels }
    override val shapes =
        examples
            .flatMapIndexed { index, example ->
                example.player.shapes.map { (key, shape) -> "$index:$key" to shape }
            }
            .toMap()
    override val geometry
        get() = current.geometry

    override val labels
        get() = current.labels

    fun select(index: Int, restart: Boolean = true) {
        require(index in examples.indices)
        selected = index
        if (restart) current.restart()
    }

    override val frostSurfaces get() = current.frostSurfaces

    override val individualTextFacing get() = current.individualTextFacing
    override fun setReaderPosition(position: afield.math.linalg.Vector3f) = current.setReaderPosition(position)

    override fun update(elapsedSeconds: Double) = current.update(elapsedSeconds)
}
