import afield.animation.keyframes.*
import afield.graphics.scene.reading.*
import afield.ui.output.*
import java.nio.file.Files

// One document-wide scope. Re-running this box replaces registrations and reads the LIVE models;
// restoreValues=false prevents stale slider caches from overwriting the look while building the UI.
// Stable target names and native key JSON keep saved looks usable as controls are added.
rootProp<KeyframeScope>(readingKeyframes) = KeyframeScope(schemaVersion = 1)
val keys = root.readingKeyframes
val drawingBox = thisBox.inside.boxes.getValue("text1_drawing")
val bodyBox = thisBox.inside.boxes.getValue("text1_body")
val scoreBox = thisBox.inside.boxes.getValue("text1_score")
val spaceBox = thisBox.inside.boxes.getValue("text1_context")
val navigationBox = thisBox.inside.boxes.getValue("text1_navigation")
fun frames() = poem.examples.flatMap { it.player.diagram.textFrames }
fun grade() = drawing.compositor.settings
fun pendingTextCacheQuality() = ReadingTextBlurConfig(
    root.textCacheWidth, root.textCacheHeight, root.textCacheMinimum,
    root.textCacheHalving, (root.textCacheTaps - 1) / 2, root.textCacheCutoff,
)

drawingBox.controlGroup("text blur quality", restoreValues = false) {
    keyframeSlider(keys, "drawing.cache-width-pixels", "Cache width", unit = "px", get = { root.textCacheWidth.toDouble() }, min = 512.0, max = 8192.0, step = 512.0) {
        root.textCacheWidth = it.toInt()
    }
    keyframeSlider(keys, "drawing.cache-height-pixels", "Cache height", unit = "px", get = { root.textCacheHeight.toDouble() }, min = 256.0, max = 8192.0, step = 256.0) {
        root.textCacheHeight = it.toInt()
    }
    keyframeSlider(keys, "drawing.broad-level-minimum-pixels", "Broad level minimum", unit = "px", get = { root.textCacheMinimum.toDouble() }, min = 64.0, max = 2048.0, step = 64.0) {
        root.textCacheMinimum = it.toInt()
    }
    keyframeSlider(keys, "drawing.levels-per-size-halving", "Levels per size halving", get = { root.textCacheHalving.toDouble() }, min = 1.0, max = 8.0, step = 1.0) {
        root.textCacheHalving = it.toInt()
    }
    keyframeSlider(keys, "drawing.maximum-taps-per-axis", "Maximum taps per axis", get = { root.textCacheTaps.toDouble() }, min = 33.0, max = 1025.0, step = 16.0) {
        root.textCacheTaps = it.toInt()
    }
    keyframeSlider(keys, "drawing.gaussian-support-sigma", "Gaussian support", unit = "σ", get = { root.textCacheCutoff.toDouble() }, min = 2.0, max = 6.0, step = .25) {
        root.textCacheCutoff = it.toFloat()
    }
    button("Apply text blur quality", hoverText = "Rebuild just the cached masks. Pose, physics, score and the headset session continue. Higher resolutions use more VRAM and take longer to prepare.") { down ->
        if (down) {
            runCatching {
                require(root.textCacheMinimum <= minOf(root.textCacheWidth, root.textCacheHeight)) {
                    "Broad minimum must fit within both base dimensions"
                }
                val quality = pendingTextCacheQuality()
                drawing.requestTextBlurRebuild(quality)
                display("text blur quality status", "<div style='padding:12px'>Queued ${quality.estimatedBytes(poem.blurRequests.size) / (1024 * 1024)} MiB of text masks. Current settings appear in the frame output after preparation.</div>")
            }.onFailure {
                display("text blur quality status", "<div style='padding:12px'>${it.message}</div>")
            }
        }
    }
}

