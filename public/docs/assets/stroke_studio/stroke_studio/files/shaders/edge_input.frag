#version 450
layout(binding=0) uniform sampler2D paintLayer;
layout(binding=1) uniform sampler2D litLayer;
layout(binding=2) uniform sampler2D velocityLayer;
layout(binding=3) uniform sampler2D worldLayer;
layout(push_constant) uniform EdgeInputPush { float litMix; float sceneTime; } pc;
// CANVAS
// PAINT_GAMUT
// EDGE_STACKSL
layout(location=0) out vec4 edgeInput;
layout(location=1) out vec4 feedback;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec2 size = vec2(textureSize(litLayer, 0));
    vec2 uv = gl_FragCoord.xy / size;
    vec2 d = 0.25 / size;
    // Resolve the larger signed paint target onto the analysis grid without clamping RGB.
    feedback = (textureLod(paintLayer, uv + vec2(-d.x, -d.y), 0.0)
              + textureLod(paintLayer, uv + vec2( d.x, -d.y), 0.0)
              + textureLod(paintLayer, uv + vec2(-d.x,  d.y), 0.0)
              + textureLod(paintLayer, uv + vec2( d.x,  d.y), 0.0)) * 0.25;
    vec3 paint = paintGamut(canvas + feedback.rgb);
    vec3 lit = texelFetch(litLayer, pixel, 0).rgb;
    vec3 mixed = mix(paint, lit, pc.litMix);
    vec3 edited = edgeToneFn(vec4(mixed, feedback.a)).rgb;
    if (any(isnan(edited)) || any(isinf(edited))) edited = mixed;
    edgeInput = vec4(clamp(edited, 0.0, 1.0), 1.0);
}
