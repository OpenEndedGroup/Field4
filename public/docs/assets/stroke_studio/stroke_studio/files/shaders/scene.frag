#version 450
layout(push_constant) uniform ScenePush {
    vec4 camera; // eye xyz, vertical half-FOV tangent
    vec4 scene;  // time, previous time, width, height
} pc;
// CAMERA
// CANVAS

layout(location=0) out vec4 lit;
layout(location=1) out vec4 world;         // xyz, exact positive surface ID (1 = enclosure)
layout(location=2) out vec4 previousWorld; // previous world xyz of this *same surface point*, same ID

const vec3 axes[3] = vec3[](vec3(1.12, 1.03, 0.87), vec3(0.62, 0.72, 0.64), vec3(0.40));
const float enclosureRadius = 18.0;
const float enclosureId = 1.0;
const vec3 albedo[3] = vec3[](
    vec3(0.69, 0.21, 0.075),
    vec3(0.060, 0.41, 0.39),
    vec3(0.88, 0.67, 0.29)
);
const vec3 light = vec3(-0.57735, 0.78868, 0.21132);
vec3 center(int id, float t) {
    if (id == 0) return vec3(-0.62, 0.03 + 0.09 * sin(t * 0.9), 0);
    if (id == 1) return vec3(0.91 + 0.18 * sin(t), -0.30, -0.42);
    return vec3(0.10 + 1.14 * sin(t * 0.67), -0.61 + 0.15 * cos(t * 1.34), 1.10);
}
mat3 spin(int id, float t) {
    float angle = t * (id == 1 ? -0.22 : 0.31);
    float c = cos(angle), s = sin(angle);
    return mat3(c, 0, -s, 0, 1, 0, s, 0, c);
}
float hitEllipsoid(vec3 origin, vec3 ray, int id, float t) {
    mat3 inv = transpose(spin(id, t));
    vec3 o = inv * (origin - center(id, t)) / axes[id];
    vec3 d = inv * ray / axes[id];
    float b = dot(o, d), a = dot(d, d), h = b * b - a * (dot(o, o) - 1.0);
    return h < 0.0 ? -1.0 : (-b - sqrt(h)) / a;
}
void main() {
    float t = pc.scene.x;
    vec3 eye = pc.camera.xyz;
    vec3 ray = eyeRay(gl_FragCoord.xy);

    // The eye is inside the opaque sphere. Take the farther root to hit its inside wall.
    float b = dot(eye, ray);
    float enclosureHit = -b + sqrt(b*b + enclosureRadius*enclosureRadius - dot(eye, eye));
    float dist = enclosureHit;
    int material = -1;
    for (int i = 0; i < 3; ++i) {
        float hit = hitEllipsoid(eye, ray, i, t);
        if (hit > 0.0 && hit < dist) {
            dist = hit;
            material = i;
        }
    }

    vec3 p = eye + ray * dist;
    if (material < 0) {
        world = previousWorld = vec4(p, enclosureId);
        lit = vec4(canvas, 1);
        return;
    }

    mat3 r = spin(material, t);
    vec3 local = transpose(r) * (p - center(material, t));
    vec3 before = center(material, pc.scene.y) + spin(material, pc.scene.y) * local;
    vec3 normal = normalize(r * (local / (axes[material] * axes[material])));
    float diffuse = max(0.0, dot(normal, light));
    float viewEdge = 1.0 - max(dot(normal, -ray), 0.0);
    float rim = pow(viewEdge, 2.8) * max(dot(normal, vec3(0.40, 0.69, -0.60)), 0.0);
    float lighting = 0.003 + 1.06 * pow(diffuse, 1.35);
    lit = vec4(albedo[material] * lighting + rim * vec3(0.045, 0.065, 0.15), 1);
    world = vec4(p, float(material + 2));
    previousWorld = vec4(before, world.w);
}
