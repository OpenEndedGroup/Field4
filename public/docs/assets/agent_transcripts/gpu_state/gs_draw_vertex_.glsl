#version 450
#extension GL_EXT_buffer_reference : require

layout(std430, buffer_reference) buffer State { vec4 a[]; };
layout(std430, buffer_reference) buffer Heat  { vec4 a[]; };

layout(location = 0) out vec2 tc;
layout(location = 1) out float warmth;

layout(push_constant) uniform push {
    State state;
    Heat  heat;
    float aspectRatio;
} pc;

const vec3[6] corner = vec3[6](
    vec3(0,0,0), vec3(1,0,0), vec3(1,1,0),
    vec3(0,0,0), vec3(1,1,0), vec3(0,1,0)
);

void main() {
    uint id = uint(gl_VertexIndex / 6);
    vec3 p = pc.state.a[id * 2u].xyz;
    float h = pc.heat.a[id].x;

    vec3 q = (corner[gl_VertexIndex % 6] - vec3(0.5, 0.5, 0.0)) * (0.009 + h * 0.10);
    vec2 s = (p.xy + q.xy) * vec2(1.0 / pc.aspectRatio, 1.0);

    gl_Position = vec4(s * 0.88, 0.5, 1.0);
    tc = corner[gl_VertexIndex % 6].xy;
    warmth = h;
}
