package afield.examples

/**
 * Example usage of the playlist functionality
 * 
 * This demonstrates how to:
 * 1. Set playlist property on boxes
 * 2. Set pauseList property to control frame delays
 * 3. Execute boxes in playlist order
 */

/*
Example usage in Field4:

// Create some boxes and set their playlist order
val box1 = box("box1")
box1.playlist = 1.0
box1.code = """
    println("Box 1 executing")
"""

val box2 = box("box2")
box2.playlist = 2.0
box2.pauseList = 2.0  // Wait 2 frames instead of 1
box2.code = """
    println("Box 2 executing")
"""

val box3 = box("box3")
box3.playlist = 3.0
box3.code = """
    println("Box 3 executing")
"""

// Execute all boxes in playlist order
root.runPlaylist()

// This will:
// 1. Execute box1.begin()
// 2. Wait 1 frame (default)
// 3. Execute box2.begin()
// 4. Wait 2 frames (pauseList = 2.0)
// 5. Execute box3.begin()
// 6. Wait 1 frame (default)

// You can also find boxes with playlist property:
val playlistBoxes = root.findBoxesWithPlaylist()
playlistBoxes.forEach { box ->
    println("${box.name} has playlist=${box.playlist}")
}
*/
