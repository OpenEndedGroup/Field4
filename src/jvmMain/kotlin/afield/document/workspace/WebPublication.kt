package afield.document.workspace

import afield.app.protocol.ExportDocumentResult
import afield.app.protocol.ExportWebDocumentResult
import afield.app.protocol.Set_output
import afield.app.server.SessionInformation
import afield.document.persistence.SavingContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File

/** Enumerate canonical assets so a publication includes Monaco's lazy modules and local fonts. */
internal fun webPublicationAssets(): List<String> {
    val common = File("src/commonMain/resources")
    val js = File("src/jsMain/resources")
    val paths = mutableListOf("offline.html", "offline-viewer.js", "offline-outputs.js", "edn-parser.js",
        "iosevka.css", "outputareas.css", "plotly-2.24.1.min.js")
    listOf("regular", "light", "lightitalic", "bold", "italic", "semicondensedlight", "semicondensedlightitalic")
        .forEach { paths.add("woff2/iosevka-$it.woff2") }
    listOf(File(js, "monaco/min") to "min").forEach { (dir, prefix) ->
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
