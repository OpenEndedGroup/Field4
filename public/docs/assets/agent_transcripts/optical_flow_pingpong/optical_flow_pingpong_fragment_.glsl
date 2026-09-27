#version 460
layout(location=0) out vec4 color;
layout(push_constant) uniform Frame { float time; } frame;
// Optical flow follows brightness. Hue changes alone did not disambiguate the old
// repeating checkerboard: neighboring cells could produce convincing wrong matches.
float brightnessKey(ivec2 cell) {
    uvec2 c=uvec2(cell);
    uint h=c.x*1597334677u ^ c.y*3812015801u;
    h=(h ^ (h>>16u))*2246822519u;
    h=(h ^ (h>>13u))*3266489917u;
    return float((h ^ (h>>16u)) & 65535u)/65535.0;
}
float textureKey(vec2 p) {
    vec2 q=p/11.0, f=fract(q);
    ivec2 c=ivec2(floor(q));
    f=f*f*(3.0-2.0*f);
    return mix(mix(brightnessKey(c),brightnessKey(c+ivec2(1,0)),f.x),
               mix(brightnessKey(c+ivec2(0,1)),brightnessKey(c+ivec2(1,1)),f.x),f.y);
}
void main() {
    vec2 p=gl_FragCoord.xy-vec2(sin(frame.time)*180,cos(frame.time*1.3)*120);
    vec2 cell=floor(p/34),local=fract(p/34)-.5;
    float seed=brightnessKey(ivec2(cell));
    vec3 pigment=.35+.3*cos(seed*6.283+vec3(0,2.094,4.189));
    float disk=1-smoothstep(.20+.12*seed,.24+.12*seed,length(local));
    // A fixed, aperiodic luminance texture moves with the circles, even in the gaps.
    vec3 background=vec3(.035+.26*textureKey(p));
    pigment*=.5+brightnessKey(ivec2(cell)+ivec2(71,103));
    color=vec4(mix(background,pigment,disk),1);
}
