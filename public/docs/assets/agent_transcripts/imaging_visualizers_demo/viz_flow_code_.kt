// The second frame is the first rotated 0.06 rad about its centre, so the arrows should
// trace a rotation field: little motion near the middle, tangential motion at the edges.
val a = Img.generateRGB(240, 180) { x, y ->
    val chk = if (x in 24..200 && y in 20..160 && ((x / 8 + y / 8) % 2 == 0)) 1f else 0f
    Triple(0.15f + 0.7f * chk, 0.18f + 0.5f * chk, 0.30f + 0.4f * chk)
}.luma()
val b = a.rotate(0.06)
opticalFlowLK(a, b, a.goodFeaturesToTrack(maxCount = 80, minDistance = 12).map { it.toVec2() }, levels = 3)
