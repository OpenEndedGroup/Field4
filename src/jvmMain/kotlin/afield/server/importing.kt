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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@RunsAt(installHandlers)
fun importing() {
    handlers["import-metadata-edn"] = mutableSetOf({ message, reply ->
        
        val importData = Json.decodeFromJsonElement<ImportMetadata>(message.payload!!)
        val si = thisSessionInfo.get()
        
        // Create a map to track ID remapping (similar to cloneInto)
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
        boxes.forEach { box ->
            box.resyncAll()
        }
        
        si.topologyGeneration++
        
        Handled.yes
    })
}
