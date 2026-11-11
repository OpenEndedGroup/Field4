#version 460
{{ Orbit.all }}
{{ orbit.kepler }}

layout(push_constant) uniform Push {
    OrbitArray orbits;
    mat4 p;
    mat4 m;
    vec4 eye;
    vec4 clock;
} pc;

layout(location = 0) out vec4 fcol;

void main()
{
    Orbit o = pc.orbits.elements[gl_VertexIndex];
    // TEME, in Earth radii, then into the drawing's frame (north up)
    vec3 teme = meanElementsPosition(o.shape, o.angles, o.rates.xyz, pc.clock.x) / 6378.137;
    vec3 w = vec3(teme.y, teme.z, teme.x);

    // hidden behind the globe: the ray from the eye meets the unit sphere before it reaches the satellite
    vec3 d = w - pc.eye.xyz;
    float dist = length(d);
    d /= dist;
    float b = dot(pc.eye.xyz, d);
    float disc = b * b - (dot(pc.eye.xyz, pc.eye.xyz) - 1.0);
    float hit = -b - sqrt(max(disc, 0.0));
    bool hidden = disc > 0.0 && hit > 0.0 && hit < dist;

    // colour by height: below 600 km, 600 to 1,000 km, above
    float alt = (length(teme) - 1.0) * 6378.137;
    vec3 c = alt < 600.0 ? vec3(0.45, 0.8, 1.0) : (alt < 1000.0 ? vec3(1.0, 0.78, 0.35) : vec3(1.0, 0.45, 0.6));
    fcol = vec4(c, hidden ? 0.06 : 0.9);
    bool picked = gl_VertexIndex == int(pc.clock.z);
    if (picked) fcol = vec4(1.0, 0.95, 0.8, 1.0);

    gl_Position = pc.p * pc.m * vec4(w, 1);
    // level of detail by distance: a satellite near the camera is drawn larger
    gl_PointSize = picked ? 16.0 : clamp(pc.clock.y / dist, 1.5, 12.0);
}
