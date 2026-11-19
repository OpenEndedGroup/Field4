// GLSL in C++ for FM-1 firmware hooks (Field, AGPL-3.0-or-later).
//
// StackSL lowers to GLSL; FM1Hooks rewrites that GLSL lightly (constructors vec4(...) become vec4_(...),
// mat2(...) mat2_(...), number literals get an f) and compiles it as C++ against this file with the
// WASI SDK's clang. Clang's ext_vector_type supplies GLSL's vector arithmetic and swizzles (v.yx, v.xyz);
// the overloads below supply the built-in functions and the two matrix sizes StackSL's library uses.
#pragma once
#define precise
#define highp
#define mediump
#define lowp

typedef float vec2 __attribute__((ext_vector_type(2)));
typedef float vec3 __attribute__((ext_vector_type(3)));
typedef float vec4 __attribute__((ext_vector_type(4)));
typedef int bvec2 __attribute__((ext_vector_type(2)));
typedef int bvec3 __attribute__((ext_vector_type(3)));
typedef int bvec4 __attribute__((ext_vector_type(4)));

#define G static inline __attribute__((always_inline))

// ── constructors ───────────────────────────────────────────────────────────────────────────
G vec2 vec2_(float a) { return vec2{a, a}; }
G vec2 vec2_(float a, float b) { return vec2{a, b}; }
G vec2 vec2_(vec2 a) { return a; }
G vec2 vec2_(vec3 a) { return a.xy; }
G vec2 vec2_(vec4 a) { return a.xy; }
G vec3 vec3_(float a) { return vec3{a, a, a}; }
G vec3 vec3_(float a, float b, float c) { return vec3{a, b, c}; }
G vec3 vec3_(vec2 a, float b) { return vec3{a.x, a.y, b}; }
G vec3 vec3_(float a, vec2 b) { return vec3{a, b.x, b.y}; }
G vec3 vec3_(vec3 a) { return a; }
G vec3 vec3_(vec4 a) { return a.xyz; }
G vec4 vec4_(float a) { return vec4{a, a, a, a}; }
G vec4 vec4_(float a, float b, float c, float d) { return vec4{a, b, c, d}; }
G vec4 vec4_(vec2 a, float b, float c) { return vec4{a.x, a.y, b, c}; }
G vec4 vec4_(float a, vec2 b, float c) { return vec4{a, b.x, b.y, c}; }
G vec4 vec4_(float a, float b, vec2 c) { return vec4{a, b, c.x, c.y}; }
G vec4 vec4_(vec2 a, vec2 b) { return vec4{a.x, a.y, b.x, b.y}; }
G vec4 vec4_(vec3 a, float b) { return vec4{a.x, a.y, a.z, b}; }
G vec4 vec4_(float a, vec3 b) { return vec4{a, b.x, b.y, b.z}; }
G vec4 vec4_(vec4 a) { return a; }

// ── component-wise built-ins ───────────────────────────────────────────────────────────────
G float glsl_fract(float x) { return x - __builtin_floorf(x); }
G float glsl_sign(float x) { return x > 0.0f ? 1.0f : x < 0.0f ? -1.0f : 0.0f; }
G float glsl_inversesqrt(float x) { return 1.0f / __builtin_sqrtf(x); }
G float glsl_mod(float x, float y) { return x - y * __builtin_floorf(x / y); }
G float glsl_step(float e, float x) { return x < e ? 0.0f : 1.0f; }
G float glsl_radians(float x) { return x * 0.017453292519943295f; }
G float glsl_degrees(float x) { return x * 57.29577951308232f; }

#define GLSL_MAP1(name, f) \
  G float name(float x) { return f(x); } \
  G vec2 name(vec2 x) { return vec2{f(x.x), f(x.y)}; } \
  G vec3 name(vec3 x) { return vec3{f(x.x), f(x.y), f(x.z)}; } \
  G vec4 name(vec4 x) { return vec4{f(x.x), f(x.y), f(x.z), f(x.w)}; }
