package afield

import afield.logger.lprintln
import afield.stdlib.begin

/**
 * Playlist functionality for Field4
 *
 * This module provides helpers for executing boxes in a defined order based on their `playlist` property.
 * Boxes with a `playlist` property (a Double) will be executed in order from lowest to highest value.
 * Between each begin() call, frame() is called to allow the animation system to update.
 * If a box has a `pauseList` property, that many frame() calls will be made instead of the default 1.
 */

/**
 * Extension property to get/set the playlist order for a box
 */
var Box.playlist by afield.stdlib.Delegation<Double>()

/**
 * Extension property to get/set the number of frame() calls to make after begin() for a box
 * Default is 1 frame if not set
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
 * Executes all boxes with playlist property in order
 * Sorts boxes by their playlist value (low to high) and calls begin() on each
 * Waits for the specified number of frames (pauseList or 1) between each begin()
 * 
 * This returns a Frames object that manages the execution
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
                    var framesToWait = pauseValue?.toInt() ?: 1
                    framesToWait = Math.max(200, framesToWait)

                    // Wait for the specified number of frames
                    lprintln("playlist,execution", "-- `${box.name}` waiting")
                    kotlinx.coroutines.delay(2000)
                    lprintln("playlist,execution", "-- `${box.name}` waiting phase 2")
                    repeat(framesToWait) {
                        frame()
                    }
                    kotlinx.coroutines.delay(2000)
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
