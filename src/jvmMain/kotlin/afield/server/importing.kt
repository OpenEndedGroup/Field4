package afield.server

import afield.Box
import afield.Handled
import afield.Message
import afield.Rect
import afield.RunsAt
import afield.SavingContext
import afield.SessionInformation
import afield.box
import afield.handlers
import afield.installHandlers
import afield.needsResync
import afield.thisSessionInfo
import afield.ImportMetadata
import afield.ImportMetadataNested
import afield.ImportNotebook
import afield.doResync
import afield.nestInto
import afield.notify
import afield.logger.lprintln
import afield.python.isPythonRoot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Handler for importing metadata.edn files from the frontend.
 *
 * This allows users to drag and drop metadata.edn files into the web UI,
 * which will then be added to the current document with all box IDs remapped
 * to avoid conflicts (similar to copy/paste behavior).
 *
 * The import process:
 * 1. Frontend reads the dropped metadata.edn file
 * 2. Sends the EDN text content to this handler via "import-metadata-edn" message
 * 3. Backend parses the EDN and creates new boxes with fresh UUIDs
 * 4. Boxes are added to the current session and synced to all clients
 */
@RunsAt(installHandlers)
fun importing() {
    handlers["import-metadata-edn"] = mutableSetOf({ message, reply ->
        try {
            val importData = Json.decodeFromJsonElement<ImportMetadata>(message.payload!!)
            val si = thisSessionInfo.get()

            // Create a map to track ID remapping (similar to paste-boxes)
            // This ensures all imported boxes get fresh IDs to avoid conflicts
            @OptIn(ExperimentalUuidApi::class)
            val seen = mutableMapOf<String, String>("root" to "root")

            // Load the boxes from the EDN text with ID remapping
            @OptIn(ExperimentalUuidApi::class)
            val boxes = SavingContext().loadFromEdnText(
                importData.ednText,
                si.filename,
                { id ->
                    val next = seen.computeIfAbsent(id) { Uuid.random().toString() }
                    next
                }
            )

            // Add the imported boxes to the current session
            // resyncAll() ensures they appear in all connected clients
            boxes.forEach { box ->
                if (box.id != "root") {
                    box.resyncAll()
                }
            }

            // Increment topology generation and sync to trigger connection graph rebuild
            si.topologyGeneration++
            doResync()

            // Notify user of successful import
            notify("Imported ${boxes.size} box${if (boxes.size == 1) "" else "es"} from EDN file", "import")

            Handled.yes
        } catch (e: Exception) {
            lprintln("error,import", "Failed to import metadata.edn: ${e.message}")
            e.printStackTrace()
            notify("Failed to import metadata.edn: ${e.message}", "error")
            Handled.yes
        }
    })

    handlers["import-metadata-edn-nested"] = mutableSetOf({ message, reply ->
        try {
            val request = Json.decodeFromJsonElement<ImportMetadataNested>(message.payload!!)
            val created = importMetadataAsNest(
                ednText = request.ednText,
                anchorX = request.x,
                anchorY = request.y,
                nestName = request.nestName,
            )
            thisSessionInfo.get().topologyGeneration++
            doResync()
            val nestedCount = created.size - 1
            notify(
                "Imported $nestedCount box${if (nestedCount == 1) "" else "es"} into a new nest box",
                "import",
            )
            Handled.yes
        } catch (e: Exception) {
            lprintln("error,import", "Failed to import metadata.edn as nest: ${e.message}")
            e.printStackTrace()
            notify("Failed to import metadata.edn: ${e.message}", "error")
            Handled.yes
        }
    })

    handlers["import-ipynb"] = mutableSetOf({ message, reply ->
        try {
            val request = Json.decodeFromJsonElement<ImportNotebook>(message.payload!!)
            val created = importNotebookCells(
                notebookJson = request.notebookJson,
                fileName = request.fileName,
                anchorX = request.x,
                anchorY = request.y,
            )
            thisSessionInfo.get().topologyGeneration++
            doResync()

            val cellCount = created.size - 1
            notify(
                "Imported $cellCount cell${if (cellCount == 1) "" else "s"} from ${displayNotebookName(request.fileName)}",
                "import",
            )
            Handled.yes
        } catch (e: Exception) {
            lprintln("error,import", "Failed to import notebook: ${e.message}")
            e.printStackTrace()
            notify("Failed to import notebook: ${e.message}", "error")
            Handled.yes
        }
    })
}

