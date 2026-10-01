#version 450
#extension GL_EXT_buffer_reference : require
// Adapted Slug core: Copyright (c) 2026 AlphaPixel LLC. See LICENSE-slughorn.
layout(std430, buffer_reference, buffer_reference_align = 4) readonly buffer SlugData { uint words[]; };
layout(push_constant) uniform SlugPC {
    mat4 mvp;
    SlugData slugData;
    vec2 viewport;
    vec4 tint;
} pc;
vec4 data4(uint at) {
    return uintBitsToFloat(uvec4(pc.slugData.words[at], pc.slugData.words[at+1],
        pc.slugData.words[at+2], pc.slugData.words[at+3]));
}
layout(location=0) in vec2 emCoord;
layout(location=0) out vec4 fragColor;
#define TEX_WIDTH 9
#define SLUG_INDIRECTION_SIZE 32
uvec4 bandFetch(ivec2 xy) {
    uint i = pc.slugData.words[7] + uint(xy.y * 512 + xy.x);
    uint pair = pc.slugData.words[i];
    return uvec4(pair & 65535u, pair >> 16u, 0u, 0u);
}
vec4 curveFetch(ivec2 xy) {
    uint i = pc.slugData.words[6] + 4u * uint(xy.y * 512 + xy.x);
    return data4(i);
}
uint slug_CalcRootCode(float y1, float y2, float y3) {
	uint i1 = floatBitsToUint(y1) >> 31u;
	uint i2 = floatBitsToUint(y2) >> 30u;
	uint i3 = floatBitsToUint(y3) >> 29u;

	uint shift = (i2 & 2u) | (i1 & ~2u);
	shift = (i3 & 4u) | (shift & ~4u);

	return ((0x2E74u >> shift) & 0x0101u);
}

vec2 slug_SolveHorizPoly(vec4 p12, vec2 p3) {
	vec2 a = p12.xy - p12.zw * 2.0 + p3;
	vec2 b = p12.xy - p12.zw;
	float ra = 1.0 / a.y;
	float rb = 0.5 / b.y;
	float d = sqrt(max(b.y * b.y - a.y * p12.y, 0.0));
	float t1 = (b.y - d) * ra;
	float t2 = (b.y + d) * ra;
	if(abs(a.y) < 1.0 / 65536.0) { t1 = p12.y * rb; t2 = t1; }
	return vec2(
		(a.x * t1 - b.x * 2.0) * t1 + p12.x,
		(a.x * t2 - b.x * 2.0) * t2 + p12.x
	);
}

vec2 slug_SolveVertPoly(vec4 p12, vec2 p3) {
	vec2 a = p12.xy - p12.zw * 2.0 + p3;
	vec2 b = p12.xy - p12.zw;
	float ra = 1.0 / a.x;
	float rb = 0.5 / b.x;
	float d = sqrt(max(b.x * b.x - a.x * p12.x, 0.0));
	float t1 = (b.x - d) * ra;
	float t2 = (b.x + d) * ra;
	if(abs(a.x) < 1.0 / 65536.0) { t1 = p12.x * rb; t2 = t1; }
	return vec2(
		(a.y * t1 - b.y * 2.0) * t1 + p12.y,
		(a.y * t2 - b.y * 2.0) * t2 + p12.y
	);
}

ivec2 slug_CalcBandLoc(ivec2 glyphLoc, uint offset) {
	ivec2 bandLoc = ivec2(glyphLoc.x + int(offset), glyphLoc.y);
	bandLoc.y += bandLoc.x >> TEX_WIDTH;
	bandLoc.x &= (1 << TEX_WIDTH) - 1;
	return bandLoc;
}

// 2026-08-30: re-derive a curve's second texel location the same way slug_CalcBandLoc()
// re-derives a band fetch's row, instead of flat-adding to curveLoc.x. Packing today always
// aligns a curve's 2 texels to start at an even X so they never straddle a row, making this a
// no-op -- but endpoint-shared curve packing (planned) removes that alignment, so a curve's
// second texel CAN legitimately land at the start of the next row.
ivec2 slug_CalcCurveLoc(ivec2 curveLoc, int offset) {
	ivec2 loc = ivec2(curveLoc.x + offset, curveLoc.y);
	loc.y += loc.x >> TEX_WIDTH;
	loc.x &= (1 << TEX_WIDTH) - 1;
	return loc;
}

float slug_CalcCoverage(float xcov, float ycov, float xwgt, float ywgt) {
	float coverage = max(
		abs(xcov * xwgt + ycov * ywgt) / max(xwgt + ywgt, 1.0 / 65536.0),
		min(abs(xcov), abs(ycov))
	);
	return clamp(coverage, 0.0, 1.0);
}

