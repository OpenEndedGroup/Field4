// Run as a fragment in dust - surface interaction after forces and history have launched.
renderer.device.waitIdle()
val order = renderer.computeList.modules.keys.toList()
check(order.indexOf("dust-surface-interaction") in 0 until order.indexOf("dust-forces"))
check(order.indexOf("dust-forces") < order.indexOf("dust-history"))
check(_surfaceModule.current.valid)
check(renderer.computeList.modules.getValue("dust-forces").current.valid)
check(_mxgPresentedRef.get()?.frames?.size == 15)
check(_anchorSelection.activeCount > 0)

var depth = 0
var flow = 0
var anchors = 0
var cells = 0
_surfaceField.mapAndRead_bytes {
    order(java.nio.ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until _dust.count) {
        val k = i * 64
        val values = FloatArray(16) { n -> getFloat(k + n * 4) }
        check(values.all { it.isFinite() })
        if (values.slice(0..2).sumOf { abs(it).toDouble() } > 1e-6) depth++
        if (values.slice(4..6).sumOf { abs(it).toDouble() } > 1e-6) flow++
        if (values.slice(8..10).sumOf { abs(it).toDouble() } > 1e-6) anchors++
        val cell = values[12].toInt()
        if (cell in 0..14) cells = cells or (1 shl cell)
    }
}
check(depth > _dust.count / 2 && flow > 100 && anchors > 0)
check(cells == 0x7fff)
val p = _cuePresentation
check(p.presentedVideoFrame == p.presentedFrame.toLong())
check(p.presentedSplatFrames.all { it == p.presentedFrame })
check(p.presentedMxgFrames.all { it == p.presentedFrame })
println("SURFACE_GPU_PASS order=$order depth=$depth flow=$flow anchors=$anchors active=${_anchorSelection.activeCount} cells=${Integer.bitCount(cells)} ${p.describe()}")