drawingBox.controlGroup("drawing", restoreValues = false) {
    button("Recenter poem") { down -> if (down) drawing.recenter() }
    keyframeCheckbox(keys, "drawing.gently-face-the-reader", "Gently face the reader", get = { drawing.followReader }) { drawing.followReader = it }
    keyframeSlider(keys, "drawing.turning-half-life-seconds", "Turning half-life", unit = "s", get = { drawing.turnHalfLife.toDouble() }, min = .1, max = 10.0) {
        drawing.turnHalfLife = it.toFloat()
    }
    keyframeSlider(keys, "drawing.stroke-width-multiplier", "Stroke width multiplier", get = { poem.current.strokeWidthScale.toDouble() }, min = .2, max = 4.0) {
        poem.examples.forEach { e -> e.player.strokeWidthScale = it.toFloat() }
    }
    keyframeSlider(keys, "drawing.point-radius-multiplier", "Point radius multiplier", get = { poem.current.dotRadiusScale.toDouble() }, min = .2, max = 4.0) {
        poem.examples.forEach { e -> e.player.dotRadiusScale = it.toFloat() }
    }
    keyframeCheckbox(keys, "drawing.show-diagram-dots", "Show diagram dots", get = { poem.current.showDots }) { poem.examples.forEach { e -> e.player.showDots = it } }
}

drawingBox.controlGroup("skeleton", restoreValues = false) {
    keyframeCheckbox(keys, "drawing.show-skeleton", "Show skeleton", get = { root.showSkeleton }) { root.showSkeleton = it }
    keyframeCheckbox(keys, "drawing.show-simd-nodes", "Show SIMD nodes", get = { root.showPhysicsNodes },
        hoverText = "Exact SIMD positions tested against the body. Bright = receiving force; dim = pinned.") {
        root.showPhysicsNodes = it
    }
    keyframeCheckbox(keys, "drawing.show-body-force-lines", "Show body force lines", get = { root.showBodyForces },
        hoverText = "Connect the selected bone centreline to the node when the solver applies a body force.") {
        root.showBodyForces = it
    }
}

drawingBox.controlGroup("frosted frames", restoreValues = false) {
    keyframeCheckbox(keys, "drawing.frost-behind-text-frames", "Frost behind text frames", get = { frames().firstOrNull()?.frost?.enabled ?: true }) { enabled ->
        frames().forEach { it.frost.enabled = enabled }
    }
    keyframeSlider(keys, "drawing.frost-opacity", "Frost opacity", get = { (frames().firstOrNull()?.frost?.opacity ?: 1f).toDouble() }, min = 0.0, max = 1.0) { value ->
        frames().forEach { it.frost.opacity = value.toFloat() }
    }
    keyframeSlider(keys, "drawing.frost-blur-scene-pixels", "Frost blur", unit = "scene px", get = { (frames().firstOrNull()?.frost?.blurPixels ?: 5f).toDouble() }, min = 0.0, max = 32.0) { value ->
        frames().forEach { it.frost.blurPixels = value.toFloat() }
    }
    keyframeSlider(keys, "drawing.frost-brightness", "Frost brightness", get = { (frames().firstOrNull()?.frost?.brightness ?: 1.1f).toDouble() }, min = 0.0, max = 2.0) { value ->
        frames().forEach { it.frost.brightness = value.toFloat() }
    }
}

drawingBox.controlGroup("frost gamma", restoreValues = false) {
    keyframeSlider(keys, "drawing.frost-blur-gamma", "Frost blur gamma", get = { root.frostBlurGamma.toDouble() }, min = .25, max = 4.0, step = .01) {
        root.frostBlurGamma = it.toFloat()
    }
}

drawingBox.controlGroup("frost black level", restoreValues = false) {
    keyframeSlider(keys, "drawing.frost-black-level", "Frost black level", get = { root.frostBlackLevel.toDouble() }, min = 0.0, max = .1, step = .001) {
        root.frostBlackLevel = it.toFloat()
    }
}

drawingBox.controlGroup("text blur appearance", restoreValues = false) {
    keyframeSlider(keys, "drawing.final-contrast-power", "Final contrast", unit = "power", get = { (1f / grade().gamma.w).toDouble() }, min = .25, max = 4.0, step = .01) {
        grade().gamma.w = 1f / it.toFloat()
    }
    keyframeSlider(keys, "drawing.text-blur-contrast-power", "Text blur contrast", unit = "power", get = { root.textBlurContrast.toDouble() }, min = .25, max = 4.0, step = .01) {
        root.textBlurContrast = it.toFloat()
    }
    keyframeSlider(keys, "drawing.text-blur-gain", "Text blur gain", get = { root.textBlurGain.toDouble() }, min = 0.0, max = 4.0, step = .01) {
        root.textBlurGain = it.toFloat()
    }
}