float slug_Render(vec2 renderCoord, vec4 bandTransform, ivec2 glyphLoc, ivec2 bandMax) {
	vec2 emsPerPixel = fwidth(renderCoord);
	vec2 pixelsPerEm = 1.0 / emsPerPixel;

	// O(1) band index via indirection tables (2 fetches per axis).
	int qY = clamp(int(renderCoord.y * bandTransform.y + bandTransform.w), 0, SLUG_INDIRECTION_SIZE - 1);
	int qX = clamp(int(renderCoord.x * bandTransform.x + bandTransform.z), 0, SLUG_INDIRECTION_SIZE - 1);
	int bandY = int(bandFetch(ivec2(glyphLoc.x + qY, glyphLoc.y)).r);
	int bandX = int(bandFetch(ivec2(glyphLoc.x + SLUG_INDIRECTION_SIZE + qX, glyphLoc.y)).r);

	// Horizontal bands -- headers at glyphLoc + 2*IS + bandY
	float xcov = 0.0, xwgt = 0.0;
	uvec2 hbandData = bandFetch(ivec2(glyphLoc.x + 2 * SLUG_INDIRECTION_SIZE + bandY, glyphLoc.y)).xy;

	for(int ci = 0; ci < int(hbandData.x); ci++) {
		// Re-derive this fetch's row every iteration (instead of computing hbandLoc once
		// and flat-adding ci to its X) so a band's curve-index list can span more than one
		// texture row -- removes the old "count > texWidth" hard limit on shape complexity.
		// Costs up to ~10us on complex scenes vs. the single-fetch version; worth it.
		ivec2 hbandLoc = slug_CalcBandLoc(glyphLoc, hbandData.y + uint(ci));
		ivec2 curveLoc = ivec2(bandFetch(hbandLoc).xy);
		vec4 p12 = curveFetch(curveLoc) - vec4(renderCoord, renderCoord);

		// 2026-08-31: same re-derive-per-fetch pattern as slug_CalcBandLoc above, now applied to
		// a curve's own second texel -- needed once packTextures() (slughorn.cpp) started
		// sharing texels between connected curves (Lengyel's Slug convention: a chain of N
		// connected curves costs N+1 texels instead of 2N), which packs densely with no
		// per-curve row alignment. Adds a handful of ALU ops per curve iteration; negligible
		// next to the texture fetch and sqrt-based polynomial solve already in this loop.
		// Shipped as the default (no opt-in flag) -- lossless, and verified bit-identical
		// against the pre-sharing renderer at real CJK-atlas scale.
		vec2 p3 = curveFetch(slug_CalcCurveLoc(curveLoc, 1)).xy - renderCoord;

		if(max(max(p12.x, p12.z), p3.x) * pixelsPerEm.x < -0.5) break;

		uint code = slug_CalcRootCode(p12.y, p12.w, p3.y);
		if(code != 0u) {
			vec2 r = slug_SolveHorizPoly(p12, p3) * pixelsPerEm.x;
			if((code & 1u) != 0u) { xcov += clamp(r.x + 0.5, 0.0, 1.0); xwgt = max(xwgt, clamp(1.0 - abs(r.x) * 2.0, 0.0, 1.0)); }
			if(code > 1u) { xcov -= clamp(r.y + 0.5, 0.0, 1.0); xwgt = max(xwgt, clamp(1.0 - abs(r.y) * 2.0, 0.0, 1.0)); }
		}
	}

	// Vertical bands -- headers at glyphLoc + 2*IS + numHBands + bandX
	float ycov = 0.0, ywgt = 0.0;
	uvec2 vbandData = bandFetch(ivec2(glyphLoc.x + 2 * SLUG_INDIRECTION_SIZE + bandMax.y + 1 + bandX, glyphLoc.y)).xy;

	for(int ci = 0; ci < int(vbandData.x); ci++) {
		// See the matching comment in the horizontal-band loop above.
		ivec2 vbandLoc = slug_CalcBandLoc(glyphLoc, vbandData.y + uint(ci));
		ivec2 curveLoc = ivec2(bandFetch(vbandLoc).xy);
		vec4 p12 = curveFetch(curveLoc) - vec4(renderCoord, renderCoord);

		// See the matching endpoint-sharing tradeoff comment in the horizontal-band loop above.
		vec2 p3 = curveFetch(slug_CalcCurveLoc(curveLoc, 1)).xy - renderCoord;

		if(max(max(p12.y, p12.w), p3.y) * pixelsPerEm.y < -0.5) break;

		uint code = slug_CalcRootCode(p12.x, p12.z, p3.x);
		if(code != 0u) {
			vec2 r = slug_SolveVertPoly(p12, p3) * pixelsPerEm.y;
			if((code & 1u) != 0u) { ycov -= clamp(r.x + 0.5, 0.0, 1.0); ywgt = max(ywgt, clamp(1.0 - abs(r.x) * 2.0, 0.0, 1.0)); }
			if(code > 1u) { ycov += clamp(r.y + 0.5, 0.0, 1.0); ywgt = max(ywgt, clamp(1.0 - abs(r.y) * 2.0, 0.0, 1.0)); }
		}
	}

	return slug_CalcCoverage(xcov, ycov, xwgt, ywgt);
}


// Replace this function with a Field procedural fill. Coverage remains independent.
vec3 shade(vec2 q) { return pc.tint.rgb * (0.7 + 0.3*cos(vec3(0,2,4)+q.x*4.0+q.y*3.0)); }
void main() {
    ivec2 loc = ivec2(pc.slugData.words[16], pc.slugData.words[17]);
    ivec2 bandMax = ivec2(pc.slugData.words[18], pc.slugData.words[19]);
    float coverage = slug_Render(emCoord, data4(12u), loc, bandMax);
    if (coverage <= 0.0) discard;
    // Straight alpha; use a source-alpha blending pipeline. Convert explicitly for
    // premultiplied-alpha targets. Neither atlas data nor coverage are sRGB colors.
    fragColor = vec4(shade(emCoord), coverage * pc.tint.a);
}
