import afield.graphics.scene.video.VideoCarouselClock
import afield.graphics.vulkan.Target

// One clock, sampled on the graphics queue after OpenXR locates this frame and before scene preparation.
// Head pose and movie decoding stay continuous; only ring geometry and screen shutters are quantized.
prop<afield.graphics.scene.video.VideoCarouselClock>(carouselClock) =
    (thisBox.getPropertyRaw("carouselClock") as? VideoCarouselClock) ?: VideoCarouselClock(carousel.surfaces.size)
prop<Int>(flashStaggerPerScreen) = (thisBox.getPropertyRaw("flashStaggerPerScreen") as? Int) ?: 0
carouselClock.flashStaggerFrames = flashStaggerPerScreen
prop<Double>(ringSpinCoarse) = (thisBox.getPropertyRaw("ringSpinCoarse") as? Double) ?: 0.0
prop<Double>(ringSpinFine) = (thisBox.getPropertyRaw("ringSpinFine") as? Double) ?: 0.0
prop<Boolean>(ringStepped) = (thisBox.getPropertyRaw("ringStepped") as? Boolean) ?: false
prop<Int>(ringStepFrames) = (thisBox.getPropertyRaw("ringStepFrames") as? Int) ?: 10
prop<Boolean>(ringSectorLock) = (thisBox.getPropertyRaw("ringSectorLock") as? Boolean) ?: false
prop<Int>(ringSectorsPerStep) = (thisBox.getPropertyRaw("ringSectorsPerStep") as? Int) ?: 1
prop<Double>(ringLockDrift) = (thisBox.getPropertyRaw("ringLockDrift") as? Double) ?: 0.0
fun updateRingClock() {
    carouselClock.motion = VideoCarouselClock.Motion(
        ringSpinCoarse + ringSpinFine,
        ringStepped,
        ringStepFrames,
        ringSectorLock,
        ringSectorsPerStep,
        ringLockDrift,
    )
}
updateRingClock()
val motionMirrors = mutableListOf<() -> Unit>()
controlGroup("ring motion") {
    slider(
        "Spin coarse (turns/s)",
        min = -30.0,
        max = 30.0,
        step = .01,
        initialValue = ringSpinCoarse,
        hoverText =
            "Signed manual turns per second, plus the fine control. Sector lock replaces this rate with exact screen-sector hops.",
    ) {
        ringSpinCoarse = it
        updateRingClock()
    }.also { h -> motionMirrors += { h.set(ringSpinCoarse) } }
    slider(
        "Spin fine (turns/s)",
        min = -1.0,
        max = 1.0,
        step = .001,
        initialValue = ringSpinFine,
        hoverText = "Manual speed trim added to coarse spin. For very slow drift around a lock, use Lock drift below.",
    ) {
        ringSpinFine = it
        updateRingClock()
    }.also { h -> motionMirrors += { h.set(ringSpinFine) } }
    button(
        "Stop ring rotation",
        hoverText =
            "Stop manual spin and lock drift, disable sector lock and hold the current angle. Screen flashes retain their settings.",
    ) { down ->
        if (down) {
            ringSpinCoarse = 0.0
            ringSpinFine = 0.0
            ringSectorLock = false
            ringLockDrift = 0.0
            updateRingClock()
        }
    }
}
controlGroup("quantized rotation / shared clock") {
    checkbox(
        "Stepped rotation",
        initialValue = ringStepped,
        hoverText =
            "Hold each geometry pose for Frames per step headset ticks. Uses OpenXR predicted display time and refresh period, never the video clock. Flash intervals use the very same integer headset ticks.",
    ) {
        ringStepped = it
        updateRingClock()
    }.also { h -> motionMirrors += { h.set(ringStepped) } }
    slider(
        "Frames per step",
        min = 1.0,
        max = 120.0,
        step = 1.0,
        initialValue = ringStepFrames.toDouble(),
        hoverText =
            "Whole headset refresh periods per rotation step. Ten ticks is 12 steps/second at 120 Hz. A matching ten-frame flash interval with one frame on exposes only the start of each held pose.",
    ) {
        ringStepFrames = it.toInt()
        updateRingClock()
    }.also { h -> motionMirrors += { h.set(ringStepFrames.toDouble()) } }
    checkbox(
        "Lock to screen sectors",
        initialValue = ringSectorLock,
        hoverText =
            "Force exact sector hops on the shared tick grid, replacing manual coarse/fine spin. With eight screens one sector is 45 degrees. Their outlines lock; different movie ages and per-screen looks still exchange places.",
    ) {
        ringSectorLock = it
        updateRingClock()
    }.also { h -> motionMirrors += { h.set(ringSectorLock) } }
    slider(
        "Sectors per step",
        min = -16.0,
        max = 16.0,
        step = 1.0,
        initialValue = ringSectorsPerStep.toDouble(),
        hoverText =
            "Signed whole screen sectors advanced at each step boundary. Zero holds geometry apart from drift; one advances one sector. Negative reverses the hop direction.",
    ) {
        ringSectorsPerStep = it.toInt()
        updateRingClock()
    }.also { h -> motionMirrors += { h.set(ringSectorsPerStep.toDouble()) } }
    slider(
        "Lock drift (turns/s)",
        min = -.05,
        max = .05,
        step = .0001,
        initialValue = ringLockDrift,
        hoverText =
            "Tiny continuous detuning added to exact sector hops. Zero is a geometric lock. 0.001 adds 0.36 degrees/second; change the sign to approach from the other side.",
    ) {
        ringLockDrift = it
        updateRingClock()
    }.also { h -> motionMirrors += { h.set(ringLockDrift) } }
    button(
        "Try sector lock + flash",
        hoverText =
            "Use ten headset frames per step, one sector per step and zero drift. Every screen flashes for the first one of those ten ticks. At 120 Hz this is a shared 12 Hz zoetrope, while head pose still updates every rendered frame.",
    ) { down ->
        if (down) {
            ringStepped = true
            ringSectorLock = true
            ringStepFrames = 10
            ringSectorsPerStep = 1
            ringLockDrift = 0.0
            updateRingClock()
            carouselClock.flashes =
                List(carousel.surfaces.size) { VideoCarouselClock.Flash(enabled = true, onFrames = 1, offFrames = 9) }
        }
    }
    button(
        "Open all shutters",
        hoverText =
            "Disable headset flashing on all screens without changing rotation, movie speed or paired blur cadence.",
    ) { down ->
        if (down) carouselClock.flashes = carouselClock.flashes.map { it.copy(enabled = false) }
    }
}
controlGroup("shared flash timing") {
    slider(
        "Flash stagger / screen (frames)",
        min = -240.0,
        max = 240.0,
        step = 1.0,
        initialValue = flashStaggerPerScreen.toDouble(),
        hoverText =
            "Extra whole headset ticks of phase per screen: screen 1 adds zero, screen 2 one stagger, screen 3 two, and so on. Negative reverses the wave. Adds to each screen's own phase; independent of delayed movie spacing and keyframe recall staggering.",
    ) {
        flashStaggerPerScreen = it.toInt()
        carouselClock.flashStaggerFrames = flashStaggerPerScreen
    }.also { h -> motionMirrors += { h.set(flashStaggerPerScreen.toDouble()) } }
    button(
        "Copy screen 1 flash to all",
        hoverText =
            "Copy enabled state, both durations, both brightness levels and local phase from screen 1 to every screen. The shared stagger remains active.",
    ) { down ->
        if (down) carouselClock.flashes = List(carousel.surfaces.size) { carouselClock.flashes.first() }
    }
    button(
        "Align all flash phases",
        hoverText =
            "Zero local phases and shared stagger so every screen with the same durations flashes together. Brightness, durations and enable states stay unchanged.",
    ) { down ->
        if (down) {
            flashStaggerPerScreen = 0
            carouselClock.flashStaggerFrames = 0
            carouselClock.flashes = carouselClock.flashes.map { it.copy(phaseFrames = 0) }
        }
    }
}
displayOpen("shared flash timing")
displayOpen("ring motion")
displayOpen("quantized rotation / shared clock")
displayOpen("motion status")

