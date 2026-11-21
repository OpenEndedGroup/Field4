/*
A sky behind whatever the renderer above draws: single scattering through Earth's atmosphere.
The physics is `#include "sky/atmosphere.glsl"`; the camera and tone mapping are in this box's
fragment property. Launch it under a renderer and drag the sliders.
*/
import afield.graphics.sky.SkyView

val view = SkyView(sunElevation = 30.0, altitude = 3.5, pitch = 10.0, exposure = 2.0)
val sky = moduleHere {
    depthTest = false
    depthWrite = false
    blend = false
}
val quad = fullscreenTriangle(sky)
sky += "quad" to quad
// Keyed by this box's id, so a second copy does not replace the first.
val key = "sky_" + id.take(8)
renderer += key to sky
// Draw first, behind everything else under this renderer, however late this box launches: the
// other modules move after the sky, keeping their own order.
val modules = renderer.renderList.modules
val drawnAfter = modules.filterKeys { it != key }
drawnAfter.keys.forEach(modules::remove)
modules.putAll(drawnAfter)
quad.viewport = {
    vec(window.width.toFloat(), window.height.toFloat(), view.altitude, Math.toRadians(view.sunElevation))
}
quad.view = {
    vec(Math.toRadians(view.sunAzimuth), Math.toRadians(view.pitch), view.exposure, Math.toRadians(view.fieldOfView))
}

controlGroup("Sky") {
    slider("Sun elevation (degrees)", min = -12.0, max = 90.0, initialValue = view.sunElevation) {
        view.sunElevation = it
    }
    slider("Sun azimuth (degrees)", min = -180.0, max = 180.0, initialValue = view.sunAzimuth) { view.sunAzimuth = it }
    slider("Altitude (log10 km)", min = -3.0, max = 4.5, initialValue = Math.log10(view.altitude)) {
        view.altitude = Math.pow(10.0, it)
    }
    slider("Camera pitch (degrees)", min = -90.0, max = 90.0, initialValue = view.pitch) { view.pitch = it }
    slider("Exposure", min = 0.1, max = 40.0, initialValue = view.exposure) { view.exposure = it }
}
displayOpen("Sky")
