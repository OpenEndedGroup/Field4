/*
A sky you can live-code: single scattering through Earth's atmosphere (Nishita 1993, with Hillaire 2020's
coefficients), one fullscreen triangle beneath the renderer. The physics is `#include "sky/atmosphere.glsl"`;
the camera and the tone mapping are in this box's fragment property. Drag the sliders, or run one of the
box's preset properties (`sunset`, `twilight`, `space`) to jump there.
*/
import afield.graphics.sky.SkyView

// Published as this box's `skyView` property, so the preset properties can reach it.
prop<SkyView>(skyView)
val view = SkyView(sunElevation = 30.0, altitude = 3.5, pitch = 10.0, exposure = 2.0)
skyView = view

val sky = moduleHere {
    depthTest = false
    depthWrite = false
    blend = false
}
val quad = fullscreenTriangle(sky)
sky += "quad" to quad
renderer += "atmospheric-sky" to sky

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
println("atmospheric sky: sun ${view.sunElevation}°, altitude ${view.altitude} km")