drawingBox.controlGroup("blur cascade", restoreValues = false) {
    keyframeSlider(keys, "drawing.medium-blur-radius", "Medium blur radius", get = { grade().mediumRadius.toDouble() }, min = 0.0, max = 8.0) {
        grade().mediumRadius = it.toFloat()
    }
    keyframeSlider(keys, "drawing.broad-blur-radius", "Broad blur radius", get = { grade().broadRadius.toDouble() }, min = 0.0, max = 8.0) {
        grade().broadRadius = it.toFloat()
    }
}

drawingBox.controlGroup("green compositor", restoreValues = false) {
    keyframeSlider(keys, "drawing.blur-mix", "Blur mix", get = { grade().selection.z.toDouble() }, min = 0.0, max = 1.0) {
        grade().selection.z = it.toFloat()
    }
    keyframeSlider(keys, "drawing.green-selector-gain", "Green selector gain", get = { grade().selection.x.toDouble() }, min = 0.0, max = 8.0) {
        grade().selection.x = it.toFloat()
    }
    keyframeSlider(keys, "drawing.green-selector-offset", "Green selector offset", get = { grade().selection.y.toDouble() }, min = -1.0, max = 1.0) {
        grade().selection.y = it.toFloat()
    }
    keyframeSlider(keys, "drawing.source-green-scale", "Source green scale", get = { grade().selection.w.toDouble() }, min = 0.0, max = 4.0) {
        grade().selection.w = it.toFloat()
    }
    keyframeSlider(keys, "drawing.selector-sharp-weight", "Selector sharp weight", get = { grade().selectorSource.x.toDouble() }, min = 0.0, max = 1.0) {
        grade().selectorSource.x = it.toFloat()
    }
    keyframeSlider(keys, "drawing.selector-medium-weight", "Selector medium weight", get = { grade().selectorSource.y.toDouble() }, min = 0.0, max = 1.0) {
        grade().selectorSource.y = it.toFloat()
    }
    keyframeSlider(keys, "drawing.selector-broad-weight", "Selector broad weight", get = { grade().selectorSource.z.toDouble() }, min = 0.0, max = 1.0) {
        grade().selectorSource.z = it.toFloat()
    }
    keyframeSlider(keys, "drawing.green-from-red-blue", "Green from red / blue", get = { grade().reconstruction.x.toDouble() }, min = 0.0, max = 1.0) {
        grade().reconstruction.x = it.toFloat()
        grade().reconstruction.y = 1f - it.toFloat()
    }
}

drawingBox.controlGroup("light and colour", restoreValues = false) {
    keyframeSlider(keys, "drawing.exposure", "Exposure", get = { grade().reconstruction.z.toDouble() }, min = 0.0, max = 4.0) {
        grade().reconstruction.z = it.toFloat()
    }
    keyframeSlider(keys, "drawing.saturation", "Saturation", get = { grade().saturation.x.toDouble() }, min = 0.0, max = 3.0) {
        grade().saturation.x = it.toFloat()
    }
    keyframeSlider(keys, "drawing.fxaa-mix", "FXAA mix", get = { grade().fxaa.x.toDouble() }, min = 0.0, max = 1.0) { grade().fxaa.x = it.toFloat() }
    keyframeColorPicker(keys, "drawing.background", "Background", get = { grade().background }) { grade().background.set(it) }
    keyframeColorPicker(keys, "drawing.shadow-tone-alpha-strength", "Shadow tone (alpha = strength)", get = { grade().shadowTone }) { grade().shadowTone.set(it) }
    keyframeColorPicker(keys, "drawing.highlight-tone-alpha-strength", "Highlight tone (alpha = strength)", get = { grade().highlightTone }) { grade().highlightTone.set(it) }
}

drawingBox.controlGroup("display dither", restoreValues = false) {
    keyframeSlider(keys, "drawing.dither-8-bit-code-steps", "Dither", unit = "8-bit steps", get = { grade().supersampling.w.toDouble() }, min = 0.0, max = 2.0) {
        grade().supersampling.w = it.toFloat()
    }
}

