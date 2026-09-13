#version 450
layout(location=0) in vec2 uv;
layout(location=0) out vec4 result;
layout(set=0,binding=0) uniform sampler2D paintSource;
layout(set=0,binding=1) uniform sampler2D paintMedium;
layout(set=0,binding=2) uniform sampler2D paintBroad;
layout(push_constant) uniform Display { vec4 displayControl; } pc;
void main() {
    vec4 sharp = texture(paintSource,uv);
    vec4 mid = texture(paintMedium,uv);
    float energy = dot(sharp,vec4(1));
    float c = sharp.r + mid.g + mid.b + texture(paintBroad,uv).a;
    if (pc.displayControl.y > 1.5)
        c = energy>0.000001 ? dot(sharp,vec4(0,4,10,24))/(24.0*energy) : 0.0;
    else if (pc.displayControl.y > 0.5) c = energy;
    result = vec4(vec3(c*pc.displayControl.x),0);
}
