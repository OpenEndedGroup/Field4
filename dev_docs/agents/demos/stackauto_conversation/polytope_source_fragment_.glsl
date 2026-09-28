#version 450
layout(location = 0) in vec3 vN;
layout(location = 1) in vec3 vLocal;
layout(location = 2) flat in float vId;
layout(location = 3) in float vAway;
layout(location = 0) out vec4 outColor;
layout(push_constant) uniform matrices { mat4 p; mat4 m; vec4 params; } pc;

// face_decay(vec4(place on the face xy, how far the face has flown, time)):
// .rgb is the face's colour; where .a falls below 0.5 the face burns through.
{{ stack.auto.face_decay }}

void main() {
    vec4 d = face_decay(vec4(vLocal.xy * 3.0 + 0.5, vAway, pc.params.x));
    if (d.a < 0.5) discard;
    float light = 0.35 + 0.65 * max(dot(normalize(vN), normalize(vec3(0.4, 0.8, 0.6))), 0.0);
    outColor = vec4(d.rgb * light, 1.0);
}
