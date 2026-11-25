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

// Growing transverse slots leave cast ribs over a continuous shell back.
val apertures = unionOf(List(19) { i ->
    val u = i / 18.0
    val t = 0.75 + (sweep - 1.45) * u
    val r = coreRadius * exp(growth * t)
    val a = t - sweep
    val c = 0.05 + 0.42 * r
    sphere(1.0).sized(c * 0.90, r * 0.18, c * 0.60 + 0.10)
        .turned(0.0, 0.0, a * 180.0 / PI).at(r*cos(a), r*sin(a), c*0.65)
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
// A small luminous diffuser tucked into the broad outer chamber.
val diffuser = sphere(0.13).at(0.64, -0.435, 2.432)
val litLamp = lamp + diffuser

// Patina follows geometric shelter and concavity; exposed ridges wear back to bronze.
litLamp.shaded("""
float patinaHash(vec3 p) {
    return fract(sin(dot(p, vec3(127.1, 311.7, 74.7))) * 43758.5453);
}
float patinaNoise(vec3 p) {
    vec3 i = floor(p), f = fract(p);
    f = f*f*(3.0-2.0*f);
    return mix(mix(mix(patinaHash(i), patinaHash(i+vec3(1,0,0)), f.x),
                   mix(patinaHash(i+vec3(0,1,0)), patinaHash(i+vec3(1,1,0)), f.x), f.y),
               mix(mix(patinaHash(i+vec3(0,0,1)), patinaHash(i+vec3(1,0,1)), f.x),
                   mix(patinaHash(i+vec3(0,1,1)), patinaHash(i+vec3(1,1,1)), f.x), f.y), f.z);
}
vec3 shade(vec3 p, vec3 n, vec3 rd, float t) {
    vec3 l = normalize(vec3(-0.5, -0.7, 1.0));
    vec3 fill = normalize(vec3(0.8, 0.3, 0.5));
    float ao = occlusion(p, n);

    // Tangential field samples distinguish concave hollows from convex worn lips.
    vec3 u = normalize(cross(n, abs(n.z) < 0.9 ? vec3(0,0,1) : vec3(0,1,0)));
    vec3 v = cross(n,u);
    float e = 0.045;
    float bend = (sdScene(p+e*u)+sdScene(p-e*u)+sdScene(p+e*v)+sdScene(p-e*v)
                  -4.0*sdScene(p))/e;
    float hollow = smoothstep(0.015, 0.23, -bend);
    float ridge = smoothstep(0.13, 0.65, bend);
    float shelter = clamp((1.0-ao)*2.8 + hollow*0.8, 0.0, 1.0);

    // Irregular mineral blooms at several scales, fixed to the cast surface.
    float bloom = 0.58*patinaNoise(p*4.0) + 0.28*patinaNoise(p*13.0)
                  + 0.14*patinaNoise(p*47.0);
    float fleck = patinaNoise(p*125.0);
    float wear = ridge * (0.65 + 0.35*max(dot(n,l),0.0));
    float patina = smoothstep(0.30, 0.72, bloom + 0.43*shelter - 0.48*wear);
    vec3 oxide = mix(vec3(0.018,0.074,0.052), vec3(0.075,0.29,0.205), bloom);
    vec3 bronze = mix(vec3(0.16,0.070,0.026), vec3(0.57,0.30,0.105), wear);
    vec3 albedo = mix(bronze, oxide, patina) * (0.94 + 0.12*fleck);

    float diffuse = max(dot(n,l),0.0);
    float light = 0.34 + 1.05*diffuse + 0.20*max(dot(n,fill),0.0);
    float polish = (1.0-patina)*(0.15+0.85*wear);
    float spec = pow(max(dot(n,normalize(l-rd)),0.0), mix(24.0,100.0,wear));
    float grazing = pow(1.0-max(dot(n,-rd),0.0),4.0);
    vec3 metal = vec3(0.85,0.52,0.22) * spec * polish * 1.5;
    // Transform back into the tilted coil's coordinates to identify its inner face.
    // This separates a lit cavity from the dark outside, rather than painting everything orange.
    float cs = cos(radians(62.0)), sn = sin(radians(62.0));
    vec3 q = vec3(p.x, cs*p.y + sn*(p.z-3.25), -sn*p.y + cs*(p.z-3.25));
    vec3 nq = vec3(n.x, cs*n.y + sn*n.z, -sn*n.y + cs*n.z);
    float nearest = 1e5;
    vec3 chamberCentre = vec3(0.0);
    for (int j=0; j<64; ++j) {
        float a0 = 2.60*3.14159265*float(j)/63.0;
        float r0 = 0.15*exp(0.28*a0);
        vec3 centre = vec3(r0*cos(a0-2.60*3.14159265), r0*sin(a0-2.60*3.14159265), 0.0);
        float d = length(q-centre)-(0.05+0.42*r0);
        if (d < nearest) { nearest=d; chamberCentre=centre; }
    }
    float inward = dot(nq, normalize(q-chamberCentre));
    float inside = (1.0-smoothstep(-0.30,0.22,inward))*smoothstep(1.8,2.1,p.z);
    vec3 source = vec3(0.64,-0.435,2.432);
    vec3 toLight = source-p;
    float d2 = dot(toLight,toLight);
    if (length(p-source)<0.134) return vec3(5.0,2.4,0.75);
    float direct = max(dot(n,normalize(toLight)),0.0)/(0.30+d2);
    // Soft bounced amber remains visible around the curl beyond the direct source.
    float bounce = 0.55 + 0.45*exp(-0.45*d2);
    vec3 warm = vec3(1.0,0.32,0.065);
    vec3 cavity = warm*(bounce*0.85 + direct*1.7)*inside;
    vec3 outer = albedo*light*mix(0.45,1.0,ao)*0.28 + metal*0.42
                 + vec3(0.10,0.055,0.018)*grazing*polish;
    // Warm reveal edges bridge the glowing interior and the unlit bronze skin.
    float reveal = ridge*(1.0-smoothstep(0.0,0.60,inward))*smoothstep(1.8,2.1,p.z);
    return outer + cavity*(0.80+0.20*bloom) + warm*reveal*0.28;
}
""", background = 0x20262B)
