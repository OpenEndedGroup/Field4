#version 450
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_multiview : require
// VIEWS
layout(std430,buffer_reference) readonly buffer FrostShape {
    uvec4 counts; // exposed edges, padded rectlets, reserved
    vec4 pieces[]; // edge XYXY records followed by rectlet LBR T records
};
layout(push_constant) uniform FrostPush {
    mat4 model; // panel-local metres to current world pose
    FrostShape shape; // exact cached union used by the outline and sliding endpoints
    vec4 bounds; // local left, bottom, right, top
    vec4 settings; // opacity, brightness, sigma, scene scale
} pc;
layout(location=0) out vec2 localPoint;
const vec2 corners[6]=vec2[6](vec2(0,0),vec2(1,0),vec2(1,1),vec2(0,0),vec2(1,1),vec2(0,1));
void main() {
    mat4 m=(gl_ViewIndex==0?stereoVpL:stereoVpR)*pc.model;
    for(int i=0;i<4;i++) {
        vec4 c=m*vec4(mix(pc.bounds.xy,pc.bounds.zw,vec2(i&1,(i>>1)&1)),0,1);
        if(c.w<=1e-5||c.z<=0) { gl_Position=vec4(0,0,2,1); return; }
    }
    vec2 corner=corners[gl_VertexIndex%6];
    vec2 q=mix(pc.bounds.xy,pc.bounds.zw,corner),normal=corner*2-1;
    vec4 position=m*vec4(q,0,1),direction=m*vec4(normalize(normal),0,0);
    vec2 viewport=stereoProjection().zw*pc.settings.w;
    float s=position.w,t=direction.w;
    float denominator=length(viewport*(s*direction.xy-t*position.xy))-s*t;
    q+=normal*(denominator>1e-12?s*s/denominator:0)*2;
    localPoint=q;
    gl_Position=m*vec4(q,0,1);
}
