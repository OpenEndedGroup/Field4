#version 450
layout(push_constant) uniform Sprite { vec4 size; vec4 look; } pc;
layout(location=0) out vec2 local;
layout(location=1) flat out vec2 appearance;
const vec2 corners[6] = vec2[6](vec2(-1,-1),vec2(1,-1),vec2(1,1),vec2(-1,-1),vec2(1,1),vec2(-1,1));
void main() {
    float row=float(gl_VertexIndex/6);
    appearance=vec2(1.0,pc.look.x*row/3.0/8.0);
    local=corners[gl_VertexIndex%6]*(1.0+appearance.y);
    vec2 p=vec2(pc.size.x*.93,pc.size.y*(row+.5)/4.0)+local*8.0;
    vec2 ndc=p/pc.size.xy*2.0-1.0;
    gl_Position=vec4(ndc.x,-ndc.y,0,1);
}
