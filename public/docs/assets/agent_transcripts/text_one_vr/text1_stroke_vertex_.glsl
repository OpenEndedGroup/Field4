#version 450
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_multiview : require
struct Segment {
    vec4 previous;
    vec4 a;
    vec4 b;
    vec4 next;
    vec4 colorA;
    vec4 colorB;
    vec4 ink;
    vec4 trim;
};
layout(std430,buffer_reference) readonly buffer DiagramSegments {
    Segment item[];
};
layout(push_constant) uniform DiagramPush {
    mat4 model; // diagram-local to world, shared by both eyes
    DiagramSegments diagramData; // CPU-authored centreline samples, never eye-dependent triangles
    vec4 options; // viewport width/height, minimum stroke/marker radius in pixels
} pc;
#extension GL_EXT_multiview : require
layout(std140, set=0, binding=7) uniform StereoViews {
    mat4 stereoVpL; // left-eye projection/view
    mat4 stereoVpR; // right-eye projection/view
    vec4 stereoRightL; // left-eye camera right
    vec4 stereoRightR; // right-eye camera right
    vec4 stereoUpL; // left-eye camera up
    vec4 stereoUpR; // right-eye camera up
    vec4 stereoProjectionL; // projection XY and viewport size
    vec4 stereoProjectionR; // projection XY and viewport size
};

mat4 stereoViewProjection() {
    return gl_ViewIndex==0 ? stereoVpL : stereoVpR;
}
vec3 stereoCameraRight() {
    return (gl_ViewIndex==0 ? stereoRightL : stereoRightR).xyz;
}
vec3 stereoCameraUp() {
    return (gl_ViewIndex==0 ? stereoUpL : stereoUpR).xyz;
}
vec4 stereoProjection() {
    return gl_ViewIndex==0 ? stereoProjectionL : stereoProjectionR;
}
layout(location=0) flat out vec4 endpoints;
layout(location=1) flat out vec4 widthsArc;
layout(location=2) flat out vec4 colorA;
layout(location=3) flat out vec4 colorB;
layout(location=4) flat out vec4 ink;
layout(location=5) flat out vec4 limits;
vec2 pixel(vec4 q) {
    return vec2(q.x/q.w*.5+.5,.5-q.y/q.w*.5)*pc.options.xy;
}
vec2 unit(vec2 p,vec2 fallback) {
    return dot(p,p)>1e-10?normalize(p):fallback;
}
const vec2 corners[6]=vec2[6](vec2(0,-1),vec2(1,-1),vec2(1,1),vec2(0,-1),vec2(1,1),vec2(0,1));
void main() {
    Segment s=pc.diagramData.item[gl_VertexIndex/6];
    // Do not rasterize unrevealed tessellation segments. Clamped arclength derivatives
    // at their joins can otherwise turn distant AA tails into a dotted preview.
    float capPadding=max(s.previous.w,s.next.w)+.0001;
    if(s.trim.y<=s.trim.x||s.a.w>s.trim.y+capPadding||s.b.w<s.trim.x-capPadding) {
        gl_Position=vec4(0,0,2,1);
        return;
    }
    mat4 vp=gl_ViewIndex==0?stereoVpL:stereoVpR;
    vec4 projection=gl_ViewIndex==0?stereoProjectionL:stereoProjectionR;
    mat4 m=vp*pc.model;
    vec4 a=m*vec4(s.a.xyz,1),b=m*vec4(s.b.xyz,1);
    // Clip the centreline before division; never turn a behind-eye endpoint into a giant ribbon.
    float enter=0,leave=1;
    float da=a.z,db=b.z;
    if(da<0&&db<0) {
        gl_Position=vec4(0,0,2,1);
        return;
    }
    if(da<0)enter=max(enter,-da/(db-da));
    if(db<0)leave=min(leave,da/(da-db));
    if(enter>=leave||max(a.w,b.w)<1e-6) {
        gl_Position=vec4(0,0,2,1);
        return;
    }
    vec4 ca=mix(a,b,enter),cb=mix(a,b,leave);
    ca.w=max(ca.w,1e-6);
    cb.w=max(cb.w,1e-6);
    vec2 pa=pixel(ca),pb=pixel(cb),axis=unit(pb-pa,vec2(1,0));
    vec4 prev=m*vec4(s.previous.xyz,1),next=m*vec4(s.next.xyz,1);
    vec2 before=prev.w>1e-6&&enter==0?unit(pa-pixel(prev),axis):axis;
    vec2 after=next.w>1e-6&&leave==1?unit(pixel(next)-pb,axis):axis;
    vec2 normal=vec2(-axis.y,axis.x);
    vec2 ma=unit(vec2(-before.y,before.x)+normal,normal);
    vec2 mb=unit(vec2(-after.y,after.x)+normal,normal);
    ma/=max(dot(ma,normal),.25);
    mb/=max(dot(mb,normal),.25);
    float focal=abs(projection.y)*pc.options.y*.5;
    float modelScale=length(pc.model[0].xyz);
    float ra=max(mix(s.previous.w,s.next.w,enter)*modelScale*focal/ca.w,pc.options.z);
    float rb=max(mix(s.previous.w,s.next.w,leave)*modelScale*focal/cb.w,pc.options.z);
    vec2 corner=corners[gl_VertexIndex%6];
    bool atEnd=corner.x>.5;
    vec4 clip=atEnd?cb:ca;
    vec2 p=atEnd?pb:pa;
    p+=(atEnd?mb:ma)*corner.y*((atEnd?rb:ra)+1.5);
    uint flags=uint(s.ink.w);
    if(!atEnd&&((flags&1u)!=0u||enter>0))p-=axis*(ra+1.5);
    if(atEnd&&((flags&2u)!=0u||leave<1))p+=axis*(rb+1.5);
    clip.xy=vec2(p.x/pc.options.x*2-1,1-p.y/pc.options.y*2)*clip.w;
    gl_Position=clip;
    endpoints=vec4(pa,pb);
    widthsArc=vec4(ra,rb,mix(s.a.w,s.b.w,enter),mix(s.a.w,s.b.w,leave));
    colorA=mix(s.colorA,s.colorB,enter);
    colorB=mix(s.colorA,s.colorB,leave);
    ink=s.ink;
    limits=vec4(s.trim.xy,ca.w,cb.w);
}