GLSL_MAP1(sin, __builtin_sinf) GLSL_MAP1(cos, __builtin_cosf) GLSL_MAP1(tan, __builtin_tanf)
GLSL_MAP1(asin, __builtin_asinf) GLSL_MAP1(acos, __builtin_acosf) GLSL_MAP1(atan, __builtin_atanf)
GLSL_MAP1(sinh, __builtin_sinhf) GLSL_MAP1(cosh, __builtin_coshf) GLSL_MAP1(tanh, __builtin_tanhf)
GLSL_MAP1(exp, __builtin_expf) GLSL_MAP1(log, __builtin_logf) GLSL_MAP1(exp2, __builtin_exp2f)
GLSL_MAP1(log2, __builtin_log2f) GLSL_MAP1(sqrt, __builtin_sqrtf) GLSL_MAP1(abs, __builtin_fabsf)
GLSL_MAP1(floor, __builtin_floorf) GLSL_MAP1(ceil, __builtin_ceilf) GLSL_MAP1(round, __builtin_roundf)
GLSL_MAP1(trunc, __builtin_truncf) GLSL_MAP1(fract, glsl_fract) GLSL_MAP1(sign, glsl_sign)
GLSL_MAP1(inversesqrt, glsl_inversesqrt) GLSL_MAP1(radians, glsl_radians) GLSL_MAP1(degrees, glsl_degrees)

#define GLSL_MAP2(name, f) \
  G float name(float a, float b) { return f(a, b); } \
  G vec2 name(vec2 a, vec2 b) { return vec2{f(a.x, b.x), f(a.y, b.y)}; } \
  G vec3 name(vec3 a, vec3 b) { return vec3{f(a.x, b.x), f(a.y, b.y), f(a.z, b.z)}; } \
  G vec4 name(vec4 a, vec4 b) { return vec4{f(a.x, b.x), f(a.y, b.y), f(a.z, b.z), f(a.w, b.w)}; } \
  G vec2 name(vec2 a, float b) { return name(a, vec2_(b)); } \
  G vec3 name(vec3 a, float b) { return name(a, vec3_(b)); } \
  G vec4 name(vec4 a, float b) { return name(a, vec4_(b)); }
GLSL_MAP2(min, __builtin_fminf) GLSL_MAP2(max, __builtin_fmaxf) GLSL_MAP2(pow, __builtin_powf)
GLSL_MAP2(mod, glsl_mod) GLSL_MAP2(atan, __builtin_atan2f)
G float step(float e, float x) { return glsl_step(e, x); }
G vec2 step(vec2 e, vec2 x) { return vec2{glsl_step(e.x, x.x), glsl_step(e.y, x.y)}; }
G vec3 step(vec3 e, vec3 x) { return vec3{glsl_step(e.x, x.x), glsl_step(e.y, x.y), glsl_step(e.z, x.z)}; }
G vec4 step(vec4 e, vec4 x) { return vec4{glsl_step(e.x, x.x), glsl_step(e.y, x.y), glsl_step(e.z, x.z), glsl_step(e.w, x.w)}; }
G vec2 step(float e, vec2 x) { return step(vec2_(e), x); }
G vec3 step(float e, vec3 x) { return step(vec3_(e), x); }
G vec4 step(float e, vec4 x) { return step(vec4_(e), x); }

// ── clamp, mix, smoothstep ─────────────────────────────────────────────────────────────────
#define GLSL_GENTYPE(T, S) \
  G T clamp(T x, T lo, T hi) { return min(max(x, lo), hi); } \
  G T clamp(T x, float lo, float hi) { return min(max(x, lo), hi); } \
  G T mix(T a, T b, T t) { return a + (b - a) * t; } \
  G T mix(T a, T b, float t) { return a + (b - a) * t; } \
  G T smoothstep(T e0, T e1, T x) { T t = clamp((x - e0) / (e1 - e0), 0.0f, 1.0f); return t * t * (3.0f - 2.0f * t); } \
  G T smoothstep(float e0, float e1, T x) { return smoothstep(S(e0), S(e1), x); }
GLSL_GENTYPE(vec2, vec2_) GLSL_GENTYPE(vec3, vec3_) GLSL_GENTYPE(vec4, vec4_)
G float clamp(float x, float lo, float hi) { return min(max(x, lo), hi); }
G float mix(float a, float b, float t) { return a + (b - a) * t; }
G float smoothstep(float e0, float e1, float x) { float t = clamp((x - e0) / (e1 - e0), 0.0f, 1.0f); return t * t * (3.0f - 2.0f * t); }
G vec2 mix(vec2 a, vec2 b, bvec2 s) { return s ? b : a; }
G vec3 mix(vec3 a, vec3 b, bvec3 s) { return s ? b : a; }
G vec4 mix(vec4 a, vec4 b, bvec4 s) { return s ? b : a; }