bodyBox.controlGroup("body interaction", restoreValues = false) {
    keyframeCheckbox(keys, "body.body-interaction", "Body interaction", get = { bodyBox.readingBodyEnabled }) { bodyBox.readingBodyEnabled = it }
    keyframeCheckbox(keys, "body.waft-away-outward-band", "Waft away (outward band)", get = { bodyBox.readingBodyWaft }) { bodyBox.readingBodyWaft = it }
    button("Waft away and settle") { down -> if (down) bodyWaftPreset() }
    keyframeSlider(keys, "body.waft-acceleration-m-s", "Waft acceleration", unit = "m/s²", get = { bodyBox.readingBodyWaftStrength.toDouble() }, min = 0.0, max = 15.0) {
        bodyBox.readingBodyWaftStrength = it.toFloat()
    }
    keyframeSlider(keys, "body.outside-reach-m", "Outside reach", unit = "m", get = { bodyBox.readingBodyReach.toDouble() }, min = 0.0, max = 1.5) {
        bodyBox.readingBodyReach = it.toFloat()
    }
    keyframeSlider(keys, "body.push-inside-capsule", "Push inside capsule", get = { bodyBox.readingBodyPush.toDouble() }, min = 0.0, max = 80.0) {
        bodyBox.readingBodyPush = it.toFloat()
    }
    keyframeSlider(keys, "body.standoff-gap-m", "Standoff gap", unit = "m", get = { bodyBox.readingBodyGap.toDouble() }, min = 0.0, max = .5) {
        bodyBox.readingBodyGap = it.toFloat()
    }
    keyframeSlider(keys, "body.range-falloff", "Range falloff", get = { bodyBox.readingBodyFalloff.toDouble() }, min = 0.0, max = 8.0) {
        bodyBox.readingBodyFalloff = it.toFloat()
    }
    keyframeSlider(keys, "body.body-acceleration-cap-0-off", "Body acceleration cap (0 off)", unit = "m/s²", get = { bodyBox.readingBodyMaximum.toDouble() }, min = 0.0, max = 20.0) {
        bodyBox.readingBodyMaximum = it.toFloat()
    }
    keyframeSlider(keys, "body.body-radius-scale", "Body radius scale", get = { bodyBox.readingBodyRadiusScale.toDouble() }, min = .1, max = 3.0) {
        bodyBox.readingBodyRadiusScale = it.toFloat()
    }
    keyframeCheckbox(keys, "body.include-finger-segments", "Include finger segments", get = { bodyBox.readingBodyHands }) { bodyBox.readingBodyHands = it }
    keyframeCheckbox(keys, "body.include-head", "Include head", get = { bodyBox.readingBodyHead }) { bodyBox.readingBodyHead = it }
    keyframeSlider(keys, "body.classic-gathering-gain", "Classic gathering gain", get = { bodyBox.readingBodyPull.toDouble() }, min = 0.0, max = 40.0,
        hoverText = "Used only when Waft away is off. Positive values gather anchors toward the standoff surface.") {
        bodyBox.readingBodyPull = it.toFloat()
    }
    keyframeSlider(keys, "body.classic-inside-reach-0-all", "Classic inside reach (0 = all)", unit = "m", get = { bodyBox.readingBodyInsideReach.toDouble() }, min = 0.0, max = 1.0) {
        bodyBox.readingBodyInsideReach = it.toFloat()
    }
}

