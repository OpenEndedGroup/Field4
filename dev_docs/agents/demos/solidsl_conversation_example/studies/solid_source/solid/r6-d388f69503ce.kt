import afield.lang.solidsl.*
import afield.lang.solidsl.study.*
import kotlin.math.*

// A logarithmic centreline: both its radius and its chamber grow as it winds.
val sweep = 2.60 * PI
val growth = 0.28
val coreRadius = 0.15
val wallHalf = 0.045
val samples = 64
val coilParts = List(samples) { i ->
    val t = sweep * i / (samples - 1.0)
    val r = coreRadius * exp(growth * t)
    val a = t - sweep
    val chamberRadius = 0.05 + 0.42 * r
    sphere(chamberRadius).at(r * cos(a), r * sin(a), 0.0)
}
// Blend overlapping round sections, then offset that same field on both sides.
// Shelled(half) gives a nominal full wall of 0.09 throughout the growing chamber.
val envelope = run {
    var layer = coilParts
    while (layer.size > 1) {
        layer = layer.chunked(2).map { pair ->
            if (pair.size == 1) pair[0] else smoothUnion(pair[0], pair[1], 0.075)
        }
    }
    layer.single()
}
val wall = envelope.shelled(wallHalf)

// Slice off the terminal cap perpendicular to the growth line: a real open mouth.
val endRadius = coreRadius * exp(growth * sweep)
val tangentAngle = atan2(1.0, growth)
val mouth = cuboid(3.0, 3.0, 3.0)
    .turned(0.0, 0.0, tangentAngle * 180.0 / PI)
    .at(endRadius + cos(tangentAngle) * 1.43, sin(tangentAngle) * 1.43, 0.0) and
    sphere(0.82).at(endRadius, 0.0, 0.0)

// Apertures ride the upper face of the actual coil, growing toward its mouth.
val apertures = unionOf(List(15) { i ->
    val u = i / 14.0
    val t = 0.6 + (sweep - 1.05) * u
    val r = coreRadius * exp(growth * t)
    val a = t - sweep
    val chamberRadius = 0.05 + 0.42 * r
    val holeRadius = 0.030 + 0.135 * u
    sphere(1.0).sized(holeRadius * 1.35, holeRadius, 0.20)
        .turned(0.0, 0.0, (a + tangentAngle) * 180.0 / PI)
        .at(r * cos(a), r * sin(a), chamberRadius)
})
val localShade = wall - mouth - apertures
// Tilt the coiling plane so it can be read both from the front and from above.
val shade = localShade.turned(62.0, 0.0, 0.0).at(0.0, 0.0, 3.25)

// A heavy, softly flared cast foot, retained below the much lighter shell.
val foot = cylinder(0.20, 0.77, 0.70).rounded(0.07).at(0.0, 0.0, 0.17)
val root = cylinder(0.43, 0.50, 0.12).rounded(0.05).at(0.0, 0.0, 0.42)
val pedestal = smoothUnion(foot, root, 0.14)
// The stem meets the underside of the outer whorl, not the cavity at its centre.
val stem = cylinder(2.12, 0.105, 0.085).at(0.10, -0.25, 1.46)
val neck = sphere(0.17).sized(1.0, 1.0, 1.5).at(0.10, -0.25, 2.43)
val support = smoothUnion(smoothUnion(pedestal, stem, 0.15), neck, 0.10)
val lamp = smoothUnion(support, shade, 0.065)

// Subtle, warm cast bronze rather than a polished machined finish.
lamp.shaded("""
vec3 shade(vec3 p, vec3 n, vec3 rd, float t) {
    vec3 l = normalize(vec3(-0.5, -0.7, 1.0));
    float diffuse = max(dot(n, l), 0.0);
    float grain = sin(p.x*73.0 + sin(p.z*57.0))*sin(p.y*91.0 + p.z*39.0);
    vec3 bronze = vec3(0.40, 0.225, 0.105) * (1.0 + 0.025*grain);
    float ao = occlusion(p, n);
    float spec = pow(max(dot(n, normalize(l-rd)), 0.0), 36.0);
    return bronze*(0.32 + 0.85*diffuse)*ao + vec3(0.55,0.36,0.17)*spec*0.45;
}
""", background = 0x20262B)
