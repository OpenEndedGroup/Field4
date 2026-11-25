import afield.lang.solidsl.*
import afield.lang.solidsl.study.*
import kotlin.math.*

// The swollen outer chamber, with a small off-centre crown like a growing shell.
val chamber = sphere(1.0).sized(1.18, 0.98, 1.38).at(0.0, 0.0, 3.42)
val crown = sphere(0.54).sized(1.0, 0.94, 1.12).at(0.33, 0.0, 4.36)
val envelope = smoothUnion(chamber, crown, 0.32)

// A thin cast wall, open below for access to the light.
val wall = envelope.shelled(0.065)
val mouth = cylinder(0.9, 0.44).at(0.0, 0.0, 2.04)

// Growing, slanted oval apertures follow one continuous spiral up the chamber.
val apertures = unionOf(List(23) { i ->
    val u = i / 22.0
    val latitude = (-61.0 + 124.0 * u) * PI / 180.0
    val angle = 594.0 * u + 15.0
    val theta = angle * PI / 180.0
    val opening = 0.13 + 0.07 * sin(PI * u)
    sphere(1.0).sized(0.56, opening * 1.65, opening * 0.70)
        .turned(28.0, 0.0, angle)
        .at(1.18 * cos(latitude) * cos(theta),
            0.98 * cos(latitude) * sin(theta),
            3.42 + 1.38 * sin(latitude))
})
val shade = wall - mouth - apertures

// A low, heavy foot with a soft rolled edge and a flared neck.
val foot = cylinder(0.20, 0.77, 0.70).rounded(0.07).at(0.0, 0.0, 0.17)
val root = cylinder(0.43, 0.50, 0.12).rounded(0.05).at(0.0, 0.0, 0.42)
val pedestal = smoothUnion(foot, root, 0.14)

// The fine stem grows into the lower wall at the back of the open mouth.
val stem = cylinder(2.05, 0.105, 0.085).at(0.0, 0.0, 1.45)
val neck = sphere(0.18).sized(1.0, 1.0, 1.8).at(0.0, 0.0, 2.38)
val support = smoothUnion(smoothUnion(pedestal, stem, 0.16), neck, 0.12)
val lamp = smoothUnion(support, shade, 0.085)

// Warm, lightly mottled cast bronze: colour variation, not machined surface grooves.
lamp.shaded("""
vec3 shade(vec3 p, vec3 n, vec3 rd, float t) {
    vec3 l = normalize(vec3(-0.5, -0.7, 1.0));
    float diffuse = max(dot(n, l), 0.0);
    float grain = sin(p.x*73.0 + sin(p.z*57.0))*sin(p.y*91.0 + p.z*39.0);
    vec3 bronze = vec3(0.40, 0.225, 0.105) * (1.0 + 0.045*grain);
    float ao = occlusion(p, n);
    float spec = pow(max(dot(n, normalize(l-rd)), 0.0), 36.0);
    return bronze*(0.26 + 0.85*diffuse)*ao + vec3(0.55,0.36,0.17)*spec*0.45;
}
""", background = 0x20262B)
