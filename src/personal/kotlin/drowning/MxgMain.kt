package drowning

/** Describe/verify an `.mxg`, or dump the editable box shaders. */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("usage: MxgMain <file.mxg> [--all] [--shaders DIR]")
        kotlin.system.exitProcess(1)
    }
    var all = false
    var shaderDir: String? = null
    val files = ArrayList<String>()
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--all" -> all = true
            "--shaders" -> shaderDir = args[++i]
            else -> files += args[i]
        }
        i++
    }
    shaderDir?.let {
        val dir = java.io.File(it).also { d -> d.mkdirs() }
        java.io.File(dir, "mxg.vert").writeText(MxgShaders.VERTEX)
        java.io.File(dir, "mxg.frag").writeText(MxgShaders.FRAGMENT)
        println("wrote $dir/mxg.vert and $dir/mxg.frag")
    }
    for (name in files) {
        MxgFile.open(name).use { file ->
            println(file)
            println(file.header)
            val profile = file.profile()
            println(
                "profile: ${profile.frames} frames, max ${profile.capacity.nodes} nodes / " +
                    "${profile.capacity.segments} segments, buffers ${"%.2f".format(profile.capacity.gpuBytes / 1e6)} MB"
            )
            val indices = if (all) 0 until file.frameCount else listOf(0, file.frameCount - 1).distinct()
            for (record in indices) {
                val frame = file.readFrame(record)
                verify(frame)
                println(frame.describeGpu())
            }
        }
    }
}
private fun verify(frame: MxgFrame) {
    require(frame.nodeCount > 0)
    require(frame.nodeParent(0) == MxgFrame.INVALID)
    for (node in 1 until frame.nodeCount) {
        require(frame.nodeParent(node) in 0 until node) { "frame ${frame.frameIndex}: bad parent at node $node" }
    }
    for (segment in 0 until frame.segmentCount) {
        require(frame.segmentNode(segment) in 0 until frame.nodeCount)
        require(frame.segmentX0(segment) in 0f..frame.width.toFloat())
        require(frame.segmentY0(segment) in 0f..frame.height.toFloat())
        require(frame.segmentX1(segment) in 0f..frame.width.toFloat())
        require(frame.segmentY1(segment) in 0f..frame.height.toFloat())
    }
}
