#version 460

{{ Particle.all }}

layout(push_constant) uniform Push {
    ParticleArray particles;
    uint count;
} pc;

layout(location = 0) out vec4 fcol;

void main()
{
    uint i = uint(gl_VertexIndex);
    Particle p = pc.particles.elements[i];

    gl_Position = vec4(p.position, 1.0);
    fcol = p.color * float(pc.count > 0u);
}
