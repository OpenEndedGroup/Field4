/*
A sky you can live-code: single scattering through Earth's atmosphere (Nishita 1993, with Hillaire 2020's
coefficients), one fullscreen triangle beneath the renderer. The physics is `#include "sky/atmosphere.glsl"`;
the camera and the tone mapping are in this box's fragment property. Drag the sliders, or edit the numbers
below and re-run.
*/
var sunElevation = 30.0 // degrees above the horizon
var sunAzimuth = 0.0 // degrees, 0 is straight ahead
var altitude = 3.5 // km above the ground
var pitch = 10.0 // degrees the camera looks up (-90 looks straight down)
var exposure = 2.0
var fieldOfView = 90.0 // degrees, vertical

val sky = moduleHere {
    depthTest = false
    depthWrite = false
    blend = false
}
val quad = fullscreenTriangle(sky)
sky += "quad" to quad
renderer += "atmospheric-sky" to sky

quad.viewport = { vec(window.width.toFloat(), window.height.toFloat(), altitude, Math.toRadians(sunElevation)) }
quad.view = { vec(Math.toRadians(sunAzimuth), Math.toRadians(pitch), exposure, Math.toRadians(fieldOfView)) }

controlGroup("Sky") {
    slider("Sun elevation (degrees)", min = -12.0, max = 90.0, initialValue = sunElevation) { sunElevation = it }
    slider("Sun azimuth (degrees)", min = -180.0, max = 180.0, initialValue = sunAzimuth) { sunAzimuth = it }
    slider("Altitude (log10 km)", min = -3.0, max = 4.5, initialValue = Math.log10(altitude)) {
        altitude = Math.pow(10.0, it)
    }
    slider("Camera pitch (degrees)", min = -90.0, max = 90.0, initialValue = pitch) { pitch = it }
    slider("Exposure", min = 0.5, max = 40.0, initialValue = exposure) { exposure = it }
}
displayOpen("Sky")
println("atmospheric sky: sun ${sunElevation}°, altitude ${altitude} km")
