// Image coordinates have their origin at the top left. All positions are in scene units.
vec3 forwardAxis() { return normalize(vec3(0.0, 0.03, 0.0) - pc.camera.xyz); }
vec3 rightAxis() { return normalize(cross(forwardAxis(), vec3(0, 1, 0))); }
vec3 upAxis() { return cross(rightAxis(), forwardAxis()); }
vec3 eyeRay(vec2 pixel) {
    vec2 ndc = (pixel / pc.scene.zw) * 2.0 - 1.0;
    return normalize(forwardAxis() + pc.camera.w *
        (rightAxis() * ndc.x * pc.scene.z / pc.scene.w - upAxis() * ndc.y));
}
vec4 worldClip(vec3 world) {
    vec3 q = world - pc.camera.xyz;
    float z = dot(q, forwardAxis());
    return vec4(dot(q, rightAxis()) / (pc.camera.w * pc.scene.z / pc.scene.w),
                dot(q, upAxis()) / pc.camera.w, z * 0.5, z);
}
vec2 worldPixel(vec3 world) {
    vec4 clip = worldClip(world);
    return (clip.xy / max(0.0001, clip.w) * vec2(0.5, -0.5) + 0.5) * pc.scene.zw;
}
float pixelFootprint(vec3 world) {
    return 2.0 * pc.camera.w * max(0.01, worldClip(world).w) / pc.scene.w;
}
bool onImage(vec2 p) {
    return all(greaterThanEqual(p, vec2(0))) && all(lessThan(p, pc.scene.zw));
}
