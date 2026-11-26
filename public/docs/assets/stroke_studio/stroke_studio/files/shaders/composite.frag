#version 450
layout(binding=0) uniform sampler2D paintLayer;
layout(binding=1) uniform sampler2D mediumPaint;
layout(binding=2) uniform sampler2D broadPaint;
layout(push_constant) uniform CompositePush {
    vec4 selection; // green gain, blur mix, green reconstruction mix, second source G to B
    vec4 blendControls; // light-to-paint amount, crossover alpha, reciprocal render extent
} pc;
// CANVAS
// PAINT_GAMUT
// COMPOSITE_STACKSL
layout(location=0) out vec4 result;

void main() {
    vec2 uv = gl_FragCoord.xy * pc.blendControls.zw;
    vec4 sharp = texture(paintLayer, uv);
    vec4 medium = texture(mediumPaint, uv);
    vec4 broad = texture(broadPaint, uv);
    float selector = clamp(medium.g * pc.selection.x, 0.0, 1.0);
    vec3 weights = vec3(max(1.0 - 2.0*selector, 0.0),
                        1.0 - abs(2.0*selector - 1.0), max(2.0*selector - 1.0, 0.0));
    vec4 routed = sharp*weights.x + medium*weights.y + broad*weights.z;
    vec4 ink = mix(sharp, routed, pc.selection.y);
    float alpha = max(ink.a, 0.0);
    float coverage = 1.0 - exp(-alpha);
    vec3 weighted = ink.rgb * (coverage / max(alpha, 1e-5));
    float crossover = smoothstep(pc.blendControls.y, pc.blendControls.y + 0.55, alpha);
    vec3 rgb = mix(ink.rgb, weighted, pc.blendControls.x * crossover);
    float green = 0.5 * (rgb.r + mix(rgb.g, rgb.b, pc.selection.w));
    rgb.g = mix(rgb.g, green, pc.selection.z);
    vec3 edited = compositeToneFn(vec4(rgb, alpha)).rgb;
    if (any(isnan(edited)) || any(isinf(edited))) edited = rgb;
    result = vec4(paintGamut(canvas + edited), 1);
}
