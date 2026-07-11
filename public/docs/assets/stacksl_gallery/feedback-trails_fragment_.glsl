#version 450
layout(location = 0) in vec2 fragUV;
layout(location = 0) out vec4 outColor;

// Feedback: `source` is the previous frame (bound by a ping-pong host).
layout(set = 0, binding = 0) uniform sampler2D source;
layout(push_constant) uniform Push { vec4 params; } pc;

float gallery_hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

{{ stack.auto.program }}

void main() {
    vec2 uv = fragUV;
    vec4 stackInput = vec4(uv, gallery_hash12(uv), pc.params.x);
    vec3 c = program(stackInput).rgb;
    if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
