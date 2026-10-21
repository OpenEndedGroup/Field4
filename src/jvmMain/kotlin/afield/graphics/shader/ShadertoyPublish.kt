package afield.graphics.shader

import afield.document.graph.Box
import afield.media.webrtc.WebRTCNetwork
import afield.media.webrtc.WebRTCSink
import afield.media.webrtc.webrtcPublish

/**
 * ShaderTrinket's **Publish**: stream [canvas] to the class as `login/name`, with the stream's
 * preview and status in this box's output.
 *
 * [webrtcPublish] of the canvas's `rtt` at the canvas's size, so whatever shader is showing is what
 * is published, presets included. A browser ShaderTrinket on the same network receives it by name
 * (Receive, `@login/name`), and so does another Field's [afield.media.webrtc.webrtcSource].
 */
fun Box.publishShadertoyCanvas(
    name: String = "field-shader",
    canvas: ShadertoyCanvas = shadertoyCanvas(),
    network: WebRTCNetwork = WebRTCNetwork.default
): WebRTCSink = webrtcPublish(canvas.rtt, name, canvas.width, canvas.height, network, renderer = canvas.renderer)
