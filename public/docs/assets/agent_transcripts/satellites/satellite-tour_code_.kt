/*
`camera` comes from the Earth box above. The flight starts where that box put the camera, 2.6 Earth
radii above Chicago, and pulls out through the low shell, past the navigation satellites at four
radii, to eleven, where the geostationary ring at six and a half is seen whole, and circles there
for the rest of 40 seconds; after that the keyboard has the camera again.
*/
val cam = camera
val a = cam.at
val r0 = sqrt(a.x * a.x + a.y * a.y + a.z * a.z).toDouble()
val azimuth0 = atan2(a.x.toDouble(), a.z.toDouble())
val elevation0 = asin(a.y / r0)
val far = 11.0
val outSeconds = 14.0

fun smooth(s: Double) = s * s * (3 - 2 * s)

val t0 = System.nanoTime()
var t = 0.0
frames {
    while (frame() && t < 40.0) {
        t = (System.nanoTime() - t0) / 1e9
        val s = smooth((t / outSeconds).coerceIn(0.0, 1.0))
        // distance grows geometrically, so each doubling takes the same time
        val r = r0 * (far / r0).pow(s)
        // turn 70° round the north axis on the way out, then 4° a second; come down to 20° above the equator
        val azimuth = azimuth0 + Math.toRadians(70.0) * s + Math.toRadians(4.0) * maxOf(0.0, t - outSeconds)
        val elevation = elevation0 + (Math.toRadians(20.0) - elevation0) * s
        cam.at = vec(r * cos(elevation) * sin(azimuth), r * sin(elevation), r * cos(elevation) * cos(azimuth))
    }
}
