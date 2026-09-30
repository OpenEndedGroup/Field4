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
    if (i >= pc.count) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        fcol = vec4(0.0);
        return;
    }

    Particle p = pc.particles.values[i];
    gl_Position = vec4(p.position, 1.0);
    fcol = p.color;
}
