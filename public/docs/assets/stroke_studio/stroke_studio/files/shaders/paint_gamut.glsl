// Map out-of-gamut additive paint at constant luminance when luminance itself fits.
vec3 paintGamut(vec3 rgb) {
    float luminance = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    float light = clamp(luminance, 0.0, 1.0);
    vec3 chroma = rgb - vec3(luminance);
    float negative = max(0.0, -min(chroma.r, min(chroma.g, chroma.b)));
    float positive = max(0.0, max(chroma.r, max(chroma.g, chroma.b)));
    float fit = min(1.0, min(light / max(negative, 1e-8), (1.0 - light) / max(positive, 1e-8)));
    return vec3(light) + chroma * pow(fit, 1.25);
}
