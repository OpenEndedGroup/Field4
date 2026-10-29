#version 450
layout(location=0) in vec4 fcol;
layout(location=0) out vec4 outColor;
void main() {
    if (fcol.a > 0.5) {
        outColor = fcol;
        return;
    }
    vec2 uv = fcol.xy;
    vec3 color;
    if (uv.y > 0.8) {
        color = uv.x < 0.333 ? vec3(0.95,0.2,0.12) : uv.x < 0.667 ? vec3(0.15,0.9,0.35) : vec3(0.15,0.4,1);
    }
    else {
        float frequency = exp2(floor(uv.x * 6.0) + 3.0);
        float phase = uv.y * frequency * 6.283185;
        float aa = max(fwidth(phase), 0.01);
        float bars = smoothstep(-aa, aa, sin(phase));
        color = mix(vec3(0.05), vec3(0.95), bars);
    }
    outColor = vec4(color,1);
}