// Native screen-look lanes: capture one shutter, recall onto any selected screens with the appearance.
// Old keys without these lanes retain the current shutter settings.
val lookPool = screenLooks
lookPool.scope.booleanTarget("flash.enabled", { carouselClock.flashes[lookPool.captureScreen].enabled }) { v ->
    carouselClock.flashes =
        carouselClock.flashes.mapIndexed { i, f -> if (i in lookPool.targetScreens) f.copy(enabled = v) else f }
}
lookPool.scope.intTarget("flash.onFrames", { carouselClock.flashes[lookPool.captureScreen].onFrames }, 0..2400) { v ->
    carouselClock.flashes =
        carouselClock.flashes.mapIndexed { i, f ->
            if (i in lookPool.targetScreens)
                f.copy(onFrames = v, offFrames = if (v == 0) f.offFrames.coerceAtLeast(1) else f.offFrames)
            else f
        }
}
lookPool.scope.intTarget("flash.offFrames", { carouselClock.flashes[lookPool.captureScreen].offFrames }, 0..2400) { v ->
    carouselClock.flashes =
        carouselClock.flashes.mapIndexed { i, f ->
            if (i in lookPool.targetScreens)
                f.copy(offFrames = v, onFrames = if (v == 0) f.onFrames.coerceAtLeast(1) else f.onFrames)
            else f
        }
}
lookPool.scope.intTarget(
    "flash.phaseFrames",
    { carouselClock.flashes[lookPool.captureScreen].phaseFrames },
    -2400..2400,
) { v ->
    carouselClock.flashes =
        carouselClock.flashes.mapIndexed { i, f -> if (i in lookPool.targetScreens) f.copy(phaseFrames = v) else f }
}
lookPool.scope.floatTarget("flash.onBrightness", { carouselClock.flashes[lookPool.captureScreen].onBrightness }) { v ->
    carouselClock.flashes =
        carouselClock.flashes.mapIndexed { i, f ->
            if (i in lookPool.targetScreens) f.copy(onBrightness = v.coerceIn(0f, 16f)) else f
        }
}
lookPool.scope.floatTarget("flash.offBrightness", { carouselClock.flashes[lookPool.captureScreen].offBrightness }) { v
    ->
    carouselClock.flashes =
        carouselClock.flashes.mapIndexed { i, f ->
            if (i in lookPool.targetScreens) f.copy(offBrightness = v.coerceIn(0f, 16f)) else f
        }
}

