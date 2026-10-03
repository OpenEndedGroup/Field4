#version 450
layout(location = 0) out vec4 uFragColor;

layout(push_constant) uniform matrices {
    mat4 p;
    mat4 m;
    vec4 finalColor;
} pc;

void main()
{
    uFragColor = vec4(gl_FragCoord.x / 640.0, gl_FragCoord.y / 360.0, 0.5 + 0.5 * pc.finalColor.z, 1);
}
