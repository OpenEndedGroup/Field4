// splat/insert — the first half of a SplatShader's compute tab.
//
// Include it on the line after #version, write statements, then #include "splat/end". Your
// statements run once per splat per frame, inside main(), with these locals:
//
//   vec3  pos      the splat's centre, in the cloud's own coordinates   (change it)
//   vec3  scales   its three radii (standard deviations)                (change it)
//   vec4  rgba     its colour and opacity                               (change it)
//   vec4  quat     its rotation, a quaternion (x, y, z, w)              (change it)
//   float index    which splat this is, 0, 1, 2, ...                    (read it)
//   float t        seconds since the box ran                            (read it)
//   float pi
//
// and the helpers sat(x), rot2(a), hash31(p) and noise31(p) declared below. splat/end clamps
// rgba to 0..1, keeps every scale above 1e-4, and hands the splat to the renderer, which sorts
// and draws the splats where your statements left them.

#extension GL_EXT_buffer_reference : require
layout(local_size_x = 64) in;

// A splat as SplatSet writes it: (centre, opacity), (rgb, unused), (scales, unused), quaternion.
layout(std430, buffer_reference) readonly buffer SplatSource { vec4 v[]; };

// A splat as GaussianSplatRenderer draws it: (centre, opacity), (rgb, source),
// (covariance xx xy xz yy), (covariance yz zz, unused).
layout(std430, buffer_reference) writeonly buffer SplatTarget { vec4 v[]; };

layout(push_constant) uniform SplatPush {
    SplatSource splatsIn;
    SplatTarget splatsOut;
    uint splatCount;
    float t;
} splatPush;

float sat(float x) {
    return clamp(x, 0.0, 1.0);
}

mat2 rot2(float a) {
    float s = sin(a);
    float c = cos(a);
    return mat2(c, -s, s, c);
}

vec3 hash31(vec3 p) {
    p = fract(p * 0.3183099 + vec3(0.11, 0.17, 0.23));
    p *= 17.0;
    return fract(vec3(
        p.x * p.y * p.z,
        p.x + p.y * p.z,
        p.x * p.y + p.z
    ));
}

// Trilinear value noise, 0..1.
float noise31(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);

    float n000 = hash31(i + vec3(0.0, 0.0, 0.0)).x;
    float n100 = hash31(i + vec3(1.0, 0.0, 0.0)).x;
    float n010 = hash31(i + vec3(0.0, 1.0, 0.0)).x;
    float n110 = hash31(i + vec3(1.0, 1.0, 0.0)).x;
    float n001 = hash31(i + vec3(0.0, 0.0, 1.0)).x;
    float n101 = hash31(i + vec3(1.0, 0.0, 1.0)).x;
    float n011 = hash31(i + vec3(0.0, 1.0, 1.0)).x;
    float n111 = hash31(i + vec3(1.0, 1.0, 1.0)).x;

    float x00 = mix(n000, n100, f.x);
    float x10 = mix(n010, n110, f.x);
    float x01 = mix(n001, n101, f.x);
    float x11 = mix(n011, n111, f.x);
    float y0 = mix(x00, x10, f.y);
    float y1 = mix(x01, x11, f.y);
    return mix(y0, y1, f.z);
}

// The splat in a form splat/end can finish without your locals in scope.
void splatFinish(uint slot, vec3 pos, vec3 scales, vec4 rgba, vec4 quat);

void main() {
    uint splatSlot = gl_GlobalInvocationID.x;
    if (splatSlot >= splatPush.splatCount) return;

    uint splatBase = splatSlot * 4u;
    vec4 splatCentre = splatPush.splatsIn.v[splatBase + 0u];

    vec3 pos = splatCentre.xyz;
    vec3 scales = splatPush.splatsIn.v[splatBase + 2u].xyz;
    vec4 rgba = vec4(splatPush.splatsIn.v[splatBase + 1u].rgb, splatCentre.w);
    vec4 quat = splatPush.splatsIn.v[splatBase + 3u];
    float index = float(splatSlot);
    float t = splatPush.t;
    float pi = 3.141592653589793;

    // ---- your statements follow ----
