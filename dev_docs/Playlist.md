# Playlist Feature

## Overview

The playlist feature allows you to execute boxes in a defined order based on their `playlist` property. This is useful for creating sequential animations, initialization sequences, or any scenario where you need boxes to execute in a specific order with controlled timing between executions.

## Properties

### `playlist` (Double)

A numeric property that defines the execution order of a box. Boxes are executed in ascending order (lowest to highest value).

- **Type**: Double
- **Persistent**: Yes (saved with the box)
- **Optional**: Yes (only boxes with this property set will be included in playlist execution)

**Example:**
```kotlin
box1.playlist = 1.0
box2.playlist = 2.0
box3.playlist = 3.0
```

### `pauseList` (Double)

A numeric property that specifies how many frame() calls to make after calling `begin()` on the box. If not set, defaults to 1 frame.

- **Type**: Double
- **Persistent**: Yes (saved with the box)
- **Optional**: Yes (defaults to 1 if not set)
- **Converted to**: Int (rounded down)

**Example:**
```kotlin
box1.pauseList = 2.0  // Wait 2 frames after this box
box2.pauseList = 5.0  // Wait 5 frames after this box
```

## Functions

### `Box.findBoxesWithPlaylist(): List<Box>`

Recursively finds all boxes (starting from the current box and traversing the hierarchy) that have the `playlist` property set.

**Returns**: List of boxes with the playlist property set

**Example:**
```kotlin
val playlistBoxes = root.findBoxesWithPlaylist()
playlistBoxes.forEach { box ->
    println("${box.name} has playlist order ${box.playlist}")
}
```

### `Box.executePlaylist(strand: String = "default"): Frames`

Executes all boxes with the `playlist` property in order (sorted by playlist value from low to high). For each box:
1. Calls `begin(strand)` on the box
2. Waits for the number of frames specified by `pauseList` (or 1 if not set)
3. Moves to the next box

**Parameters:**
- `strand`: The strand name to use for execution (default: "default")

**Returns**: Frames object that manages the execution (can be used to control or stop the execution)

**Example:**
```kotlin
val frames = root.executePlaylist("myStrand")
// The returned Frames object can be used to control execution
```

### `Box.runPlaylist(strand: String = "default"): Frames`

Convenience function that executes the playlist starting from the root box. Can be called from any box in the hierarchy.

**Parameters:**
- `strand`: The strand name to use for execution (default: "default")

**Returns**: Frames object that manages the execution

**Example:**
```kotlin
// Can be called from any box
runPlaylist()
```

## Usage Examples

### Basic Sequential Execution

```kotlin
// Create boxes with playlist order
val intro = box("intro")
intro.playlist = 1.0
intro.code = """
    println("Starting animation")
    // Initialization code
"""

val main = box("main")
main.playlist = 2.0
main.code = """
    println("Main animation")
    // Main animation code
"""

val outro = box("outro")
outro.playlist = 3.0
outro.code = """
    println("Ending animation")
    // Cleanup code
"""

// Execute all boxes in order
root.runPlaylist()
```

### With Custom Delays

```kotlin
val box1 = box("box1")
box1.playlist = 1.0
box1.pauseList = 1.0  // Wait 1 frame (default)
box1.code = """
    println("Quick execution")
"""

val box2 = box("box2")
box2.playlist = 2.0
box2.pauseList = 5.0  // Wait 5 frames
box2.code = """
    println("Need more time to settle")
"""

val box3 = box("box3")
box3.playlist = 3.0
box3.pauseList = 10.0  // Wait 10 frames
box3.code = """
    println("Heavy computation")
"""

root.runPlaylist()
```

### Finding and Inspecting Playlist Boxes

```kotlin
// Find all boxes with playlist property
val playlistBoxes = root.findBoxesWithPlaylist()

// Print information about each box
playlistBoxes.sortedBy { it.playlist }.forEach { box ->
    val pause = (box.getPropertyRaw("pauseList") as? Number)?.toInt() ?: 1
    println("${box.name}: order=${box.playlist}, pause=${pause} frames")
}

// Count boxes in playlist
println("Total boxes in playlist: ${playlistBoxes.size}")
```

### Conditional Execution

```kotlin
// Only execute if there are boxes in the playlist
if (root.findBoxesWithPlaylist().isNotEmpty()) {
    println("Starting playlist execution")
    root.runPlaylist()
} else {
    println("No boxes in playlist")
}
```

## Implementation Details

- The `playlist` and `pauseList` properties are persisted (saved with the box)
- Both properties are added to the `ioPersistentProperties` list
- The execution uses the `Frames` coroutine pattern for proper async execution
- Deleted boxes (`_tombstone` property set) are automatically excluded
- The search is performed recursively through the box hierarchy
- Frame delays use the `frame()` coroutine suspend function to properly yield control

## Pattern in Previous Versions of Field

This feature recreates a common pattern from earlier versions of Field where:
1. Boxes stored a persistent property (typically called `playlist`) as a double
2. A helper would find all boxes with that property set
3. Sort them from low to high
4. Call `begin()` on them in order
5. Insert `frame()` calls between each `begin()` (or use `pauseList` for custom delays)

This pattern is useful for:
- Startup sequences
- Staged initialization
- Sequential animations
- Ordered execution of dependent operations