// Insert before all surface prepare targets so the current rotation reaches their push constants.
val clockStereo = stereo
val clockCarousel = carousel
val ownedClock = carouselClock
var lastWrittenAngle = Double.NaN
val clockTarget = Target { _, _ ->
    val externalAngle = clockCarousel.azimuthDegrees.toDouble()
    if (lastWrittenAngle.isNaN() || kotlin.math.abs(externalAngle - lastWrittenAngle) > .001)
        ownedClock.setAngle(externalAngle)
    val xr = clockStereo.openXR
    val time = xr?.locatedViewDisplayTime?.takeIf { it > 0L } ?: System.nanoTime()
    val period = xr?.locatedViewDisplayPeriod?.takeIf { it > 0L } ?: 8_333_333L
    val angle = ownedClock.advance(time, period).toFloat()
    if (angle != clockCarousel.azimuthDegrees) {
        clockCarousel.azimuthDegrees = angle
        clockCarousel.layout()
    }
    lastWrittenAngle = angle.toDouble()
    clockCarousel.surfaces.indices.forEach { i ->
        val brightness = ownedClock.brightness(i)
        clockCarousel.surfaces[i].brightness = brightness
        clockStereo.layered.modules["carousel-screen-$i"]?.current?.enabled = brightness > 0f
    }
}
renderer.runOnQueueThread {
    val existing = clockStereo.beforeScene.filterKeys { it != "carousel-display-clock" }
    clockStereo.beforeScene.clear()
    clockStereo.beforeScene["carousel-display-clock"] = clockTarget
    clockStereo.beforeScene.putAll(existing)
}

// Status and control mirroring stay on the Runloop; timing decisions above stay on the graphics queue.
var lastClockStatus = 0L
loop {
    val now = System.nanoTime()
    if (now - lastClockStatus > 250_000_000L) {
        lastClockStatus = now
        motionMirrors.forEach { it() }
        val m = ownedClock.motion
        val stepRate = ownedClock.refreshHz / m.stepFrames
        markdown(
            "motion status",
            """
**${ownedClock.refreshHz.f2} Hz** · shared headset tick **${ownedClock.tick}** · **${stepRate.f2} steps/s**.

${if (m.sectorLock) "Sector lock: ${m.sectorsPerStep} sectors/step, ${m.driftTurnsPerSecond} turns/s drift" else "Manual spin: ${m.turnsPerSecond.f2} turns/s · ${if (m.stepped) "stepped" else "continuous"}"} · angle **${ownedClock.angleDegrees.f2}°**.

Flash on/off durations and levels are in each material box; shared stagger is above. Rotation and flashes use the same tick grid; video decoding and head pose keep their own continuous timing. Desktop mode previews a nominal 120 Hz grid.
        """,
            open = false,
        )
    }
    frame()
}
    .end {
        renderer.runOnQueueThread {
            clockStereo.beforeScene.remove("carousel-display-clock", clockTarget)
            clockCarousel.surfaces.indices.forEach { i ->
                clockCarousel.surfaces[i].brightness = 1f
                clockStereo.layered.modules["carousel-screen-$i"]?.current?.enabled = true
            }
        }
    }