// --- metadata.edn -> nested subtree import ----------------------------------
//
// Loads a dropped metadata.edn with fresh UUIDs (like the flat import-metadata-edn
// path) and then wraps the top-level imported boxes inside one new nest-parent box
// (afield.nested / nestedIn), so they land as a single collapsible/zoomable group
// rather than scattered across the document root.

private const val NEST_IMPORT_DEFAULT_NAME = "imported boxes"
private const val NEST_IMPORT_HEADER_HEIGHT = 40.0
private const val NEST_IMPORT_MIN_WIDTH = 260.0

/**
 * Parse [ednText], remap its ids to fresh UUIDs, and nest the top-level imported
 * boxes inside a new nest-parent box anchored at document-space ([anchorX],
 * [anchorY]). Returns the created boxes with the nest parent first.
 */
@OptIn(ExperimentalUuidApi::class)
fun importMetadataAsNest(
    ednText: String,
    anchorX: Double,
    anchorY: Double,
    nestName: String = "",
    info: SessionInformation = thisSessionInfo.get(),
): List<Box> {
    val seen = mutableMapOf("root" to "root")
    val boxes = SavingContext().loadFromEdnText(
        ednText,
        info.filename,
        { id -> seen.computeIfAbsent(id) { Uuid.random().toString() } }
    )
    val imported = boxes.filter { it.id != "root" }
    val importedSet = imported.toSet()

    // Nest only the top-level boxes of the imported subtree: those with no parent
    // inside the imported set (e.g. children of the document root). Their imported
    // descendants ride along via the children edges nestInto preserves.
    val topLevel = imported.filter { box -> box.parents.none { it in importedSet } }
        .ifEmpty { imported }

    // The nested canvas pans/zooms, so this is only the parent's initial footprint.
    val width = (topLevel.maxOfOrNull { it.frame.w } ?: NEST_IMPORT_MIN_WIDTH)
        .coerceAtLeast(NEST_IMPORT_MIN_WIDTH)
    val nestParent = box(Uuid.random().toString(), createIfMissing = true, initIfCreated = true, root = info)
    nestParent.name = nestName.ifBlank { NEST_IMPORT_DEFAULT_NAME }
    nestParent.frame = Rect(anchorX, anchorY, width, NEST_IMPORT_HEADER_HEIGHT)

    // nestInto sets nested/nestedIn (converting each frame to parent-relative). Like the
    // interactive "-nest-into" action it leaves the boxes' document-root edge in place —
    // the client relocates a box into the nest canvas by its nestedIn, not by re-parenting.
    topLevel.forEach { child -> child.nestInto(nestParent) }

    nestParent.resyncAll()
    imported.forEach { it.resyncAll() }
    needsResync.add(info.root to "children")
    needsResync.add(nestParent to "nested")
    topLevel.forEach { needsResync.add(it to "nestedIn") }

    return listOf(nestParent) + imported
}

// --- Jupyter notebook (.ipynb) import ---------------------------------------
//
// A dropped notebook becomes a vertical column of boxes. A fresh python-root box
// (afield.python.isPythonRoot = true) is created as the parent; the topology
// watcher then turns each of its direct children into Python cells (see
// PythonInterface.applyRuntimeToggle). Code cells are wired as children of that
// python root; markdown/raw cells are left as ordinary document-root boxes with
// their text in the `notes` property, indented within the column so they read as
// prose interleaving the code. Cell boxes are deliberately left unnamed so the AI
// auto-namer (Ai4AutoBoxNamer) can title them from their content.

private const val PYTHON_ROOT_BOX_NAME = "python root"
private const val NOTEBOOK_COLUMN_WIDTH = 380.0
private const val NOTEBOOK_MARKDOWN_INDENT = 48.0
private const val NOTEBOOK_VERTICAL_GAP = 22.0
private const val NOTEBOOK_ROOT_HEIGHT = 56.0
private const val NOTEBOOK_LINE_HEIGHT = 15.0
private const val NOTEBOOK_CODE_PADDING = 46.0
private const val NOTEBOOK_MARKDOWN_PADDING = 40.0

/**
 * Parse [notebookJson] and materialize its cells as boxes in [info]. Returns the
 * created boxes with the python-root parent first. Pure with respect to client
 * sync (callers invoke [doResync]); safe to call from tests without live sessions.
 */
