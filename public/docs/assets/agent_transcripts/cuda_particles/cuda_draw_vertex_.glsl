#version 450
#extension GL_EXT_buffer_reference : require

// The CUDA box's buffer: xy position in world units, z phase, w size.
layout(std430, buffer_reference) buffer Particles
{
    vec4 p[];
};

layout(location = 0) out vec2 tc;
layout(location = 1) out float phase;

layout(push_constant) uniform push {
    Particles particles;
    float aspectRatio;
} pc;

// The six corners of a unit square, as two triangles
const vec2[6] corner = {
    vec2(0, 0), vec2(1, 0), vec2(1, 1),
    vec2(0, 0), vec2(1, 1), vec2(0, 1)
};

void main()
{
    vec4 q = pc.particles.p[gl_VertexIndex / 6];
    vec2 c = corner[gl_VertexIndex % 6];
    vec2 xy = q.xy * 0.42 + (c - 0.5) * 0.006 * q.w;
    gl_Position = vec4(xy.x / pc.aspectRatio, xy.y, 0, 1);
    tc = c;
    phase = q.z;
}
