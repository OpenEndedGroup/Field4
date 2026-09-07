// A sustained box workload makes statistical line samples observable in a short UI check.
var checksum = 0.0
var retained = ByteArray(0)
loop {
    if (frameNumber == 0) println("PROFILE_STACKS_READY")
    repeat(450000) { checksum += kotlin.math.sin(it.toDouble()) }
    retained = ByteArray(262144)
    retained[0] = checksum.toInt().toByte()
    delay(4)
}
