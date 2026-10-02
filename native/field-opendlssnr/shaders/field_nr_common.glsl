// Adapted from OpenDLSS-NR, MIT; see UPSTREAM_LICENSE.
// Shared pieces of the demo's NR compute passes (feature preprocess and temporal composite).
// Float -> half round trips are spelled out at the bit level: the NVIDIA GLSL compiler elides float(float16_t(x)).

float roundF16(float value) {
  uint bits = floatBitsToUint(value);
  uint sign = bits & 0x80000000u;
  uint magnitude = bits & 0x7fffffffu;
  if (magnitude >= 0x7f800000u) return value;
  if (magnitude >= 0x477ff000u) return uintBitsToFloat(sign | 0x7f800000u);
  if (magnitude < 0x38800000u) {
    float scaled = roundEven(uintBitsToFloat(magnitude) * 16777216.0);
    return uintBitsToFloat(sign) + uintBitsToFloat(sign | floatBitsToUint(scaled * 0.000000059604644775390625));
  }
  uint lsb = (magnitude >> 13u) & 1u;
  uint rounded = (magnitude + 0xfffu + lsb) & ~0x1fffu;
  return uintBitsToFloat(sign | rounded);
}

// Truncation (round toward zero) to the half grid, as truncate_half of the composite pass.
float truncateHalf(float value) {
  uint bits = floatBitsToUint(value);
  uint signBit = (bits >> 16u) & 0x8000u;
  uint exponent = (bits >> 23u) & 0xffu;
  uint mantissa = bits & 0x7fffffu;
  uint halfBits;
  if (exponent == 0xffu) {
    halfBits = signBit | (mantissa != 0u ? 0x7e00u : 0x7c00u);
  } else {
    int halfExponent = int(exponent) - 112;
    if (halfExponent >= 31) halfBits = signBit | 0x7c00u;
    else if (halfExponent <= 0) {
      if (halfExponent < -10) halfBits = signBit;
      else halfBits = signBit | ((mantissa | 0x800000u) >> uint(14 - halfExponent));
    } else halfBits = signBit | (uint(halfExponent) << 10u) | (mantissa >> 13u);
  }
  return unpackHalf2x16(halfBits).x;
}

float finiteNonnegative(float value) {
  return (value == value && abs(value) <= 65504.0) ? max(value, 0.0) : 0.0;
}

float srgbEncode(float value) {
  float bounded = clamp(value, 0.0, 1.0);
  return bounded <= 0.0031308 ? 12.92 * bounded : 1.055 * pow(bounded, 1.0 / 2.4) - 0.055;
}

float srgbDecode(float value) {
  float bounded = clamp(value, 0.0, 1.0);
  return bounded <= 0.04045 ? bounded / 12.92 : pow((bounded + 0.055) / 1.055, 2.4);
}

// The display proxy: paper-white relative scene value with a soft shoulder, sRGB encoded, on the half grid.
float proxyComponent(float scene, float paperWhite) {
  float value = scene / max(paperWhite, 0.05);
  if (value > 0.75) value = 0.75 + 0.25 * (1.0 - exp(-5.770780 * (value - 0.75)));
  return roundF16(srgbEncode(value));
}


layout(push_constant) uniform Push {
 uint fullWidth, fullHeight, validWidth, validHeight;
 uint seed; int style; float tone, structure;
 float skin; uint autoMask; float intensity; uint encoding;
 float paperWhite; uint temporal; uint historyValid; float motionSign;
 float blendScale; uint preserveAlpha; uint pad0, pad1;
} pc;
layout(binding=0) uniform sampler2D sourceColor;
layout(binding=1) uniform sampler2D historyPrev;
layout(binding=2) uniform sampler2D motionTex;
layout(binding=3) uniform sampler2D depthTex;
vec3 proxyAt(ivec2 p) {
 vec3 c=texelFetch(sourceColor,p,0).rgb;
 if(pc.encoding==1u) c=vec3(srgbEncode(c.r),srgbEncode(c.g),srgbEncode(c.b));
 if(pc.encoding==2u) c=vec3(proxyComponent(c.r,pc.paperWhite),proxyComponent(c.g,pc.paperWhite),proxyComponent(c.b,pc.paperWhite));
 return c;
}
vec2 previousUv(ivec2 p) { return (vec2(p)+0.5)/vec2(pc.validWidth,pc.validHeight)+pc.motionSign*texelFetch(motionTex,p,0).xy/(pc.pad0!=0u?vec2(pc.validWidth,pc.validHeight):vec2(1)); }
bool validHistory(ivec2 p) {
 if(pc.temporal==0u || pc.historyValid==0u) return false;
 vec2 uv=previousUv(p); vec2 velocity=texelFetch(motionTex,p,0).xy;
 float depth=texelFetch(depthTex,p,0).r;
 if(any(lessThan(uv,vec2(0))) || any(greaterThanEqual(uv,vec2(1))))return false;
 float oldDepth=texelFetch(historyPrev,ivec2(uv*vec2(pc.validWidth,pc.validHeight)),0).a;
 if(abs(depth-oldDepth)>max(0.002,0.01*depth))return false;
 return !any(isnan(velocity)) && !any(isinf(velocity)) && all(greaterThanEqual(uv,vec2(0))) && all(lessThan(uv,vec2(1))) && texelFetch(depthTex,p,0).r<1.0;
}
vec3 historySample(ivec2 pixel) {
  vec2 valid=vec2(pc.validWidth,pc.validHeight);
  vec2 position = previousUv(pixel) * valid;
  vec2 base = floor(position - 0.5) + 0.5;
  vec2 f = clamp(position - base, vec2(0.0), vec2(1.0));
  vec2 square = f * f; vec2 cube = f * square;
  vec2 w0 = fma(f + cube, vec2(-0.5), square);
  vec2 w1 = (cube * 1.5 - square * 2.5) + 1.0;
  vec2 w3 = (cube - square) * 0.5;
  vec2 w2 = ((1.0 - w0) - w1) - w3;
  vec2 middle = w1 + w2;
  vec2 low = clamp(base - 1.0, vec2(0.5), valid - 0.5) / valid;
  vec2 center = clamp(base + w2 / middle, vec2(0.5), valid - 0.5) / valid;
  vec2 high = clamp(base + 2.0, vec2(0.5), valid - 0.5) / valid;
  vec3 left = textureLod(historyPrev, vec2(low.x, center.y), 0.0).rgb;
  vec3 top = textureLod(historyPrev, vec2(center.x, low.y), 0.0).rgb;
  vec3 mid = textureLod(historyPrev, center, 0.0).rgb;
  vec3 bottom = textureLod(historyPrev, vec2(center.x, high.y), 0.0).rgb;
  vec3 right = textureLod(historyPrev, vec2(high.x, center.y), 0.0).rgb;
  float a = w0.x * middle.y; float b = w0.y * middle.x;
  float c = middle.x * middle.y; float d = w3.y * middle.x; float e = w3.x * middle.y;
  vec3 value = fma(left, vec3(a), top * b);
  value = fma(mid, vec3(c), value);
  value = fma(bottom, vec3(d), value);
  value = fma(right, vec3(e), value);
  return value * (1.0 / (e + (d + (c + (a + b)))));
}