// ── geometry ───────────────────────────────────────────────────────────────────────────────
G float dot(float a, float b) { return a * b; }
G float dot(vec2 a, vec2 b) { return a.x * b.x + a.y * b.y; }
G float dot(vec3 a, vec3 b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
G float dot(vec4 a, vec4 b) { return a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w; }
G float length(float a) { return __builtin_fabsf(a); }
G float length(vec2 a) { return __builtin_sqrtf(dot(a, a)); }
G float length(vec3 a) { return __builtin_sqrtf(dot(a, a)); }
G float length(vec4 a) { return __builtin_sqrtf(dot(a, a)); }
template <typename T> G float distance(T a, T b) { return length(a - b); }
G float normalize(float a) { return glsl_sign(a); }
G vec2 normalize(vec2 a) { return a / length(a); }
G vec3 normalize(vec3 a) { return a / length(a); }
G vec4 normalize(vec4 a) { return a / length(a); }
G vec3 cross(vec3 a, vec3 b) { return vec3{a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x}; }
template <typename T> G T reflect(T i, T n) { return i - 2.0f * dot(n, i) * n; }

// ── comparisons into bvecs ─────────────────────────────────────────────────────────────────
#define GLSL_CMP(name, op) \
  G bvec2 name(vec2 a, vec2 b) { return a op b; } \
  G bvec3 name(vec3 a, vec3 b) { return a op b; } \
  G bvec4 name(vec4 a, vec4 b) { return a op b; }
GLSL_CMP(lessThan, <) GLSL_CMP(lessThanEqual, <=) GLSL_CMP(greaterThan, >)
GLSL_CMP(greaterThanEqual, >=) GLSL_CMP(equal, ==) GLSL_CMP(notEqual, !=)
G bool any(bvec2 b) { return b.x || b.y; }
G bool any(bvec3 b) { return b.x || b.y || b.z; }
G bool any(bvec4 b) { return b.x || b.y || b.z || b.w; }
G bool all(bvec2 b) { return b.x && b.y; }
G bool all(bvec3 b) { return b.x && b.y && b.z; }
G bool all(bvec4 b) { return b.x && b.y && b.z && b.w; }

// ── matrices (column-major, as GLSL) ───────────────────────────────────────────────────────
struct mat2 { vec2 c0, c1; };
struct mat3 { vec3 c0, c1, c2; };
G mat2 mat2_(float a, float b, float c, float d) { return mat2{vec2{a, b}, vec2{c, d}}; }
G mat2 mat2_(vec2 a, vec2 b) { return mat2{a, b}; }
G mat2 mat2_(float s) { return mat2{vec2{s, 0.0f}, vec2{0.0f, s}}; }
G mat3 mat3_(vec3 a, vec3 b, vec3 c) { return mat3{a, b, c}; }
G mat3 mat3_(float a, float b, float c, float d, float e, float f, float g, float h, float i) {
  return mat3{vec3{a, b, c}, vec3{d, e, f}, vec3{g, h, i}};
}
G mat3 mat3_(float s) { return mat3{vec3{s, 0.0f, 0.0f}, vec3{0.0f, s, 0.0f}, vec3{0.0f, 0.0f, s}}; }
G vec2 operator*(mat2 m, vec2 v) { return m.c0 * v.x + m.c1 * v.y; }
G vec2 operator*(vec2 v, mat2 m) { return vec2{dot(v, m.c0), dot(v, m.c1)}; }
G vec3 operator*(mat3 m, vec3 v) { return m.c0 * v.x + m.c1 * v.y + m.c2 * v.z; }
G vec3 operator*(vec3 v, mat3 m) { return vec3{dot(v, m.c0), dot(v, m.c1), dot(v, m.c2)}; }
G mat2 operator*(mat2 a, mat2 b) { return mat2{a * b.c0, a * b.c1}; }
G mat3 operator*(mat3 a, mat3 b) { return mat3{a * b.c0, a * b.c1, a * b.c2}; }
G mat2 operator*(mat2 a, float s) { return mat2{a.c0 * s, a.c1 * s}; }
G mat3 operator*(mat3 a, float s) { return mat3{a.c0 * s, a.c1 * s, a.c2 * s}; }
