#version 450

// The page's body shapes by signed distance, in device pixels, antialiased over about a pixel:
// local is (along the heading, across it), shape = (kind, size, line half width, -).

layout(location = 0) in vec2 local;
layout(location = 1) flat in vec4 shape;
layout(location = 2) flat in vec4 color;

layout(location = 0) out vec4 outColor;

// iq's polygon distance, negative inside (even-odd), for up to eight vertices
float polygon(vec2 p, vec2 v[8], int n) {
    float d = dot(p - v[0], p - v[0]);
    float s = 1.0;
    for (int i = 0, j = n - 1; i < n; j = i, i++) {
        vec2 e = v[j] - v[i];
        vec2 w = p - v[i];
        vec2 b = w - e * clamp(dot(w, e) / dot(e, e), 0.0, 1.0);
        d = min(d, dot(b, b));
        bvec3 c = bvec3(p.y >= v[i].y, p.y < v[j].y, e.x * w.y > e.y * w.x);
        if (all(c) || all(not(c))) s *= -1.0;
    }
    return s * sqrt(d);
}

void main() {
    int kind = int(shape.x + 0.5);
    float s = shape.y;
    vec2 v[8];
    float d;
    if (kind == 0) {
        // arrow: the tip, a wing, the notch, the other wing
        v[0] = vec2(1.6, 0.0) * s; v[1] = vec2(-1.0, 0.8) * s; v[2] = vec2(-0.45, 0.0) * s; v[3] = vec2(-1.0, -0.8) * s;
        d = polygon(local, v, 4);
    } else if (kind == 1) {
        // kite: a diamond body and a forked tail meeting at a point
        v[0] = vec2(1.4, 0.0) * s; v[1] = vec2(0.0, 0.45) * s; v[2] = vec2(-1.1, 0.0) * s;
        v[3] = vec2(-1.7, 0.5) * s; v[4] = vec2(-1.7, -0.5) * s; v[5] = vec2(-1.1, 0.0) * s;
        v[6] = vec2(0.0, -0.45) * s;
        d = polygon(local, v, 7);
    } else if (kind == 2) {
        d = length(local) - s;
    } else {
        // line: a round-capped stroke from 1.5 sizes behind to half a size ahead
        vec2 a = vec2(-1.5 * s, 0.0), b = vec2(0.5 * s, 0.0);
        vec2 pa = local - a, ba = b - a;
        float hh = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
        d = length(pa - ba * hh) - shape.z;
    }
    float coverage = clamp(0.5 - d, 0.0, 1.0);
    float alpha = color.a * coverage;
    if (alpha <= 0.0) discard;
    outColor = vec4(color.rgb * alpha, alpha);
}
