#version 450
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_multiview : require
struct Marker {
    vec4 position; // diagram-local XYZ and radius in metres
    vec4 color; // linear RGBA before score-driven opacity
    vec4 shape; // SDF kind, ring width, rotation and aspect
};
layout(std430,buffer_reference) readonly buffer DiagramMarkers {
    Marker item[]; // CPU-uploaded 48-byte records, shared by both eyes
};
layout(push_constant) uniform DiagramPush {
    mat4 model; // diagram-local to world, shared by both eyes
    DiagramMarkers diagramData; // position/radius, linear RGBA and SDF parameters
    vec4 options; // viewport width/height, minimum stroke/marker radius in pixels
    vec4 clipPlane; // world-space backdrop clipping, zero for the main scene
} pc;
// VIEWS
layout(location=0) noperspective out vec2 uv;
layout(location=1) flat out vec4 tint;
layout(location=2) flat out vec4 shape;
const vec2 corners[6]=vec2[6](vec2(-1,-1),vec2(1,-1),vec2(1,1),vec2(-1,-1),vec2(1,1),vec2(-1,1));
layout(location=3) out float backdropDistance;
void main() {
    backdropDistance=0;
    Marker p=pc.diagramData.item[gl_VertexIndex/6];
    mat4 vp=gl_ViewIndex==0?stereoVpL:stereoVpR;
    vec4 projection=gl_ViewIndex==0?stereoProjectionL:stereoProjectionR;
    vec4 clip=vp*pc.model*vec4(p.position.xyz,1);
    if(clip.z<0||clip.w<1e-6) {
        gl_Position=vec4(0,0,2,1);
        return;
    }
    float radius=max(p.position.w*length(pc.model[0].xyz)*abs(projection.y)*pc.options.y*.5/clip.w,pc.options.w);
    uv=corners[gl_VertexIndex%6]*(1+2/max(radius,1e-5));
    clip.xy+=uv*radius*vec2(2,-2)/pc.options.xy*clip.w;
    gl_Position=clip;
    backdropDistance=dot(pc.clipPlane,inverse(vp)*clip);
    tint=p.color;
    shape=p.shape;
}
