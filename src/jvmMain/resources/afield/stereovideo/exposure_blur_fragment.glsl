#version 450
layout(binding = 0) uniform sampler2D sourceImage; // packed SBS, linear RGBA16F
layout(push_constant) uniform BlurPush {
    vec4 blur; // sigma in source pixels X/Y; zero makes a full-resolution copy
} pc;

layout(location = 0) in vec2 uv; // packed image, top-left origin
layout(location = 0) out vec4 color;

void main() {
    vec2 size = vec2(textureSize(sourceImage, 0));
    float eye = step(.5, uv.x);
    vec2 lower = vec2(eye * .5, 0) + .5 / size;
    vec2 upper = vec2((eye + 1) * .5, 1) - .5 / size;
    float sigma = max(pc.blur.x, pc.blur.y);
    if (sigma < .001) {
        color = texture(sourceImage, clamp(uv, lower, upper));
        return;
    }
    // Nine symmetric taps span +/-3 sigma; every lookup is clamped to its own eye.
    vec4 sum = vec4(0);
    float mass = 0;
    for (int i = -4; i <= 4; ++i) {
        float p = float(i) * .75;
        float weight = exp(-.5 * p * p);
        vec2 q = clamp(uv + p * pc.blur.xy / size, lower, upper);
        sum += weight * texture(sourceImage, q);
        mass += weight;
    }
    color = sum / mass;
}
