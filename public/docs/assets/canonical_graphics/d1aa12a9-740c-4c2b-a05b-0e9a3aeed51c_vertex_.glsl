#version 450
#extension GL_ARB_shader_viewport_layer_array : enable

layout (location = 0) out vec2 ftc;
layout (location = 1) out vec4 radii;
layout (location = 2) out vec4 weights;

// Push constants or a tiny UBO
layout(push_constant) uniform BlurParams {
    float radius;   // e.g., 5–8
    float sigma;    // e.g., radius * 0.5–0.7
} blur;

float gauss(float x, float sigma) {
    float s2 = sigma * sigma;
    return exp(-x * x / (2.0 * s2));
}

void main()
{
    const vec2 positions[4] = vec2[](
        vec2(-1.0, -1.0),
        vec2( 1.0, -1.0),
        vec2(-1.0,  1.0),
        vec2( 1.0,  1.0)
    );

    float r1 = blur.radius * 0.275;   // near
    float r2 = blur.radius * 0.65;    // mid
    float r3 = blur.radius * 0.95;    // far

    float w0 = gauss(0.0, blur.sigma);
    float w1 = gauss(r1, blur.sigma);
    float w2 = gauss(r2, blur.sigma);
    float w3 = gauss(r3, blur.sigma);
    float norm = w0 + 2.0 * (w1 + w2 + w3);

    radii = vec4(r1,r2,r3,norm);
    weights = vec4(w0,w1,w2,w3);

    vec2 p = positions[gl_VertexIndex];
    gl_Position = vec4(p, 0.0, 1.0);
    ftc = p * 0.5 + 0.5;

    gl_Layer = 1;
}