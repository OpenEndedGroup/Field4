package afield.media.webrtc

import afield.app.diagnostics.logging.lprintln
import afield.document.graph.Box
import afield.graphics.render.Mesh
import afield.graphics.render.Module
import afield.graphics.render.ModulePointer
import afield.graphics.render.parameters
import afield.graphics.render.pointer
import afield.graphics.shader.fromViews
import afield.graphics.shader.isUnorm8
import afield.graphics.shader.sampleableSize
import afield.graphics.texture.HasDefaultTextureSampler
import afield.graphics.texture.HasImageView
import afield.graphics.texture.TextureSampler
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.targets.RenderToTexture
import afield.document.context.fromContext

/**
 * Publishing what Field renders as a WebRTC stream, and the colour conversion that makes the stream
 * look like the window.
 *
 * [webrtcPublish] is the one call a box makes: it creates a sink of the size you ask for and adds a
 * copy pass that samples your source into it every frame. The copy is a full-screen triangle whose
 * fragment shader is written for the source and target formats it finds, so an 8-bit UNORM source
 * holding display values and a float or `_SRGB` one holding linear light both come out looking the
 * same. `WebRTCPublishTest` pins which of the four pairings converts and which passes the colour
 * through, and compiles every variant. The sink itself, the sources and the networks are in
 * `WebRTC.kt`.
 */

/**
 * Publish anything Field can sample — a [RenderToTexture], a shadertoy canvas's `rtt`, a camera, a
 * received stream — as the WebRTC stream `login/name`, and show its preview and status in this box's
 * [show] output channel (`null` to show nothing).
 *
 * A [webrtcSink] of [width]×[height] (by default [source]'s own size) is fed every frame by a copy
 * pass that samples [source]. The copy keeps what you see: an `_SRGB` or float source holds linear
 * light and an 8-bit UNORM one holds display values, and the copy converts so the stream shows the
 * same picture as the window does. A source of another aspect is stretched to fill the stream.
 *
 * ```
 * val target = RenderToTexture { width = 640; height = 360; useDepth = false }
 * // ... draw into target ...
 * webrtcPublish(target, "orbit")   // a browser ShaderTrinket receives @you/orbit
 * ```
 *
 * The sink belongs to this box, so re-running the box replaces it and deleting the box stops
 * publishing. It needs the Field Electron app (the sink is an Electron output-area view); elsewhere
 * its status line says so.
 */
fun Box.webrtcPublish(
    source: HasImageView,
    name: String,
    width: Int = sampleableSize(source, 640 to 360).first,
    height: Int = sampleableSize(source, 640 to 360).second,
    network: WebRTCNetwork = WebRTCNetwork.default,
    show: String? = "main",
    renderer: Renderer = fromContext()
): WebRTCSink {
    val sink = webrtcSink(name, width, height, network, renderer)
    sink.rtt += "published $name" to webrtcCopyPass(renderer, source, sink.rtt)
    if (show != null) display(show, sink.html())
    return sink
}

/** True when sampling a texture of this format gives display (sRGB-encoded) values rather than linear light. */
internal fun samplesDisplayValues(format: Int): Boolean = isUnorm8(format)

/** True when writing to a target of this format takes linear light (the hardware encodes it). */
internal fun storesFromLinear(format: Int): Boolean = !isUnorm8(format)

/**
 * The copy pass's fragment shader: sample `source` across the [width]×[height] target, converting
 * from what a source of [sourceFormat] holds to what a target of [targetFormat] wants.
 */
internal fun webrtcCopyFragment(sourceFormat: Int, targetFormat: Int, width: Int, height: Int): String {
    val fromDisplay = samplesDisplayValues(sourceFormat)
    val toLinear = storesFromLinear(targetFormat)
    val convert = when {
        fromDisplay && toLinear -> "displayToLinear(c)"
        !fromDisplay && !toLinear -> "linearToDisplay(c)"
        else -> "c"
    }
    return """#version 450
layout(location = 0) out vec4 color;
layout(set = 0, binding = 0) uniform sampler2D source;

vec3 linearToDisplay(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
}

vec3 displayToLinear(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c));
}

void main() {
    vec3 c = texture(source, gl_FragCoord.xy / vec2($width.0, $height.0)).rgb;
    color = vec4($convert, 1.0);
}
"""
}

internal const val WEBRTC_COPY_VERTEX = """#version 450
void main() {
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
}
"""

private fun webrtcCopyPass(renderer: Renderer, source: HasImageView, target: RenderToTexture): ModulePointer {
    val sampler = (source as? HasDefaultTextureSampler)?.defaultSampler
        ?: (source as? RenderToTexture)?.setDefaultSampler(anisotropyEnable = false)
        ?: TextureSampler(renderer, 1, false)
    val module = Module(parameters(renderer) {
        name = "webrtc publish -> ${target.parameters.debugName}"
        vertex = WEBRTC_COPY_VERTEX
        fragment = webrtcCopyFragment(
            source.imageView.imageViewData.format, target.parameters.format, target.width, target.height
        )
        blend = false
        depthTest = false
        depthWrite = false
        +target
        textureResolver = { if (it.name == "source") fromViews(listOf(source), "source", sampler) else null }
        errorHandler = { message, _ -> lprintln("error,graphics", "webrtcPublish: $message") }
    }).pointer()
    val mesh = Mesh(renderer, module)
    mesh.resize(3, 0, module)
    module.current.meshes["fullscreen"] = mesh
    return module
}
