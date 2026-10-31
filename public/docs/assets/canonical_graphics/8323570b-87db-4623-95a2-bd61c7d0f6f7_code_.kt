import afield.media.stream.FieldRTTStreamer
import org.lwjgl.vulkan.VK10.*

// 6. RTT - sending: render into a target that FieldRTTStreamer encodes and streams over the network.
// Parent: window. The streamer allocates the surface and hands back a RenderToTexture to draw into;
// here it sends 1280x720 at 15 fps to 127.0.0.1:9001. `6. RTT- draw rtt` also shows the target in
// the window. The fragment shader (open it with the editor's property switcher) colours by pixel position.

prop<FieldRTTStreamer>(streamer)
// `once` creates the streamer only on the first run, so re-running the box keeps the same stream.
once.streamer {
    FieldRTTStreamer(renderer, dest = "127.0.0.1:9001", width = 1280, height = 720, fps = 15)
}
prop<RenderToTexture>(target) = streamer.start().first()

// Render the target each frame, and attach this box's module to the target rather than the window.
renderer.renderList += "target" to target

var module = moduleHere { +target }
var m = Mesh()
module += "mainMesh" to m
target += "boo" to module

val camera = Camera()
m.p = camera.p
m.m = camera.m
// finalColor is set every frame; the vertex shader uses its xy as a random offset.
m.finalColor = { vec(Math.random(), Math.random(), Math.random(), 1.0) }

var kc by keyCamera(camera = camera)
// Rebuild a large quad every frame.
loop {
    val sc = 10.5
    m.set(4, 2) {
        pos.add(-sc, -sc, 0)
        pos.add(sc, -sc, 0)
        pos.add(sc, sc, 0)
        pos.add(-sc, sc, 0)
        tri.add(0, 1, 2)
        tri.add(0, 2, 3)
    }
}
