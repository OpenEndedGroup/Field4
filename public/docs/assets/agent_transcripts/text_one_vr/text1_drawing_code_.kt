import afield.graphics.scene.reading.*

// Staged cache quality. Apply changes at an acquired frame, without replacing the renderer or XR session.
rootProp<Int>(textCacheWidth, 2048)
rootProp<Int>(textCacheHeight, 1024)
rootProp<Int>(textCacheMinimum, 512)
rootProp<Int>(textCacheHalving, 2)
rootProp<Int>(textCacheTaps, 513)
rootProp<Float>(textCacheCutoff, 4f)
fun pendingTextCacheQuality() = ReadingTextBlurConfig(
    width = root.textCacheWidth,
    height = root.textCacheHeight,
    minimumSize = root.textCacheMinimum,
    levelsPerHalving = root.textCacheHalving,
    maximumRadius = (root.textCacheTaps - 1) / 2,
    sigmaCutoff = root.textCacheCutoff,
)

// Each shader box owns its moduleHere pipeline and its standard Alt+R action.
// The renderer supplies pass-specific targets/resources without copying shader properties.
val passes = mapOf(
    "reading-strokes" to strokeModuleFactory,
    "reading-markers" to pointModuleFactory,
    "reading-slug" to textModuleFactory,
    "reading-blur-1" to mediumHorizontalFactory,
    "reading-blur-2" to mediumVerticalFactory,
    "reading-blur-3" to broadHorizontalFactory,
    "reading-blur-4" to broadVerticalFactory,
    "reading-composite" to compositeModuleFactory,
)

prop<StereoDiagramRenderer>(drawing) =
    StereoDiagramRenderer(renderer, stereo, poem, textBlurConfig = pendingTextCacheQuality(), moduleFactories = passes)
val ownedDrawing = drawing

rootProp<Boolean>(showSkeleton, false)
rootProp<Boolean>(showPhysicsNodes, true)
rootProp<Boolean>(showBodyForces, true)

// The uniform has its own document-wide floor; panel-local black levels remain available in the DSL.
rootProp<Float>(frostBlackLevel, .012f)
rootProp<Float>(frostBlurGamma, 1f)
val frostScenePreparation = drawing.compositor.beforeScene
val frostFloorValue: () -> Float = { root.frostBlackLevel }
drawing.compositor.beforeScene =
    afield.graphics.vulkan.Target { command, index ->
        drawing.frost.blurGamma = root.frostBlurGamma
        frostScenePreparation?.render(command, index)
        drawing.compositor.sharp.modules
            .filterKeys { it.startsWith("reading-frost-") }
            .values
            .forEach { pass ->
                pass.current.liveUniformStructures.getValue("FrostTone").bind("globalBlackLevel", frostFloorValue)
            }
    }

// These controls shape already-filtered text coverage; neither changes its cache nor its timing.
rootProp<Float>(textBlurGain, 1f)
rootProp<Float>(textBlurContrast, 1f)
val textBlurToneValue: () -> Vector4f = { vec(root.textBlurGain, root.textBlurContrast, 0f, 0f) }
val prepareTextAndFrost = drawing.compositor.beforeScene
drawing.compositor.beforeScene =
    afield.graphics.vulkan.Target { command, index ->
        drawing.compositor.sharp.modules
            .filterKeys { it.startsWith("reading-local-blur-") }
            .values
            .forEach { pass ->
                pass.current.liveUniformStructures.getValue("TextBlurTone").bind("appearance", textBlurToneValue)
            }
        prepareTextAndFrost?.render(command, index)
    }

// Optional perceptual dither for downstream display quantization; zero keeps it disabled.

var lastStats = 0L
loop {
    renderer.windows.forEach { it.pollEvents() }
    renderer.render()
    val now = System.nanoTime()
    if (now - lastStats > 1_000_000_000L) {
        lastStats = now
        display(
            "frame",
            "<div style='padding:12px;width:440px'>${if (drawing.textBlurNeedsRebuild) "Blur capacity changed: rebuild Drawing. " else ""}Frost: ${drawing.frost.activeSurfaces} active / ${drawing.frost.allocatedSurfaces} cached · ${drawing.frost.allocatedBytes / (1024 * 1024)} MiB · ${drawing.frost.omittedSurfaces} over budget. Text caches: ${drawing.textBlurConfig.width}×${drawing.textBlurConfig.height}, minimum ${drawing.textBlurConfig.minimumSize}px, max ${2*drawing.textBlurConfig.maximumRadius+1} taps, ${drawing.textBlurCacheBytes/(1024*1024)} MiB. Frame ${drawing.frameCount} · ${poem.labels.size} text runs · ${poem.geometry.segments.size} stroke segments · ${poem.geometry.points.size} markers · ${poem.current.physicsSteps} fixed physics steps. One centreline upload and one text-pose update serve both eyes.</div>",
        )
    }
    frame()
}
    .end { ownedDrawing.close() }
