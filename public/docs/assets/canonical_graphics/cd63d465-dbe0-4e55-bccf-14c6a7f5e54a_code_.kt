/*
A GPU radix sort. Run this in a box beneath the renderer (`window`) box; it draws nothing.

`GpuRadixSort` is a packaged set of compute passes. The lesson is how such work is scheduled: it is
recorded into the renderer's command buffer for a frame, not executed when this code runs.
*/

val sorter = GpuRadixSort()

/*
The buffer builder states every use the buffer will have, and those flags decide which accesses are
legal later: `storage()` for shader reads and writes, `addressable()` for buffer references,
`hosted()` so the CPU can map it, and `dest()` so it can be a transfer destination.
*/

var sourceBuffer = buffer {
    size = 200.ints
    storage()
    addressable()
    hosted()
    dest()
}
    .buffer

// 200 keys in descending order: 2000, 1999, ... 1801
sourceBuffer.write {
    200 {
        putInt(2000 - it)
    }
}

/*
`renderList.others` is a slot on the frame that hands you the command buffer to record into.
`sorter.sort(command, ...)` records the sort there; it runs on the GPU when that frame is submitted,
and, because the target stays installed, it runs again every frame.
*/
renderer.renderList.others["sort"] = Target { command, index ->
    sorter.sort(command, sourceBuffer, 200)
}

/*
The sort runs when a frame is submitted, so wait a couple of frames, then read the first key back through
the host-visible memory and print it in the editor: 1801 once sorted (2000 before). `frames` runs only
because it is the last expression in the box.
*/
frames {
    frame()
    frame()
    sourceBuffer.read_ints {
        _print(this.get(0))
    }
}