scoreBox.controlGroup("gentle physics", restoreValues = false) {
    keyframeSlider(keys, "score.layout-spring", "Layout spring", get = { poem.current.spring.toDouble() }, min = 0.0, max = 100.0) {
        poem.examples.forEach { e -> e.player.spring = it.toFloat() }
    }
    keyframeSlider(keys, "score.link-stiffness", "Link stiffness", get = { poem.current.constraintStrength.toDouble() }, min = 0.0, max = 80.0) {
        poem.examples.forEach { e -> e.player.constraintStrength = it.toFloat() }
    }
    keyframeSlider(keys, "score.gravity", "Gravity", get = { poem.current.gravity.toDouble() }, min = -.3, max = .1) {
        poem.examples.forEach { e -> e.player.gravity = it.toFloat() }
    }
    keyframeSlider(keys, "score.air-movement", "Air movement", get = { poem.current.wind.toDouble() }, min = 0.0, max = .3) {
        poem.examples.forEach { e -> e.player.wind = it.toFloat() }
    }
    button("Still proof / reset") { down ->
        if (down) {
            poem.current.gravity = 0f
            poem.current.wind = 0f
            poem.current.resetPositions()
            poem.current.playback.playing = false
            poem.current.playback.seek(6.0)
        }
    }
}

spaceBox.controlGroup("reading space", restoreValues = false) {
    keyframeCheckbox(keys, "context.show-both-eyes-side-by-side", "Show both eyes side by side", get = { spaceBox.showStereoPair }) { spaceBox.showStereoPair = it }
    keyframeCheckbox(keys, "context.desktop-preview", "Desktop preview", get = { renderer.desktopPresentationEnabled }) { renderer.desktopPresentationEnabled = it }
}

navigationBox.controlGroup("move through the reading space", restoreValues = false) {
    keyframeCheckbox(keys, "navigation.left-stick-movement", "Left stick movement", get = { navigationBox.readingWalkEnabled }) { navigationBox.readingWalkEnabled = it }
    keyframeSlider(keys, "navigation.walking-speed-m-s", "Walking speed", unit = "m/s", get = { navigationBox.readingWalkSpeed.toDouble() }, min = .05, max = 2.0) { navigationBox.readingWalkSpeed = it.toFloat() }
}

// Capture individual values after the aggregate controls: a recall preserves intentional differences
// between examples and frosted panels. Getters resolve inherited props on every use, including reloads.
for (i in poem.examples.indices) {
    fun player() = poem.examples[i].player
    val prefix = "example.$i"
    keys.floatTarget("$prefix.spring", { player().spring }) { player().spring = it }
    keys.floatTarget("$prefix.links", { player().constraintStrength }) { player().constraintStrength = it }
    keys.floatTarget("$prefix.gravity", { player().gravity }) { player().gravity = it }
    keys.floatTarget("$prefix.wind", { player().wind }) { player().wind = it }
    keys.floatTarget("$prefix.damping", { player().dampingRate }) { player().dampingRate = it }
    keys.floatTarget("$prefix.textScale", { player().textScale }) { player().textScale = it }
    keys.floatTarget("$prefix.strokes", { player().strokeWidthScale }) { player().strokeWidthScale = it }
    keys.floatTarget("$prefix.dots", { player().dotRadiusScale }) { player().dotRadiusScale = it }
    keys.booleanTarget("$prefix.showDots", { player().showDots }) { player().showDots = it }
    for (j in player().diagram.textFrames.indices) {
        fun frost() = player().diagram.textFrames[j].frost
        keys.booleanTarget("$prefix.frame.$j.enabled", { frost().enabled }) { frost().enabled = it }
        keys.floatTarget("$prefix.frame.$j.opacity", { frost().opacity }) { frost().opacity = it }
        keys.floatTarget("$prefix.frame.$j.blur", { frost().blurPixels }) { frost().blurPixels = it }
        keys.floatTarget("$prefix.frame.$j.brightness", { frost().brightness }) { frost().brightness = it }
        keys.floatTarget("$prefix.frame.$j.black", { frost().blackLevel }) { frost().blackLevel = it }
        fun frame() = player().diagram.textFrames[j]
        keys.floatTarget("$prefix.frame.$j.standoff", { frame().standoff }) { frame().standoff = it }
        keys.floatTarget("$prefix.frame.$j.overstrokeFraction", { frame().overstrokeFraction }) { frame().overstrokeFraction = it }
        keys.floatTarget("$prefix.frame.$j.overstrokeDistance", { frame().overstrokeDistance }) { frame().overstrokeDistance = it }
    }
    for (j in player().diagram.textFrusta.indices) {
        fun holder() = player().diagram.textFrusta[j]
        val lane = "$prefix.frustum.$j.aspect"
        keys.floatTarget(lane, { holder().aspectRatio ?: 0f }) { holder().aspectRatio = it.takeIf { value -> value > 0f } }
        // Zero means automatic fit, not a very narrow rectangle: step when either end is automatic.
        keys.interpolators[lane] = { a, b, t ->
            val x = (a as Number).toFloat()
            val y = (b as Number).toFloat()
            if (x == 0f || y == 0f) { if (t < 1.0) x else y } else x + (y - x) * t.toFloat()
        }
    }
}

