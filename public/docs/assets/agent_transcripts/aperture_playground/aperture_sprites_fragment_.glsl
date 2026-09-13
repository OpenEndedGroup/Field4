#version 450
layout(push_constant) uniform push { vec4 size; vec4 look; } pc;
layout(location=0) in vec2 local;
layout(location=1) flat in vec2 appearance; // visible energy, aperture radius
layout(location=0) out vec4 color;

// A disc convolved with a uniform circular aperture: overlap area / aperture area.
// This spreads the sprite's energy rather than inflating an equally bright soft circle.
float apertureDisc(float d, float body, float aperture, float aa) {
    if (aperture < aa) return 1.0-smoothstep(body-aa, body+aa, d);
    if (d >= body+aperture) return 0.0;
    if (d <= abs(body-aperture)) return min(1.0, body*body/(aperture*aperture));
    float d2 = d*d, r2 = body*body, b2 = aperture*aperture;
    float u = clamp((d2+r2-b2)/(2.0*d*body), -1.0, 1.0);
    float v = clamp((d2+b2-r2)/(2.0*d*aperture), -1.0, 1.0);
    float h = max(0.0, (-d+body+aperture)*(d+body-aperture)*(d-body+aperture)*(d+body+aperture));
    return clamp((r2*acos(u)+b2*acos(v)-0.5*sqrt(h))/(3.14159265*b2), 0.0, 1.0);
}

void main() {
    float r = length(local);
    float aa = max(fwidth(r), 0.002);
    float aperture = appearance.y;
    float disc = apertureDisc(r, 1.0, aperture, aa);
    float ring = max(0.0, disc-apertureDisc(r, 0.66, aperture, aa));
    // Independent outline, intensity and fill controls.
    float energy = pc.look.y*ring + pc.look.z*appearance.x*mix(ring,disc,pc.look.w);
    color = vec4(vec3(energy), 0.0);
}
