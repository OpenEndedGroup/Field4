#version 450
#extension GL_EXT_buffer_reference : require

layout(std430, buffer_reference) buffer Positions { vec4 pos[]; };

layout(location = 0) out vec2 tc;
layout(location = 1) out float ring;

layout(push_constant) uniform push {
    Positions inData;
    float aspectRatio;
} pc;

const vec3[6] part = {
    vec3(0,0,0), vec3(1,0,0), vec3(1,1,0),
    vec3(0,0,0), vec3(1,1,0), vec3(0,1,0)
};

void main() {
    vec4 particle = pc.inData.pos[gl_VertexIndex / 6];
    float scale = particle.w;
    vec3 p = (part[gl_VertexIndex % 6] - vec3(0.5, 0.5, 0)) * scale;
    // Net scale 0.9, so the outermost ring at full energy still sits inside the frame.
    p.xy += particle.xy * 18.0;
    p.xy *= vec2(1.0 / pc.aspectRatio, 1.0);
    gl_Position = vec4(p * 0.05, 1.0);
    tc = part[gl_VertexIndex % 6].xy;
    ring = particle.z;
}
