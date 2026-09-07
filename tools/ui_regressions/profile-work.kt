// Two independent jobs belonging to one box, with observable heap allocation.
val sink = arrayOfNulls<ByteArray>(2)
launches(
    loop {
        if (frameNumber == 0) println("PROFILE_WORK_READY")
        val data = ByteArray(65536)
        var value = 0.0
        repeat(12000) { value += kotlin.math.sin(it.toDouble()) }
        data[0] = value.toInt().toByte()
        sink[0] = data
    },
    frames {
        while (frame()) {
            sink[1] = ByteArray(131072)
            delay(40)
        }
    }
)