// Include shader-only colour controls too. Visible controls above own their lanes and UI mirrors;
// only unrepresented vector components are registered here, avoiding two setters for one field.
val vectors = linkedMapOf<String, () -> Vector4f>(
    "selection" to { grade().selection },
    "reconstruction" to { grade().reconstruction },
    "selectorRange" to { grade().selectorRange },
    "selectorSource" to { grade().selectorSource },
    "paint" to { grade().paint },
    "colorFold" to { grade().colorFold },
    "foldLimits" to { grade().foldLimits },
    "alphaFold" to { grade().alphaFold },
    "saturation" to { grade().saturation },
    "gain" to { grade().gain },
    "gamma" to { grade().gamma },
    "toneRange" to { grade().toneRange },
    "fxaa" to { grade().fxaa },
    "subpixel" to { grade().subpixel },
    "supersampling" to { grade().supersampling },
)
val visibleComponents = setOf(
    "selection.x", "selection.y", "selection.z", "selection.w",
    "reconstruction.x", "reconstruction.z", "selectorSource.x", "selectorSource.y", "selectorSource.z",
    "saturation.x", "gamma.w", "fxaa.x", "supersampling.w",
)
for ((name, vector) in vectors) for ((axis, suffix) in listOf("x", "y", "z", "w").withIndex()) {
    if ("$name.$suffix" !in visibleComponents) keys.floatTarget("compositor.$name.$suffix",
        { vector().get(axis) }, { value ->
            when (axis) {
                0 -> vector().x = value
                1 -> vector().y = value
                2 -> vector().z = value
                3 -> vector().w = value
            }
        })
}
keys.floatTarget("drawing.distance", { drawing.distance }) { drawing.distance = it }
keys.floatTarget("drawing.minimumStrokePixels", { drawing.minimumStrokePixels }) { drawing.minimumStrokePixels = it }
keys.floatTarget("drawing.minimumPointPixels", { drawing.minimumPointPixels }) { drawing.minimumPointPixels = it }

// Cache dimensions are staged, discrete choices. Morphing never reallocates a GPU image;
// recall restores these controls, and Apply text blur quality performs the rebuild explicitly.
keys.interpolators["drawing.cache-width-pixels"] = { a, b, t -> if (t < 1.0) a else b }
keys.interpolators["drawing.cache-height-pixels"] = { a, b, t -> if (t < 1.0) a else b }
keys.interpolators["drawing.broad-level-minimum-pixels"] = { a, b, t -> if (t < 1.0) a else b }
keys.interpolators["drawing.levels-per-size-halving"] = { a, b, t -> if (t < 1.0) a else b }
keys.interpolators["drawing.maximum-taps-per-axis"] = { a, b, t -> if (t < 1.0) a else b }
keys.interpolators["drawing.gaussian-support-sigma"] = { a, b, t -> if (t < 1.0) a else b }

val keyDirectory = "global-keys".path
Files.createDirectories(keyDirectory)
val startingLook = keyDirectory.resolve("starting-look")
if (!Files.exists(startingLook)) keys.saveKey(startingLook, keys.capture(), "Live look before enabling global keyframes")
controlGroup("global looks", restoreValues = false) {
    presetBar(keys, directory = keyDirectory)
}
keyframeBrowser(keyDirectory, channel = "saved global looks", scope = keys, width = 620, height = 420)
display("keyframe guide", "<div style='padding:12px;width:560px'>Store saves every global control, including colour, blur, frosting, physics, body interaction and navigation. Recall restores a named look and its sliders. Preset morph blends saved looks in name order. Cache quality is staged; use Apply text blur quality to rebuild. Score time, node positions and headset pose are independent. See guide 16.</div>")
