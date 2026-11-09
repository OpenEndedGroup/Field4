#version 450
#include "sky/atmosphere.glsl"

layout(location = 0) out vec4 color;

layout(push_constant) uniform pcs {
    vec4 viewport; // width, height, altitude (km), sun elevation (radians)
    vec4 view; // sun azimuth, camera pitch, exposure, vertical field of view (radians)
} pc;

void main() {
    Atmosphere a = earthAtmosphere();

    // A pinhole camera at the given altitude, looking along -z and pitched up by view.y.
    vec2 uv = (gl_FragCoord.xy - 0.5 * pc.viewport.xy) / pc.viewport.y;
    uv.y = -uv.y;
    float pitch = pc.view.y;
    vec3 forward = vec3(0.0, sin(pitch), -cos(pitch));
    vec3 right = vec3(1.0, 0.0, 0.0);
    vec3 up = cross(right, forward);
    vec3 rd = normalize(forward + (uv.x * right + uv.y * up) * 2.0 * tan(0.5 * pc.view.w));
    vec3 ro = vec3(0.0, a.planetRadius + pc.viewport.z, 0.0);

    float e = pc.viewport.w, az = pc.view.x;
    vec3 sun = vec3(cos(e) * sin(az), sin(e), -cos(e) * cos(az));

    vec3 L = skyRadiance(a, ro, rd, sun, vec3(20.0), 0.00465, 32, 8);

    // Exposure, then a soft shoulder so the sun's disc saturates instead of clipping hue.
    color = vec4(1.0 - exp(-L * pc.view.z), 1.0);
}
