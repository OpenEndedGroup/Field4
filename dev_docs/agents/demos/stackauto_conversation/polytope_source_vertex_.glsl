#version 450
layout(location = 0) in vec3 pos;
layout(location = 1) in vec3 nrm;
layout(location = 2) in vec3 cen;
layout(location = 3) in float fid;
layout(location = 0) out vec3 vN;
layout(location = 1) out vec3 vLocal;
layout(location = 2) flat out float vId;
layout(location = 3) out float vAway;
layout(push_constant) uniform matrices { mat4 p; mat4 m; vec4 params; } pc;

// explode(vec4(face centre xyz, time)): .x pushes the face out along its normal,
// .y shrinks it about its own centre (0 keeps it whole, 1 shrinks it to a point).
{{ stack.auto.explode }}

void main() {
    vec4 e = explode(vec4(cen, pc.params.x));
    vec3 q = cen + (pos - cen) * (1.0 - clamp(e.y, 0.0, 0.95)) + nrm * e.x;
    vN = nrm;
    vLocal = pos - cen;
    vId = fid;
    vAway = e.x;
    gl_Position = pc.p * pc.m * vec4(q, 1.0);
}
