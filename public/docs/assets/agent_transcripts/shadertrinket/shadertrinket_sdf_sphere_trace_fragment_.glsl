{{ shadertoy.image }}

float sdSphere(vec3 p, float r) {
    return length(p) - r;
}

float mapScene(vec3 p) {
    float ca = cos(iTime * 0.7);
    float sa = sin(iTime * 0.7);
    p.xz = mat2(ca, -sa, sa, ca) * p.xz;
    float s = sdSphere(p, 0.82);
    float rings = abs(length(p.xz) - 0.98) - 0.025;
    float band = abs(p.y + 0.08 * sin(atan(p.z, p.x) * 5.0 + iTime * 2.0)) - 0.035;
    return min(s, max(rings, band));
}

vec3 calcNormal(vec3 p) {
    vec2 e = vec2(0.001, 0.0);
    return normalize(vec3(
    mapScene(p + e.xyy) - mapScene(p - e.xyy),
    mapScene(p + e.yxy) - mapScene(p - e.yxy),
    mapScene(p + e.yyx) - mapScene(p - e.yyx)
    ));
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = (2.0 * fragCoord - iResolution.xy) / iResolution.y;
    vec3 ro = vec3(0.0, 0.0, 3.1);
    vec3 rd = normalize(vec3(uv, -1.75));
    vec3 col = vec3(0.005, 0.007, 0.01);
    float t = 0.0;
    float glow = 0.0;

    for (int i = 0; i < 96; i++) {
        vec3 p = ro + rd * t;
        float d = mapScene(p);
        glow += 0.018 / (0.03 + abs(d));
        if (d < 0.001 || t > 8.0) break;
        t += d * 0.78;
    }

    if (t < 8.0) {
        vec3 p = ro + rd * t;
        vec3 n = calcNormal(p);
        vec3 l = normalize(vec3(-0.5, 0.7, 0.9));
        float diff = max(0.0, dot(n, l));
        float fres = pow(1.0 + dot(rd, n), 4.0);
        col = vec3(0.08, 0.16, 0.20) + diff * vec3(0.34, 0.88, 0.76) + fres * vec3(0.9, 0.35, 1.0);
    }

    col += glow * vec3(0.03, 0.22, 0.45);
    fragColor = vec4(col, 1.0);
}
