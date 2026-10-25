#version 460
layout(location=0) in vec2 uv;
layout(location=0) out vec4 color;
layout(binding=0) uniform sampler2D source;
layout(binding=1) uniform sampler2D neural;
layout(push_constant) uniform Compare {
    vec4 grade;
    // xy = output width/height; z = heading height in pixels; w = neural pass available.
    vec4 dimensions;
} pc;

// 5x7 bitmap lettering for the three small panel headings, rendered by Field too.
const uint S[7]=uint[](15,16,16,14,1,1,30);
const uint O[7]=uint[](14,17,17,17,17,17,14);
const uint U[7]=uint[](17,17,17,17,17,17,14);
const uint R[7]=uint[](30,17,17,30,20,18,17);
const uint C[7]=uint[](14,17,16,16,16,17,14);
const uint E[7]=uint[](31,16,16,30,16,16,31);
const uint N[7]=uint[](17,25,25,21,19,19,17);
const uint G[7]=uint[](14,17,16,23,17,17,15);
const uint A[7]=uint[](14,17,17,31,17,17,17);
const uint D[7]=uint[](30,17,17,17,17,17,30);
uint letter(int panel,int column,int row) {
    if(panel==0) {
        if(column==0)return S[row];
        if(column==1)return O[row];
        if(column==2)return U[row];
        if(column==3)return R[row];
        if(column==4)return C[row];
        if(column==5)return E[row];
    }
    else if(panel==1) {
        if(column==0)return N[row];
        if(column==1)return R[row];
    }
    else {
        if(column==0)return G[row];
        if(column==1)return R[row];
        if(column==2)return A[row];
        if(column==3)return D[row];
        if(column==4)return E[row];
    }
    return 0u;
}
void main() {
    vec2 pixel=uv*pc.dimensions.xy;
    int panel=min(2,int(uv.x*3));
    float x=fract(uv.x*3)*pc.dimensions.x/3;
    float header=pc.dimensions.z;
    if(pixel.y<header) {
        ivec2 p=ivec2(floor((vec2(x,pixel.y)-vec2(24,20))/3));
        bool glyph=false;
        if(p.x>=0 && p.y>=0 && p.y<7 && p.x/6<6 && p.x%6<5)
        glyph=(letter(panel==1 && pc.dimensions.w==0?0:panel,p.x/6,p.y)&(1u<<uint(4-p.x%6)))!=0;
        color=vec4(glyph?vec3(.8,.86,.9):vec3(.012,.019,.027),1);
        return;
    }
    vec2 st=vec2(fract(uv.x*3),(pixel.y-header)/(pc.dimensions.y-header));
    vec3 rgb=panel==0?texture(source,st).rgb:texture(neural,st).rgb;
    if(panel==2)rgb*=pc.grade.rgb;
    if(x<2)rgb=vec3(.012,.019,.027);
    color=vec4(rgb,1);
}
