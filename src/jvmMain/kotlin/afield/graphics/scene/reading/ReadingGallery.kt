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
        require(examples.map { it.player.typeface.awtFont }.distinct().size == 1) {
            "A gallery shares one font at one preparation size"
        }
    }

    var selected = 0
        private set

    val current
        get() = examples[selected].player

    override val maximumLabels = examples.maxOf { it.player.maximumLabels }
    override val shapes =
        examples.flatMap { it.player.shapes.entries }.associate { it.key to it.value }
    override val geometry
        get() = current.geometry

    override val labels
        get() = current.labels

    fun select(index: Int, restart: Boolean = true) {
        require(index in examples.indices)
        selected = index
        if (restart) current.restart()
    }

    override fun update(elapsedSeconds: Double) = current.update(elapsedSeconds)
}
