{{ shadertoy.image }}

// Camera Input Demo: iChannel0 is the camera (ShaderTrinket's Camera button is the
// textures("iChannel0" to webcam()) line in this box's code). With no camera it samples black and
// you get a quiet prompt-like field.

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = fragCoord / iResolution.xy;

    // Mirror the camera so it behaves like a familiar preview.
    vec2 cameraUv = vec2(1.0 - uv.x, uv.y);
    vec3 camera = texture(iChannel0, cameraUv).rgb;
    float luma = dot(camera, vec3(0.299, 0.587, 0.114));

    vec2 p = (2.0 * fragCoord - iResolution.xy) / iResolution.y;
    float rings = 0.5 + 0.5 * sin(length(p) * 28.0 - iTime * 4.0 + luma * 5.0);
    float scan = smoothstep(0.92, 1.0, sin(uv.y * iResolution.y * 0.75 + iTime * 12.0));
    vec3 tint = mix(vec3(0.02, 0.16, 0.18), vec3(0.94, 0.72, 0.22), rings);

    // If the camera is off, iChannel0 samples black and you get a quiet prompt-like field.
    vec3 color = mix(camera, tint, 0.34);
    color += scan * vec3(0.16, 0.55, 0.52) * (0.25 + luma);
    color *= 0.78 + 0.55 * luma;
    fragColor = vec4(color, 1.0);
}
