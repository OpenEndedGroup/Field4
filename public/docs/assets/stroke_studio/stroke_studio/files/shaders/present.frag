#version 450
layout(binding=0) uniform sampler2D litLayer;
layout(binding=1) uniform sampler2D worldLayer;
layout(binding=2) uniform sampler2D previousLayer;
layout(binding=3) uniform sampler2D directionLayer;
layout(binding=4) uniform sampler2D paintLayer;
layout(binding=5) uniform sampler2D diagnosticLayer;
layout(binding=6) uniform sampler2D compositeLayer;
layout(binding=7) uniform sampler2D velocityLayer;
layout(binding=8) uniform sampler2D edgeColor;
layout(binding=10) uniform sampler2D summaryValue;
layout(binding=11) uniform sampler2D summaryMoment;
layout(binding=12) uniform sampler2D summaryExtent;
layout(binding=13) uniform sampler2D mediumPaint;
layout(binding=14) uniform sampler2D broadPaint;
layout(push_constant) uniform PresentPush {
    vec4 camera;
    vec4 scene;
    vec4 viewport; // window width, height, source aspect, view mode
    float feedback;
} pc;
// CAMERA
// CANVAS
// PAINT_GAMUT
layout(location=0) out vec4 color;
const vec3 luma = vec3(0.2126, 0.7152, 0.0722);
vec3 orientation(vec2 direction) {
    float angle = atan(direction.y, direction.x);
    return 0.52 + 0.48 * cos(angle + vec3(0, 2.094395, 4.188790));
}
vec3 atlas(int mode, int panel, vec2 uv) {
    vec3 lit = texture(litLayer, uv).rgb;
    vec4 world = texture(worldLayer, uv);
    if (mode == 7) {
        if (panel == 0) return lit;
        if (panel == 3) {
            vec4 velocity = texture(velocityLayer, uv);
            return orientation(velocity.xy) * clamp(velocity.w / 80.0, 0.0, 1.0);
        }
        vec3 position = panel == 1 ? world.xyz : texture(previousLayer, uv).xyz;
        float range = world.w > 1.0 ? 2.2 : 18.0;
        return 0.15 + 0.60 * clamp(0.5 + position / (2.0 * range), 0.0, 1.0);
    }
    vec3 pigment = texture(paintLayer, uv).rgb;
    if (mode == 8) {
        if (panel == 0) return paintGamut(canvas + pigment);
        if (panel == 1) return texture(edgeColor, uv).rgb;
        if (panel == 2) {
            vec3 field = texture(directionLayer, uv).xyz;
            return field.z * orientation(field.xy);
        }
        float error = dot(lit - canvas - pigment * pc.feedback, luma);
        return 0.012 + 3.0 * (max(0, error) * vec3(1,0.32,0.07) + max(0,-error) * vec3(0.07,0.38,1));
    }
    if (mode == 9) {
        ivec2 size = textureSize(summaryValue, 0);
        ivec2 cell = ivec2(uv * size);
        vec2 local = fract(uv * size);
        vec4 value = texelFetch(summaryValue, cell, 0);
        vec4 extent = texelFetch(summaryExtent, cell, 0);
        if (panel == 0) return vec3(value.y * 1.8);
        if (panel == 1) return vec3(0.28, 0.65, 1.0) * (extent.z - extent.y) * 4.0;
        if (panel == 2) return vec3(1.0, 0.61, 0.22) * sqrt(max(0.0, value.z)) * 11.0;
        vec4 moment = texelFetch(summaryMoment, cell, 0);
        vec2 mean = moment.xy / max(value.x, 1e-8);
        vec2 variance = max(vec2(0), moment.zw / max(value.x, 1e-8) - mean * mean);
        float covariance = extent.x / max(value.x, 1e-8) - mean.x * mean.y;
        vec2 eigen = vec2(variance.x - variance.y, 2.0 * covariance);
        float angle = 0.5 * atan(eigen.y, eigen.x);
        vec2 tangent = normalize(vec2(cos(angle), sin(angle)) * size);
        vec2 delta = local - (mean * size - cell);
        float weight = clamp(sqrt(max(0.0, value.z)) * 14.0, 0.0, 1.0);
        float line = (1.0 - smoothstep(0.05, 0.12, abs(dot(delta, vec2(-tangent.y, tangent.x)))))
            * (1.0 - smoothstep(0.30, 0.44, abs(dot(delta, tangent))));
        return vec3(value.y * 0.4) + orientation(eigen) * line * weight;
    }
    if (panel == 0) return paintGamut(canvas + pigment);
    if (panel == 1) return paintGamut(canvas + texture(mediumPaint, uv).rgb);
    if (panel == 2) return paintGamut(canvas + texture(broadPaint, uv).rgb);
    return texture(compositeLayer, uv).rgb;
}
void main() {
    vec2 uv = gl_FragCoord.xy / pc.viewport.xy;
    float aspect = pc.viewport.x / pc.viewport.y;
    vec2 scale = aspect > pc.viewport.z ? vec2(aspect / pc.viewport.z, 1) : vec2(1, pc.viewport.z / aspect);
    uv = (uv - 0.5) * scale + 0.5;
    if (any(lessThan(uv, vec2(0))) || any(greaterThanEqual(uv, vec2(1)))) {
        color = vec4(canvas, 1);
        return;
    }
    vec3 target = texture(litLayer, uv).rgb;
    vec4 p = texture(paintLayer, uv);
    vec4 w = texture(worldLayer, uv);
    vec3 rgb = texture(compositeLayer, uv).rgb;
    int mode = int(pc.viewport.w + 0.5);
    if (mode >= 7) {
        ivec2 quadrant = ivec2(uv * 2.0);
        vec2 tile = fract(uv * 2.0);
        if (any(lessThan(tile, vec2(0.004))) || any(greaterThan(tile, vec2(0.996)))) {
            color = vec4(0.035, 0.040, 0.050, 1.0);
        } else {
            rgb = atlas(mode, quadrant.x + quadrant.y * 2, tile);
            color = vec4(clamp(rgb, 0.0, 1.0), 1.0);
        }
        return;
    }
    if (mode == 1) rgb = target;
    if (mode == 2) {
        vec3 d = texture(directionLayer, uv).xyz;
        float angle = atan(d.y, d.x);
        rgb = d.z * (0.52 + 0.48 * cos(angle + vec3(0, 2.0944, 4.1888)));
    }
    if (mode == 3) {
        float error = dot(target - canvas - pc.feedback * p.rgb, vec3(0.2126, 0.7152, 0.0722));
        rgb = vec3(0.07) + 3.0 * (max(0, error) * vec3(1,0.32,0.07) + max(0,-error) * vec3(0.07,0.38,1));
        if (w.w <= 1.0) rgb = vec3(0.0);
    }
    if (mode == 4 || mode == 6) {
        vec4 states = texture(diagnosticLayer, uv);
        rgb = mode == 4 ? 0.025 + states.rgb * 0.65 : 0.025 + states.w * vec3(1.0,0.17,0.44);
    }
    if (mode == 5) {
        ivec2 pixel = ivec2(uv * pc.scene.zw);
        vec4 current = texelFetch(worldLayer, pixel, 0);
        vec4 previous = texelFetch(previousLayer, pixel, 0);
        vec2 flow = worldPixel(current.xyz) - worldPixel(previous.xyz);
        rgb = vec3(0.15) + 0.24 * vec3(flow.x, -flow.y, -flow.x);
    }
    color = vec4(clamp(rgb, 0.0, 1.0), 1.0);
}
