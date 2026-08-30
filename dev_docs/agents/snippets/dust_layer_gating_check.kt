// Live regression: connect a temporary box to dust - splats circle and dust - mxg circle,
// launch this code, await GATE_ALL_PASS and GATE_RESTORED, then delete the temporary box.
// Assumes dust_three's default zero cell offsets. Exercises real decoder/upload counters.
val p = _cuePresentation
val transport = _cueTransport
val splats = _splatBank
val mxg = _mxgBank
val shades = listOf("splatStream", "dust-splat-rectangles", "dust-splat-outlines").map { _mainRtt.modules.getValue(it) }
val mxgShade = _mainRtt.modules.getValue("dust-mxg-circle")
val savedShades = (shades + mxgShade).map { it to it.current.enabled }
val savedMasters = p.splatsEnabled to p.mxgEnabled
val savedPlaying = transport.playing

frames {
    val checkVisibleFrames = {
        if (p.splatsVisible) check(p.presentedSplatFrames.all { it.toLong() == p.presentedVideoFrame })
        if (p.mxgVisible) check(p.presentedMxgFrames.all { it.toLong() == p.presentedVideoFrame })
        splats.failure?.let { throw it }
        mxg.failure?.let { throw it }
    }
    val until: suspend (String, () -> Boolean) -> Unit = { label, condition ->
        val deadline = System.nanoTime() + 45_000_000_000L
        do {
            frame()
            checkVisibleFrames()
            check(System.nanoTime() < deadline) { "timeout: $label; ${p.describe()}" }
        } while (!condition())
    }
    try {
        transport.playing = true
        transport.resetClock()
        p.splatsEnabled = true; p.mxgEnabled = true
        savedShades.forEach { it.first.enable() }
        until("initial presentation") { p.splatsVisible && p.mxgVisible }

        shades[0].disable(); shades[1].disable()
        until("outlines only") { p.activeSplatShades == setOf("outlines") }
        val activeUploads = p.splatUploads
        until("last shade keeps decoder alive") { p.splatUploads > activeUploads }
        check(splats.active)
        println("GATE_PASS one remaining splat shade keeps the shared bank alive")

        shades[2].disable()
        until("all splats parked") { !p.splatsActive && !splats.preparing }
        val sr = splats.decodedFrames; val su = p.splatUploads; val mu = p.mxgUploads
        check(!splats.request(IntArray(15)))
        until("MXG continues without splats") { p.mxgUploads >= mu + 2 }
        check(sr == splats.decodedFrames && su == p.splatUploads)
        check(!p.splatsVisible)
        println("GATE_PASS all splat shades off: reads/uploads frozen at $sr/$su while MXG advances")

        shades[1].enable()
        until("filled shade rejoins") { p.splatsVisible && p.splatUploads > su }
        check(p.activeSplatShades == setOf("rectangles"))
        println("GATE_PASS returning splats rejoin video on frame ${p.presentedFrame}")

        mxgShade.disable()
        until("MXG parked") { !p.mxgActive && !mxg.preparing }
        val mr = mxg.decodedFrames; val mu2 = p.mxgUploads; val su2 = p.splatUploads
        check(!mxg.request(0))
        until("splats continue without MXG") { p.splatUploads >= su2 + 2 }
        check(mr == mxg.decodedFrames && mu2 == p.mxgUploads)
        check(!p.mxgVisible)
        println("GATE_PASS MXG off: reads/uploads frozen at $mr/$mu2 while splats advance")

        p.splatsEnabled = false; p.mxgEnabled = false
        until("both masters parked") { !p.splatsActive && !p.mxgActive && !splats.preparing && !mxg.preparing }
        val parkedReads = splats.decodedFrames to mxg.decodedFrames
        val parkedUploads = p.splatUploads to p.mxgUploads
        val videoSets = p.presentations
        until("video continues alone") { p.presentations >= videoSets + 2 }
        check(parkedReads == (splats.decodedFrames to mxg.decodedFrames))
        check(parkedUploads == (p.splatUploads to p.mxgUploads))
        println("GATE_PASS both layers off: video advances, both banks do no new work")

        transport.playing = false
        until("paused clock settled") { p.pendingFrame == -1 && p.presentedFrame == transport.frame }
        val stoppedFrame = transport.frame
        p.splatsEnabled = true; p.mxgEnabled = true
        shades[2].enable(); mxgShade.enable()
        until("same-frame resume") { p.splatsVisible && p.mxgVisible }
        check(p.presentedFrame == stoppedFrame)
        println("GATE_PASS paused same-frame re-enable: $stoppedFrame, no stale flash")

        repeat(4) {
            p.splatsEnabled=false; p.mxgEnabled=false; frame()
            p.splatsEnabled=true; p.mxgEnabled=true; frame()
        }
        until("rapid toggle recovery") { p.splatsVisible && p.mxgVisible }
        println("GATE_ALL_PASS module switches, master switches, rapid toggles and matched resume")
    } finally {
        savedShades.forEach { (module, enabled) -> if(enabled) module.enable() else module.disable() }
        p.splatsEnabled = savedMasters.first; p.mxgEnabled = savedMasters.second
        transport.playing = savedPlaying
        transport.resetClock()
        println("GATE_RESTORED original playback and layer settings")
    }
}
