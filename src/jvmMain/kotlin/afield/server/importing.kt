package afield.server

import afield.Box
import afield.Handled
import afield.Message
import afield.RunsAt
import afield.SavingContext
import afield.handlers
import afield.installHandlers
import afield.thisSessionInfo
import afield.ImportMetadata
import afield.doResync
import afield.notify
import afield.logger.lprintln
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
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
}
