// Blocking diagnostics only. The Starting point and its consumer stay GPU-resident.
// Compile this function once per check box, then call it for each measurement.
// Keep large arrays local: top-level script vals otherwise survive in the REPL history.
prop<java.util.function.Consumer<String>>(capturePair) = java.util.function.Consumer { kind ->
check(flow.backend==afield.four.vision.OpticalFlowBackend.HARDWARE) { "This fixture check requires the NVIDIA backend; portable flow has separate verification" }
check(history.frameCount==2 && motion.width==240 && motion.height==135)
val expectedStart=afield.stdlib.linalg.Vector2f(expectedMotion)
val handles=listOf(previousImage.imageView.vkImageView,currentImage.imageView.vkImageView)
check(handles[0]!=handles[1])
val frameStart=afield.Runloop.runLoop.frame
val capture=Class.forName("afield.four.reconstruction.ReconstructionCapture")
val instance=capture.getField("INSTANCE").get(null)
val read=capture.getMethod("read",afield.four.Renderer::class.java,afield.four.texture.HasImageView::class.java)
val pixels=read.invoke(instance,renderer,motion) as FloatArray
val before=read.invoke(instance,renderer,previousImage) as FloatArray
val after=read.invoke(instance,renderer,currentImage) as FloatArray
val difference=before.indices.maxOf { kotlin.math.abs(before[it]-after[it]) }
val samePair=expectedStart==expectedMotion && handles==listOf(previousImage.imageView.vkImageView,currentImage.imageView.vkImageView) && frameStart==afield.Runloop.runLoop.frame
val dump=java.io.File(__OUTPUT__,"pair-${System.nanoTime()}")
dump.mkdirs()
// Retain both source images for the full-pair reproducibility investigation.
listOf("flow" to pixels,"first" to before,"second" to after).forEach { (name,data) ->
    val bytes=java.nio.ByteBuffer.allocate(data.size*4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    bytes.asFloatBuffer().put(data)
    java.io.File(dump,name+".f32").writeBytes(bytes.array())
}
val props=renderer.device.physicalDevice.vkPhysicalDeviceProperties
val api=props.apiVersion()
val meta=org.json.JSONObject().put("kind",kind).put("samePairDuringReadback",samePair)
    .put("expected",listOf(expectedStart.x,expectedStart.y)).put("imageHandles",handles).put("fieldFrame",frameStart)
    .put("width",motion.width).put("height",motion.height).put("inputWidth",history.width).put("inputHeight",history.height)
    .put("backend",flow.backend.toString()).put("sourceMaxDifference",difference)
    .put("gpu",props.deviceNameString()).put("driverVersionRaw",props.driverVersion())
    .put("vulkanApi","${api ushr 22}.${(api ushr 12) and 1023}.${api and 4095}")
    .put("glfwPlatform",org.lwjgl.glfw.GLFW.glfwGetPlatform()).put("jvm",System.getProperty("java.version"))
    .put("lwjgl",org.lwjgl.Version.getVersion()).put("validationRequested",afield.four.util.Options.validate)
java.io.File(dump,"metadata.json").writeText(meta.toString(2))
check(samePair) { "Readback straddled evaluations: $dump" }
check(before.all { it.isFinite() } && after.all { it.isFinite() }) { "Non-finite source images: $dump" }
println("PINGPONG PAIR CAPTURED $dump")
}
