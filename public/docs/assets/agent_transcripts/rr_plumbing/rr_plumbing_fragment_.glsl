#version 460

layout(set = 0, binding = 0) uniform sampler2D positions;
layout(set = 0, binding = 1) uniform sampler2D normals;
layout(set = 0, binding = 2) uniform sampler2D albedo;
layout(set = 0, binding = 3) uniform sampler2D velocity;

layout(location = 0) out float depth;
layout(location = 1) out vec2 motion;
layout(location = 2) out vec4 diffuse;
layout(location = 3) out vec4 specular;
layout(location = 4) out vec4 normalRoughness;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 positionAndDepth = texelFetch(positions, pixel, 0);

    // G-buffer alpha stores Vulkan depth in [0, 1]; 1 is the cleared background.
    depth = positionAndDepth.w;

    // Raster motion is currentUV - previousUV, unjittered and Y down.
    // Reconstruction wants current-to-previous motion in input pixels.
    vec2 uvMotion = texelFetch(velocity, pixel, 0).xy;
    vec2 inputSize = vec2(textureSize(velocity, 0));
    motion = -uvMotion * inputSize;

    vec3 worldNormal = texelFetch(normals, pixel, 0).xyz;
    bool valid = positionAndDepth.w < 1 && dot(worldNormal, worldNormal) > .01;
    diffuse = vec4(valid ? texelFetch(albedo, pixel, 0).rgb : vec3(0), 1);

    // Opaque Lambertian material: zero specular RGB and roughness 1 in normalRoughness.w.
    // Background uses a unit fallback normal instead of normalizing zero.
    specular = vec4(0, 0, 0, 1);
    normalRoughness = vec4(valid ? normalize(worldNormal) : vec3(0, 0, 1), 1);
}
