import afield.media.stream.FieldRTTStreamer
import org.lwjgl.vulkan.VK10.*

// Streamer allocates + imports the surface and hands back the RTT to render into.

prop<FieldRTTStreamer>(streamer)
once.streamer {
    FieldRTTStreamer(renderer, dest = "127.0.0.1:9001", width = 1280, height = 720, fps = 15)
}
prop<RenderToTexture>(target) = streamer.start().first()

renderer.renderList += "target" to target

var module = moduleHere { +target }
var m = Mesh()
module += "mainMesh" to m
target += "boo" to module

val camera = Camera()
m.p = camera.p
m.m = camera.m
m.finalColor = { vec(Math.random(), Math.random(), Math.random(), 1.0) }

var kc by keyCamera(camera = camera)
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
