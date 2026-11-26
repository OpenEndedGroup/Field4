#version 450
layout(binding=0) uniform sampler2D worldLayer;
layout(binding=1) uniform sampler2D previousLayer;
layout(push_constant) uniform MotionPush {
    vec4 camera;
    vec4 scene;
    float deltaSeconds;
} pc;
// CAMERA
layout(location=0) out vec4 velocity;
void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 current = texelFetch(worldLayer, pixel, 0);
    vec4 previous = texelFetch(previousLayer, pixel, 0);
    velocity = vec4(0.0);
    if (current.w <= 1.0 || previous.w != current.w || pc.deltaSeconds < 1e-5) return;
    vec2 pixelsPerSecond = (worldPixel(current.xyz) - worldPixel(previous.xyz)) / pc.deltaSeconds;
    velocity = vec4(pixelsPerSecond, 1.0, length(pixelsPerSecond));
}
