// Compact tracked world-space capsules. Include after buffer-reference extensions.
struct BodySegmentRecord {
    vec4 a; // world start XYZ, preferred radius in metres
    vec4 b; // world end XYZ, valid flag
};
layout(std430, buffer_reference, buffer_reference_align=16) readonly buffer BodySegmentData {
    BodySegmentRecord segment[64]; // stable IDs; tracking loss clears the valid flag
    uvec4 activeIds[16]; // compact valid IDs, four per word
};
layout(std430, buffer_reference, buffer_reference_align=4) buffer BodyChoiceData {
    uint choice[]; // previous nearest stable segment ID per particle
};
layout(std140, set=0, binding=20) uniform BodyField {
    mat4 worldFromLocal; // current cloud transform
    mat4 localFromWorld; // inverse transform for the returned force
    BodySegmentData geometry; // small per-frame host upload
    BodyChoiceData choices; // persistent four-byte choice per particle
    vec4 state; // particle count, enabled, active segment count, reserved
    vec4 gains; // outside pull, inside push, outside reach metres, falloff exponent
    vec4 options; // extra standoff metres, body-force magnitude cap, reserved
} bodyField;

vec4 gBodyForce; // resulting local force XYZ, zero when disabled/untracked
vec4 gBodyNormal; // outward WORLD unit normal XYZ
vec4 gBodyPoint; // selected standoff point in WORLD XYZ
float gBodyDistance; // signed metres to the selected capsule surface
float gBodySegment; // stable ID, -1 when no valid candidate exists

uint bodyActiveId(uint index) { return bodyField.geometry.activeIds[index/4u][index%4u]; }
vec4 bodySegmentSample(uint id, vec3 world, out vec3 centre) {
    BodySegmentRecord segment=bodyField.geometry.segment[id];
    vec3 axis=segment.b.xyz-segment.a.xyz;
    float squared=dot(axis,axis);
    float t=squared>1e-12?clamp(dot(world-segment.a.xyz,axis)/squared,0.0,1.0):0.0;
    centre=segment.a.xyz+t*axis;
    vec3 radial=world-centre;
    float distance=length(radial);
    vec3 normal;
    if(distance>1e-6)normal=radial/distance;
    else if(squared>1e-12){
        axis*=inversesqrt(squared);
        vec3 basis=abs(axis.x)<0.9?vec3(1,0,0):vec3(0,1,0);
        normal=normalize(cross(axis,basis));
    }else normal=vec3(1,0,0);
    float radius=max(segment.a.w+bodyField.options.x,0.0);
    return vec4(normal,distance-radius);
}

// Re-evaluate the old winner and one rotating proposal. No all-bones loop per particle.
vec3 bodySegmentForce(uint particle, uint activeTick, vec3 local) {
    gBodyForce=vec4(0);gBodyNormal=vec4(0);gBodyPoint=vec4(0);
    gBodyDistance=0.0;gBodySegment=-1.0;
    uint count=min(uint(bodyField.state.z),64u);
    if(bodyField.state.y<0.5||count==0u||particle>=uint(bodyField.state.x))return vec3(0);
    vec3 world=(bodyField.worldFromLocal*vec4(local,1)).xyz;
    uint previous=bodyField.choices.choice[particle];
    uint phase=(activeTick+particle*1664525u)%count;
    uint candidate=bodyActiveId(phase);
    if(candidate==previous&&count>1u)candidate=bodyActiveId((phase+1u)%count);
    if(candidate>=64u)return vec3(0);
    vec3 centre;
    vec4 best=bodySegmentSample(candidate,world,centre);
    uint winner=candidate;
    if(previous<64u&&previous!=candidate&&bodyField.geometry.segment[previous].b.w>0.5){
        vec3 oldCentre;
        vec4 old=bodySegmentSample(previous,world,oldCentre);
        if(old.w<=best.w){best=old;centre=oldCentre;winner=previous;}
    }
    bodyField.choices.choice[particle]=winner;
    float radius=max(bodyField.geometry.segment[winner].a.w+bodyField.options.x,0.0);
    gBodySegment=float(winner);gBodyDistance=best.w;
    gBodyNormal=vec4(best.xyz,0);gBodyPoint=vec4(centre+best.xyz*radius,1);
    float outside=max(best.w,0.0);
    float weight=outside==0.0?1.0:(bodyField.gains.z<=0.0||outside>=bodyField.gains.z?0.0:
        pow(max(1.0-outside/bodyField.gains.z,0.0),max(bodyField.gains.w,0.0)));
    float magnitude=-best.w*(best.w>0.0?bodyField.gains.x:bodyField.gains.y)*weight;
    if(bodyField.options.y>0.0)magnitude=clamp(magnitude,-bodyField.options.y,bodyField.options.y);
    vec3 result=(bodyField.localFromWorld*vec4(best.xyz*magnitude,0)).xyz;
    gBodyForce=vec4(result,0);
    return result;
}
