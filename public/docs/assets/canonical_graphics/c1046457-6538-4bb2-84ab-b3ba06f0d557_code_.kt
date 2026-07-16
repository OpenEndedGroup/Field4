import afield.stdlib.linalg.Rectanglef
val maxElementCount = splatNum*2

val RADIX = 256
val WORKGROUP_SIZE = 512
val PARTITION_DIVISION = 8
val PARTITION_SIZE = PARTITION_DIVISION*WORKGROUP_SIZE

fun roundUp(n : Int, to : Int) = ((n + to - 1) / to) * to
val histogramSize = (1+4*RADIX+roundUp(maxElementCount, PARTITION_SIZE) * RADIX)
val inoutSize = maxElementCount

val partitionCount = roundUp(splatNum, PARTITION_SIZE)

var tempStorage = buffer {
    size = (histogramSize + inoutSize*2).ints
    addressable()
    hosted()
    dest()
}.buffer


var downsweep = computeModuleHere(parameters {
})


var mUpsweep = ComputeMesh()
var mSpine = ComputeMesh()
var mDownsweep = ComputeMesh()
upsweep += "m" to mUpsweep
spine += "m" to mSpine
downsweep += "m" to mDownsweep

renderer.computeList.others += "radixSort" to target { cmd, index ->
}

renderer.computeList.others += "radixSort" to target { cmd, index ->
    for(pass in 0 until 4) { // 4!
        //_print("render ${pass}")
        val three = listOf(mUpsweep, mSpine, mDownsweep)

        val p = pass
        three.forEach { it.pass = { p } }
        three.forEach { it.elementCountReference = { indirect + 4L} }
        three.forEach { it.globalHistogramReference = { tempStorage + 4L } }
        three.forEach { it.partitionHistogramReference = { tempStorage + 4L + 4L * 4 * RADIX } }

        run {
            var a = { splatDistances + 0L}
            var b = { tempStorage + 4L +  4L * 4 * RADIX + roundUp(maxElementCount, PARTITION_SIZE) * RADIX* 4L}
            if (pass % 2 == 1) {
                val c = a
                a = b
                b = c
            }
            three.forEach { it.keysInReference = a }
            three.forEach { it.keysOutReference = b }
        }
        run {
            var a = { splatIndices + 0L }
            var b = { tempStorage + 4L +  4L * 4 * RADIX + roundUp(maxElementCount, PARTITION_SIZE) * RADIX*4L + maxElementCount * 4L}
            if (pass % 2 == 1) {
                val c = a
                a = b
                b = c
            }
            three.forEach { it.valuesInReference = a }
            three.forEach { it.valuesOutReference = b }
        }

        computeMemoryBarrier(cmd)
        if (pass==0) {
            tempStorage.zero(cmd)
            transferMemoryBarrier(cmd, tempStorage)
        }

        // System.out.println(" -- pass $pass ------")
        mUpsweep.x = partitionCount
        upsweep.render(cmd, Rectanglef())
        computeMemoryBarrier(cmd)

        mSpine.x = RADIX
        spine.render(cmd, Rectanglef())
        computeMemoryBarrier(cmd)

        mDownsweep.x = partitionCount
        downsweep.render(cmd, Rectanglef())
        computeMemoryBarrier(cmd)
    }
    // hostReadBarrier(cmd,splatIndices)
    // hostReadBarrier(cmd,splatDistances)
}


if (false) {
    val cameraPosition = vec(0,0,0)
    splatIndices.mapAndRead_ints {
        val ind = this
        splatDistances.mapAndRead_ints {
            val dist = this
            for(n in 0 until 1000) {
                val N = ind.get(n)
                val v = vec(splatPos.get(4*N + 0), splatPos.get(4*N + 1), splatPos.get(4*N + 2)) - cameraPosition
                System.out.println("${v.length()}, ${N}, ${dist.get(n)-1317050000}")
            }
        }
    }
}
