# Syphon: sharing frames with other applications

[Syphon](https://syphon.info) is how video applications on a Mac hand frames to each other without
encoding them: Resolume, TouchDesigner, MadMapper, VDMX, Max, OBS (through its Syphon source) and
many more publish and receive it. Field joins in through `afield.syphon`: a box can publish
pictures for any of those to receive, and can receive what they publish.

Syphon is macOS-only, and Field reaches it through a small Objective-C library that is not built by
default. Build it once:

```diffstatic {data-lang=bash}
./gradlew buildSyphonNative
```

That needs Xcode, and the network the first time (it fetches the Syphon framework's source at a
pinned revision). Every call below says so, and stops, if the library is missing.

## Publishing

`syphonServer(name)` makes a server that every Syphon client on the machine lists, under Field's
application name and the name you give it. Publish to it as often as you like; each `publish` is one
frame:

```diffstatic {data-lang=kotlin}
val out = syphonServer("Field pattern")
val img = BufferedImage(640, 360, BufferedImage.TYPE_INT_ARGB)

loop {
    val g = img.createGraphics()
    // ... draw ...
    g.dispose()
    out.publish(img)
}
```

`publish` accepts a `BufferedImage`, ARGB ints, raw RGBA (or BGRA) bytes, or a `RenderToTexture`.
Calling `syphonServer` again with the same name returns the server that is already open, so
re-running the box keeps publishing to the same source rather than adding another. `out.close()`
retires it; clients see it disappear.

### From the GPU

To publish what a Vulkan render target draws, hand it the target:

```diffstatic {data-lang=kotlin}
val out = syphonServer("Field render")
out.follow(target)          // every frame target renders, until out.stopFollowing()
```

`follow` reads each frame back from the GPU once its work has finished and passes it to Syphon, one
frame in flight at a time, so a slow receiver drops frames instead of queueing them. The target
needs 8-bit colour, which the default `B8G8R8A8_SRGB` is. `out.publish(target)` sends just the next
frame.

## Receiving

`syphonClient(match)` connects to the first server whose name, application name or uuid is `match`,
waiting up to two seconds for it to appear. `latest()` is its most recent frame, `png()` the same
frame ready for `display`:

```diffstatic {data-lang=kotlin}
val inp = syphonClient("Resolume Arena")

loop {
    inp.png()?.let { display("main", it) }
    delay(250)
}
```

`latest()` returns a `SyphonFrame` with the RGBA bytes, `rgbaAt(x, y)`, `argb()` and `image()`.
`Syphon.servers()` lists everything announced on the machine, Field's own servers included.

## What it costs

Today every frame passes through memory on its way across. Publishing a `RenderToTexture` reads it
back from the GPU, and Syphon then copies those bytes into the IOSurface it shares with clients;
receiving copies the client's surface back into a byte array. At 1920 x 1080 that is about 8 MB per
frame in each direction, which is fine at 30 or 60 frames a second on Apple silicon but is not free.
`out.publishIOSurface(ref)` publishes an IOSurface you already have without touching the CPU, which
is the route a zero-copy path from Field's renderer will take.

## Try it

The `syphon_bridge` starting point has three boxes: one lists the servers on the machine, one
publishes an animated picture as "Field pattern", and one receives "Field pattern" back through
Syphon and shows it — so the whole round trip is visible with nothing else installed. Change the
name in the third box to receive another application.

??? aside "How this was checked"
    `SyphonTest` runs both directions against `field_syphon_probe`, a separate process built next to
    the library: Field publishes a four-quadrant pattern and the probe asserts five exact pixel
    values, then the probe publishes it and Field asserts them. Orientation is part of what it pins:
    row 0 is the top in both directions. Syphon's discovery rides on macOS distributed
    notifications, which arrive on the main run loop; Field's main thread runs Field's own loop, so
    `afield.syphon` turns the run loop once per Field frame. Without that, Field's servers were seen
    only by clients that were already listening when they started.
