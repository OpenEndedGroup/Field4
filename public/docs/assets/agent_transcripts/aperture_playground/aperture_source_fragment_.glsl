#version 450
layout(location=0) in vec2 uv;
layout(location=0) out vec4 result;
layout(push_constant) uniform Source { vec4 look; vec4 size; } pc;
float capsule(vec2 p, vec2 a, vec2 b) {
    vec2 d=b-a;
    return length(p-a-d*clamp(dot(p-a,d)/dot(d,d),0.0,1.0));
}
vec4 bands(float radius) {
    if(radius<4.0) return mix(vec4(1,0,0,0),vec4(0,1,0,0),radius/4.0);
    if(radius<10.0) return mix(vec4(0,1,0,0),vec4(0,0,1,0),(radius-4.0)/6.0);
    return mix(vec4(0,0,1,0),vec4(0,0,0,1),clamp((radius-10.0)/14.0,0.0,1.0));
}
void main() {
    vec2 p=uv*pc.size.xy;
    float row=floor(uv.y*4.0);
    float y=(row+.5)*pc.size.y/4.0;
    float d=capsule(p,vec2(pc.size.x*.12,y-32),vec2(pc.size.x*.70,y+32));
    float line=1.0-smoothstep(.3,1.3,d);
    float dots=1.0-smoothstep(7.0,8.0,length(p-vec2(pc.size.x*.82,y)));
    float radius=clamp(pc.look.x*row/3.0,0.0,24.0);
    result=bands(radius)*(line+dots)*pc.look.y;
}
