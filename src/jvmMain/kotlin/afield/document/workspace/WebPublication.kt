package afield.document.workspace

import afield.app.protocol.ExportDocumentResult
import afield.app.protocol.ExportWebDocumentResult
import afield.app.protocol.Set_output
import afield.app.server.SessionInformation
import afield.document.persistence.SavingContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File
import afield.app.protocol.NativePublicationImage
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.util.openWindowsSnapshot
import java.util.Base64
import javax.imageio.ImageIO
import afield.tools.docs.raiseAndScreenshot

/** Resolve only this document's already running renderers, without creating a scripting engine. */
internal fun nativePublicationTargets(info: SessionInformation): List<Pair<String, Window>> {
    val open = openWindowsSnapshot().toSet()
    return info.boxes.values.flatMap { box ->
        (box.properties.values.toList() + box.liveKi?.context?.vars?.values.orEmpty().map { it.value })
            .mapNotNull { value -> when (value) {
                is Window -> value
                is Renderer -> value.window
                else -> null
            } }.filter { it in open }.map { box.id to it }
    }.distinctBy { it.second }
}

/** Uses the same native-window screenshot operation as docs/backdoor captures. Runs off Runloop. */
internal suspend fun captureNativePublication(source: ExportWebDocumentResult, targets: List<Pair<String, Window>>): ExportWebDocumentResult {
    val images = mutableListOf<NativePublicationImage>()
    val warnings = source.warnings.toMutableList()
    targets.forEach { (uid, window) ->
        val file = kotlin.io.path.createTempFile("field-publication-", ".png").toFile()
        try {
            window.raiseAndScreenshot(3, file.absolutePath, captureTimeoutMs = 10000)
            require(file.length() <= 32 * 1024 * 1024) { "Native image exceeds 32 MB" }
            val image = ImageIO.read(file) ?: error("Native capture produced no image")
            images.add(NativePublicationImage(uid, "data:image/png;base64," + Base64.getEncoder().encodeToString(file.readBytes()), image.width, image.height))
        } catch (error: Exception) {
            warnings.add("Native window for $uid could not be captured: ${error.message}")
        } finally { file.delete() }
    }
    return source.copy(nativeImages = images, warnings = warnings)
}

/** Enumerate canonical assets so a publication includes Monaco's lazy modules and local fonts. */
internal fun webPublicationAssets(): List<String> {
    val common = File("src/commonMain/resources")
    val js = File("src/jsMain/resources")
    val paths = mutableListOf("offline.html", "offline-viewer.js", "offline-outputs.js", "edn-parser.js",
        "iosevka.css", "outputareas.css", "plotly-2.24.1.min.js")
    paths.addAll(listOf("threejs_viewer.html", "threejs_tools.js", "threejs_camera_link.js", "threejs_shot.js",
        "threejs_export.js", "geometry_review_inbox.js", "geometry_review_controls.js"))
    listOf("regular", "light", "lightitalic", "bold", "italic", "semicondensedlight", "semicondensedlightitalic")
        .forEach { paths.add("woff2/iosevka-$it.woff2") }
    listOf(File(js, "monaco/min") to "min", File(common, "vendor/three") to "vendor/three").forEach { (dir, prefix) ->
        require(dir.isDirectory) { "Missing publication assets: $dir" }
        dir.walkTopDown().filter { it.isFile }.forEach { file ->
            paths.add("$prefix/${file.relativeTo(dir).invariantSeparatorsPath}")
        }
    }
    paths.forEach { path ->
        require(File(common, path).isFile || File(js, path).isFile || File(js, "monaco/$path").isFile) {
            "Missing publication asset: $path"
        }
    }
    return paths.sorted()
}

/** Reads current state only: publication never evaluates a box or changes the saved document. */
internal fun exportWebDocument(info: SessionInformation): ExportWebDocumentResult {
    val boxes = info.boxes.values.toMutableSet().also { it.add(info.root) }
    val outputs = boxes.flatMap { box ->
        box.output.retainedAreas.snapshot(box.id).filter { it.name == "set-output" }.map {
            Json.decodeFromJsonElement<Set_output>(it.payload!!)
        }
    }
    return ExportWebDocumentResult(
        ExportDocumentResult(SavingContext().exportEdnPackage(boxes, info.filename),
            File(info.filename).nameWithoutExtension.ifBlank { "document" }),
        outputs, webPublicationAssets()
    )
}
