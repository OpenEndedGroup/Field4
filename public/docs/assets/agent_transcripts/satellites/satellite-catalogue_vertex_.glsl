#version 460
#extension GL_EXT_buffer_reference : require

// Three vec4s per satellite, as the satellites box's Orbit records are laid out.
layout(std430, buffer_reference) buffer OrbitData {
    vec4 v[];
};

// Kepler's equation with secular drift: the GLSL twin of MeanElements.position (MeanElements.glsl).
vec3 meanElementsPosition(vec4 shape, vec4 angles, vec3 rates, float t) {
    float m = angles.z + rates.z * t;
    float w = angles.y + rates.y * t;
    float o = angles.x + rates.x * t;
    float e = shape.y;
    float ea = m;
    for (int k = 0; k < 6; k++) ea -= (ea - e * sin(ea) - m) / (1.0 - e * cos(ea));
    float px = shape.x * (cos(ea) - e);
    float py = shape.x * sqrt(1.0 - e * e) * sin(ea);
    float x1 = cos(w) * px - sin(w) * py;
    float y1 = sin(w) * px + cos(w) * py;
    return vec3(cos(o) * x1 - sin(o) * cos(shape.z) * y1, sin(o) * x1 + cos(o) * cos(shape.z) * y1, sin(shape.z) * y1);
}

layout(push_constant) uniform Push {
    OrbitData orbits;
    mat4 p;
    mat4 m;
    vec4 eye;
    vec4 clock;
} pc;

layout(location = 0) out vec4 fcol;

void main()
{
    int i = 3 * gl_VertexIndex;
    // TEME, in Earth radii, then into the drawing's frame (north up)
    vec3 teme = meanElementsPosition(pc.orbits.v[i], pc.orbits.v[i + 1], pc.orbits.v[i + 2].xyz, pc.clock.x) / 6378.137;
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
