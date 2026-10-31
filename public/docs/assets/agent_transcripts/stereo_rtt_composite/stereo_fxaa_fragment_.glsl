#version 450
layout(location = 0) in vec2 vUv;
layout(set = 0, binding = 0) uniform sampler2D sourceTex;

layout(push_constant) uniform FxaaPush {
    float aaBlend;
    float edgeThreshold;
    float edgeThresholdMin;
    float spanMax;
} push;

layout(location = 0) out vec4 outColor;

float luma(vec3 rgb) {
    return dot(rgb, vec3(0.299, 0.587, 0.114));
}

// Keep every FXAA tap in the fragment's own eye; no sampling across the stereo seam.
vec4 sampleEye(vec2 uv) {
    vec2 halfTexel = 0.5 / vec2(textureSize(sourceTex, 0));
    float eyeX = vUv.x < 0.5 ? 0.0 : 0.5;
    vec2 lower = vec2(eyeX, 0.0) + halfTexel;
    vec2 upper = vec2(eyeX + 0.5, 1.0) - halfTexel;
    return texture(sourceTex, clamp(uv, lower, upper));
}

void main() {
    vec2 inverseResolution = 1.0 / vec2(textureSize(sourceTex, 0));
    vec4 center = sampleEye(vUv);
    if (push.aaBlend <= 1e-5) {
        outColor = center;
        return;
    }

    vec3 rgbNW = sampleEye(vUv + vec2(-1.0, -1.0) * inverseResolution).rgb;
    vec3 rgbNE = sampleEye(vUv + vec2(1.0, -1.0) * inverseResolution).rgb;
    vec3 rgbSW = sampleEye(vUv + vec2(-1.0, 1.0) * inverseResolution).rgb;
    vec3 rgbSE = sampleEye(vUv + vec2(1.0, 1.0) * inverseResolution).rgb;
    vec3 rgbM = center.rgb;

    float lumaNW = luma(rgbNW);
    float lumaNE = luma(rgbNE);
    float lumaSW = luma(rgbSW);
    float lumaSE = luma(rgbSE);
    float lumaM = luma(rgbM);

    float lumaMin = min(lumaM, min(min(lumaNW, lumaNE), min(lumaSW, lumaSE)));
    float lumaMax = max(lumaM, max(max(lumaNW, lumaNE), max(lumaSW, lumaSE)));
    float lumaRange = lumaMax - lumaMin;
    float threshold = max(push.edgeThresholdMin, lumaMax * push.edgeThreshold);
    if (lumaRange < threshold) {
        outColor = center;
        return;
    }

    vec2 dir;
    dir.x = -((lumaNW + lumaNE) - (lumaSW + lumaSE));
    dir.y = ((lumaNW + lumaSW) - (lumaNE + lumaSE));

    float dirReduce = max(
    (lumaNW + lumaNE + lumaSW + lumaSE) * 0.03125,
    push.edgeThresholdMin
    );
    float rcpDirMin = 1.0 / (min(abs(dir.x), abs(dir.y)) + dirReduce);
    dir = clamp(dir * rcpDirMin, vec2(-push.spanMax), vec2(push.spanMax)) * inverseResolution;

    vec3 rgbA = 0.5 * (
    sampleEye(vUv + dir * (1.0 / 3.0 - 0.5)).rgb +
    sampleEye(vUv + dir * (2.0 / 3.0 - 0.5)).rgb
    );
    vec3 rgbB = rgbA * 0.5 + 0.25 * (
    sampleEye(vUv + dir * -0.5).rgb +
    sampleEye(vUv + dir * 0.5).rgb
    );

    float lumaB = luma(rgbB);
    vec3 fxaaColor = ((lumaB < lumaMin) || (lumaB > lumaMax)) ? rgbA : rgbB;
    outColor = vec4(mix(rgbM, fxaaColor, clamp(push.aaBlend, 0.0, 1.0)), center.a);
}
