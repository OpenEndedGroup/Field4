#version 450
layout (location = 0) in vec3 pos;

layout(push_constant) uniform matrices {
    float time;
} pc;

void main()
{
    // One triangle that covers the whole target, in clip space: no camera, so every pixel is drawn.
    gl_Position = vec4(pos.xy, 0, 1);
}
