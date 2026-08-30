// Live regression: connect beneath dust - video atlas after the complete dust_three graph launches.
// Prepares a future source-frame cut in the other resident look, switches atomically, then returns.
val switchP = _cuePresentation
val switchT = _cueTransport
val switchFirst = switchP.presentedLook.ifEmpty { switchP.requestedLook }
val switchSecond = if (switchFirst == "ritual-material") "haunted-urban-home-movie" else "ritual-material"

val switchCheck: suspend () -> Unit = {
    val until: suspend (String, () -> Boolean) -> Unit = { label, condition ->
        val deadline = System.nanoTime() + 45_000_000_000L
        do {
            kotlinx.coroutines.yield()
            check(System.nanoTime() < deadline) { "timeout: $label; ${switchP.describe()}" }
        } while (!condition())
    }

    val cutFrame = Math.floorMod(switchT.frame + 40, switchT.frameCount)
    switchP.prepareLook(switchSecond, cutFrame)
    until("prepare $switchSecond") { switchP.isLookPrepared(switchSecond) }
    println("LOOK_PREPARED $switchSecond@$cutFrame ${switchP.describe()}")

    until("reach cut frame $cutFrame") { switchT.frame == cutFrame }
    switchP.look(switchSecond)
    until("switch to $switchSecond") { switchP.presentedLook == switchSecond }
    check(switchP.presentedFrame == cutFrame) { "missed prepared cut $cutFrame: ${switchP.describe()}" }
    check(switchP.presentedVideoFrame == switchP.presentedFrame.toLong())
    check(switchP.presentedSplatFrames == List(15) { switchP.presentedFrame })
    check(switchP.presentedMxgFrames == List(15) { switchP.presentedFrame })
    println("LOOK_SWITCH_PASS $switchSecond ${switchP.describe()}")

    val returnFrame = Math.floorMod(switchT.frame + 40, switchT.frameCount)
    switchP.prepareLook(switchFirst, returnFrame)
    until("prepare $switchFirst") { switchP.isLookPrepared(switchFirst) }
    println("LOOK_PREPARED $switchFirst@$returnFrame ${switchP.describe()}")

    until("reach return frame $returnFrame") { switchT.frame == returnFrame }
    switchP.look(switchFirst)
    until("switch back to $switchFirst") { switchP.presentedLook == switchFirst }
    check(switchP.presentedFrame == returnFrame) { "missed prepared return $returnFrame: ${switchP.describe()}" }
    check(switchP.presentedVideoFrame == switchP.presentedFrame.toLong())
    check(switchP.presentedSplatFrames == List(15) { switchP.presentedFrame })
    check(switchP.presentedMxgFrames == List(15) { switchP.presentedFrame })
    println("LOOK_SWITCH_BACK_PASS $switchFirst ${switchP.describe()}")
}
start(switchCheck)
