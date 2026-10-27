{{ shadertoy.image }}

float hash(vec2 p) {
    p = fract(p * vec2(234.34, 435.35));
    p += dot(p, p + 34.23);
    return fract(p.x * p.y);
}

// Falling masks invert an increasing smoothstep; reversed edges are undefined in GLSL.
void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = (2.0 * fragCoord - iResolution.xy) / iResolution.y;
    float t = iTime * 0.55;
    float a = atan(uv.y, uv.x);
    float r = length(uv);
    vec3 col = vec3(0.002, 0.004, 0.009);

    for (int i = 0; i < 48; i++) {
        float fi = float(i);
        float lane = floor(fi / 6.0);
        float spoke = mod(fi, 6.0);
        float z = fract(t + fi * 0.071);
        float rr = mix(1.55, 0.05, z);
        float aa = spoke * 1.047 + lane * 0.38 + t * 1.7 + sin(lane) * 0.5;
        vec2 pos = vec2(cos(aa), sin(aa)) * rr;
        float star = pow(max(0.0, 1.0 - length(uv - pos) / (0.015 + z * 0.035)), 8.0);
        vec3 tint = 0.55 + 0.45 * cos(vec3(0.2, 1.8, 4.1) + fi * 0.7);
        col += star * tint * (0.25 + z * 2.2);
    }

    float warp = sin(a * 9.0 - r * 7.0 + t * 8.0);
    col += vec3(0.03, 0.08, 0.15) * (1.0 - smoothstep(0.0, 0.95, abs(warp))) * (1.0 - smoothstep(0.2, 1.5, r));
    col *= 1.0 - smoothstep(1.15, 1.75, r);
    fragColor = vec4(col, 1.0);
}
