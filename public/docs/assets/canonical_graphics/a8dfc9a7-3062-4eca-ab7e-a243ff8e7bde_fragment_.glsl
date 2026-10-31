#version 450
layout(location = 0) out vec4 uFragColor;

layout(push_constant) uniform matrices {
    mat4 p;
    mat4 m;
} pc;

layout(set=0, binding = 0) uniform sampler2D otherView;

void main()
{

    // 1024 is the target's default size, so this maps pixels to texture coordinates.
    vec2 tc = gl_FragCoord.xy / 1024.0;
    vec4 c = texture(otherView, tc.xy + vec2(0.001));

    // Read last frame slightly offset and write it back with channels swapped, so the image drifts and
    // recolours instead of settling.
    uFragColor = vec4(c.gr+vec2(0.001),1,1);
}
