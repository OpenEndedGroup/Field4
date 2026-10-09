#version 450
layout(location = 0) out vec4 color;
layout(set = 0, binding = 0) uniform sampler2D syphonIn;
void main() {
    // texel (x, y) of the received frame, y down from its top row, into pixel (x, y)
    color = texelFetch(syphonIn, ivec2(gl_FragCoord.xy), 0);
}