@OptIn(ExperimentalUuidApi::class)
fun importNotebookCells(
    notebookJson: String,
    fileName: String,
    anchorX: Double,
    anchorY: Double,
    info: SessionInformation = thisSessionInfo.get(),
): List<Box> {
    val notebook = Json.parseToJsonElement(notebookJson).jsonObject
    val cells = notebookCells(notebook)

    // The python-root parent: a marker/container box. It gets a fixed, descriptive
    // name ("python root") — a deliberate manual name so the AI namer leaves it
    // alone — and spans the full column width so it visually frames the cells below.
    val pythonRoot = box(Uuid.random().toString(), createIfMissing = true, initIfCreated = true, root = info)
    pythonRoot.isPythonRoot = true
    pythonRoot.name = PYTHON_ROOT_BOX_NAME
    pythonRoot.frame = Rect(
        anchorX,
        anchorY,
        NOTEBOOK_COLUMN_WIDTH + NOTEBOOK_MARKDOWN_INDENT,
        NOTEBOOK_ROOT_HEIGHT,
    )

    val created = mutableListOf(pythonRoot)
    var cursorY = anchorY + NOTEBOOK_ROOT_HEIGHT + NOTEBOOK_VERTICAL_GAP

    for (cell in cells) {
        val source = cellSource(cell)
        if (source.isBlank()) continue
        val type = (cell["cell_type"] as? JsonPrimitive)?.contentOrNull ?: "code"

        val cellBox = box(Uuid.random().toString(), createIfMissing = true, initIfCreated = true, root = info)
        // Leave `name` empty on purpose — AI auto-naming titles these from content.

        if (type == "code") {
            cellBox.setPropertyRaw("code", source, doResync = false)
            // Reparent under the python root: connect first, then drop the default
            // document-root edge so the root's own toggle pass can't flip the cell
            // back to Kotlin (applyRuntimeToggle detaches Python from non-root children).
            pythonRoot.connect(cellBox)
            info.root.disconnect(cellBox)
            val height = cellHeight(source, NOTEBOOK_CODE_PADDING, maxHeight = 560.0)
            cellBox.frame = Rect(anchorX, cursorY, NOTEBOOK_COLUMN_WIDTH, height)
            cursorY += height + NOTEBOOK_VERTICAL_GAP
        } else {
            // markdown / raw — prose. Kept as an ordinary document-root box (NOT a
            // child of the python root, so it never becomes an executable cell),
            // indented within the column. Content lives in `notes` (rendered as
            // Markdown), which we make the box's default editing surface.
            cellBox.setPropertyRaw("notes", source, doResync = false)
            cellBox.setPropertyRaw("default_property", "notes", doResync = false)
            val height = cellHeight(source, NOTEBOOK_MARKDOWN_PADDING, maxHeight = 400.0)
            cellBox.frame = Rect(
                anchorX + NOTEBOOK_MARKDOWN_INDENT,
                cursorY,
                NOTEBOOK_COLUMN_WIDTH - NOTEBOOK_MARKDOWN_INDENT,
                height,
            )
            cursorY += height + NOTEBOOK_VERTICAL_GAP
        }

        created += cellBox
    }

    // Sync the new topology to any connected clients.
    created.forEach { it.resyncAll() }
    needsResync.add(info.root to "children")
    needsResync.add(pythonRoot to "children")
    needsResync.add(pythonRoot to "parents")
    needsResync.add(info.root to "actions")
    created.forEach { needsResync.add(it to "parents") }

    return created
}

/** Extract the cell list, tolerating nbformat 4 (`cells`) and nbformat 3 (`worksheets[].cells`). */
private fun notebookCells(notebook: JsonObject): List<JsonObject> {
    (notebook["cells"] as? JsonArray)?.let { arr ->
        return arr.mapNotNull { it as? JsonObject }
    }
    (notebook["worksheets"] as? JsonArray)?.let { sheets ->
        return sheets.mapNotNull { it as? JsonObject }
            .flatMap { ws -> (ws["cells"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList() }
    }
    return emptyList()
}

/** A cell's `source` is either a JSON array of line strings or a single string. `input` is the nbformat-3 key. */
private fun cellSource(cell: JsonObject): String {
    val src = cell["source"] ?: cell["input"] ?: return ""
    return when (src) {
        is JsonArray -> src.joinToString("") { (it as? JsonPrimitive)?.contentOrNull ?: "" }
        is JsonPrimitive -> src.contentOrNull ?: ""
        else -> ""
    }
}

private fun cellHeight(source: String, padding: Double, maxHeight: Double): Double {
    val lines = source.count { it == '\n' } + 1
    return (lines * NOTEBOOK_LINE_HEIGHT + padding).coerceIn(90.0, maxHeight)
}

/** Notebook filename without directory or the `.ipynb` extension, e.g. `analysis`. */
private fun displayNotebookName(fileName: String): String =
    fileName.substringAfterLast('/').substringAfterLast('\\').removeSuffix(".ipynb").ifBlank { "notebook" }
