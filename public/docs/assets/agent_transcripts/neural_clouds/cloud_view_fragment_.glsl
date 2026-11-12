#version 460
// cloudNetwork / cloudAmbient (weights as constants) and the raymarcher, from cloud_train
#include "cloud/network"
#include "cloud/scene"
layout(location=0) in vec2 uv;
layout(location=0) out vec4 color;

void main() {
    // Top: fitted ambient | network | single scatter.
    // Bottom: predicted in-scatter | sun transmittance | sky visibility.
    vec2 grid = vec2(uv.x * 3.0, uv.y * 2.0);
    vec2 st = fract(grid);
    int panel = int(grid.y) * 3 + int(grid.x);
    int modes[6] = int[](1, 2, 0, 3, 4, 5);
    int mode = modes[min(panel, 5)];

    // window aspect from the pixel footprint, then one panel's share of it
    float aspect = fwidth(uv.y) / fwidth(uv.x) * 2.0 / 3.0;
    vec2 ndc = vec2(st.x * 2.0 - 1.0, 1.0 - st.y * 2.0);
    vec3 c = cloudRender(vec3(4.6, 0.9, 3.6), vec3(0.0, 2.05, 0.0), 40.0, ndc, aspect, mode, gl_FragCoord.xy);

    if (mode < 3) c = 1.0 - exp(-c * 1.2); // the tone curve writeCloudComparison uses
    if (min(st.x, st.y) < 0.004) c = vec3(0.0);
    color = vec4(c, 1.0);
}