#version 450
// FIELD_STATS
layout(binding=0) uniform sampler2D accumulationImage; // packed stereo accumulation, before presentation
layout(push_constant) uniform MeterSketchPush {
    vec4 image; // width, height, stats channel, selected-pixel count
    vec4 sampling; // global pre-exposure gamma, deterministic sequence seed, reserved
} pc;
layout(local_size_x=32) in;
void main() {
    // One active lane records one pixel. The existing subgroup reducer therefore cannot average
    // unrelated pixels together before its centroid sketch. The other lanes leave without sampling.
    if(gl_LocalInvocationIndex!=0u) return;
    uint i=gl_WorkGroupID.x, n=uint(pc.image.w+.5), seed=uint(pc.sampling.y+.5);
    float u=(float(i)+.5)/float(n);
    float v=float(bitfieldReverse(i^(seed*0x9e3779b9u)))*2.3283064365386963e-10;
    ivec2 size=ivec2(pc.image.xy+.5);
    ivec2 p=clamp(ivec2(vec2(u,v)*vec2(size)),ivec2(0),size-1);
    vec3 rgb=texelFetch(accumulationImage,p,0).rgb;
    if(pc.sampling.x!=1) rgb=pow(max(rgb,vec3(0)),vec3(1/pc.sampling.x));
    float luminance=dot(rgb,vec3(.2126,.7152,.0722));
    if(!isnan(luminance)&&!isinf(luminance)) fieldStatsRecord(uint(pc.image.z+.5),luminance);
}
