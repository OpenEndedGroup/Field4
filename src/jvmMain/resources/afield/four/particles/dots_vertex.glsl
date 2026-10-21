#version 450
#extension GL_EXT_buffer_reference : require

// Draws a ParticleSketch's particles as discs of their radius and colour, one quad each;
// shape_fragment.glsl antialiases the edge. Slots past the live count are moved off screen.

struct Particle {
    vec2 pos;
    vec2 vel;
    vec2 acc;
    vec2 home;
    vec4 color;
    float radius;
    float mass;
    float seed;
    float age;
    int group;
    uint id;
    float _reserved0;
    float _reserved1;
    vec4 data;
};

layout(std430, buffer_reference) readonly buffer Particles { Particle p[]; };

layout(push_constant) uniform Push {
    Particles particles;
    vec2 size;        // the world, in points: canvas pixels
    vec2 targetSize;  // the accumulation, in device pixels
    uint count;
} push;

layout(location = 0) out vec2 local;
layout(location = 1) flat out vec4 shape;
layout(location = 2) flat out vec4 color;

const vec2 corner[6] = vec2[](
    vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(1.0, 1.0),
    vec2(-1.0, -1.0), vec2(1.0, 1.0), vec2(-1.0, 1.0)
);

void main() {
    uint i = uint(gl_VertexIndex) / 6u;
    Particle p = push.particles.p[min(i, max(push.count, 1u) - 1u)];
    if (i >= push.count || !(p.radius > 0.0) || any(isnan(p.pos))) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        local = vec2(0.0);
        shape = vec4(0.0);
        color = vec4(0.0);
        return;
    }
    vec2 scale = push.targetSize / push.size;
    float radius = p.radius * scale.x;
    vec2 l = corner[gl_VertexIndex % 6] * (radius + 1.0);
    vec2 pixel = p.pos * scale + l;
    local = l;
    shape = vec4(0.0, radius, 2.0, 0.0);
    color = p.color;
    gl_Position = vec4(pixel / push.targetSize * 2.0 - 1.0, 0.0, 1.0);
}
