package afield

import afield.logger.lprintln
import afield.stdlib.begin

/**
 * Playlist functionality for Field4
 *
 * This module provides helpers for executing boxes in a defined order based on their `playlist` property.
 * Boxes with a `playlist` property (a Double) will be executed in order from lowest to highest value.
 * Between each `begin()` call, [executePlaylist] waits `pauseList` `frame()` calls (default 1).
 *
 * User-facing dev doc: dev_docs/Playlist.md (a pointer stub); this KDoc is the source of truth.
 */

/**
 * Extension property to get/set the playlist order for a box
 */
var Box.playlist by afield.stdlib.Delegation<Double>()

/**
 * The number of `frame()` calls [executePlaylist] makes after `begin()` before advancing to the next
 * box. Defaults to 1 when unset; coerced to a minimum of 1.
 */
var Box.pauseList by afield.stdlib.Delegation<Double>()

/**
 * Finds all boxes (recursively) that have the `playlist` property set
 * @return List of boxes with playlist property set
 */
fun Box.findBoxesWithPlaylist(): List<Box> {
    val result = mutableListOf<Box>()
    val visited = mutableSetOf<Box>()
    
    fun visitBox(box: Box) {
        if (box in visited) return
        visited.add(box)
        
        // Check if this box has playlist property using getPropertyRaw to avoid null pointer
        val playlistValue = box.getPropertyRaw("playlist")
        if (playlistValue is Number) {
            result.add(box)
        }
        
        // Recursively visit children
        box.children.forEach { child ->
            if (!child.getPropertyRaw("_tombstone").isTrue()) {
                visitBox(child)
            }
        }
    }
    
    visitBox(this)
    return result
}

/**
 * Executes all boxes with the `playlist` property in order (sorted by `playlist` value, low to high),
 * calling `begin(strand)` on each.
 *
 * Between boxes it waits `pauseList` `frame()` calls (default 1, minimum 1) — no fixed wall-clock
 * delay and no floor.
 *
 * @param strand The strand name to use for execution (default "default")
 * @return Frames object that can be used to control the execution
 */
fun Box.executePlaylist(strand: String = "default"): Frames {
    return frames {
            val boxes = findBoxesWithPlaylist()

            // Sort by playlist value (low to high)
            // We know these boxes have the property set, so we can use the extension property
            val sorted = boxes.sortedBy { it.playlist }

            // Execute each box in order
            for (box in sorted) {

                lprintln(
                    "playlist,execution",
                    "\n\n\n\n -- `${box.name}` begins (playlist) ------------------------------------- "
                )
                try {
                    // Call begin() on the box
                    inMain {
                        box.begin(strand)
                    }

                    // Get the number of frames to wait (pauseList or default 1)
                    // Use getPropertyRaw to safely check if pauseList is set
                    val pauseValue = box.getPropertyRaw("pauseList") as? Number
                    val framesToWait = (pauseValue?.toInt() ?: 1).coerceAtLeast(1)

                    // Wait for the specified number of frames
                    lprintln("playlist,execution", "-- `${box.name}` waiting $framesToWait frame(s)")
                    repeat(framesToWait) {
                        frame()
                    }
                    lprintln("playlist,execution", "-- `${box.name}` finished waiting")
                } catch (e: Exception) {
                    lprintln("playlist,execution", "-- `${box.name}` (playlist) threw:")
                    e.printStackTrace()
                }
            }
        }
    }

/**
 * Helper function to execute playlist from root
 * This can be called from any box and will execute the playlist starting from root
 * 
 * @param strand The strand name to use for execution (default "default")
 * @return Frames object that can be used to control the execution
 */
fun Box.runPlaylist(strand: String = "default"): Frames {
    return root.executePlaylist(strand)
}
