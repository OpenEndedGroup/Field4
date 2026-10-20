#version 450
layout(location = 0) out vec4 color;

layout(push_constant) uniform constants {
    vec4 clock;
} pc;

// A band across the middle: bar 0 white, bars 1-24 the time code (MSB first), bar 25 black.
void main() {
    vec2 uv = gl_FragCoord.xy / vec2(640.0, 360.0);
    vec3 background = 0.5 + 0.5 * cos(vec3(0.0, 2.0, 4.0) + pc.clock.x + uv.xyx * 3.0);
    if (abs(uv.y - 0.5) < 0.08) {
        int bar = int(floor(uv.x * 26.0));
        float bit = bar == 0 ? 1.0 : bar == 25 ? 0.0 : mod(floor(pc.clock.y / exp2(float(24 - bar))), 2.0);
        color = vec4(vec3(bit), 1.0);
    } else {
        color = vec4(background * 0.6, 1.0);
    }
}
