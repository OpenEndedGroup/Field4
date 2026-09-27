#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_buffer_reference : require
layout(buffer_reference,std430,buffer_reference_align=16) buffer Pixels {vec4 data[];};
layout(buffer_reference,std430,buffer_reference_align=16) readonly buffer Positions {vec4 data[];};
layout(buffer_reference,std430,buffer_reference_align=4) readonly buffer Indices {uint data[];};
layout(push_constant) uniform Frame {Pixels pixels;Positions positions;Indices indices;float time;int view;} pc;
layout(location=0) rayPayloadInEXT vec4 result;
hitAttributeEXT vec2 bary;
void main(){vec3 p=gl_WorldRayOriginEXT+gl_HitTEXT*gl_WorldRayDirectionEXT;
    uint cluster=gl_GeometryIndexEXT,base=cluster*81,tri=cluster*384+uint(gl_PrimitiveID)*3;
    vec3 a=pc.positions.data[base+pc.indices.data[tri]].xyz;
    vec3 b=pc.positions.data[base+pc.indices.data[tri+1]].xyz;
    vec3 c=pc.positions.data[base+pc.indices.data[tri+2]].xyz;
    vec3 normal=normalize(cross(b-a,c-a));
    if(dot(normal,gl_WorldRayDirectionEXT)>0)normal=-normal;
    vec3 ink=mix(vec3(.02,.13,.3),vec3(.85,.22,.045),clamp(p.y+.5,0,1));
    if(pc.view==1)ink=.3+.3*cos(float(gl_GeometryIndexEXT)*2.39996+vec3(0,2,4));
    float edge=smoothstep(.0,.04,min(min(bary.x,bary.y),1-bary.x-bary.y));
    result=vec4(ink*(.2+.8*max(dot(normal,normalize(vec3(-2,4,1))),0))*(pc.view==2?mix(.35,1,edge):1),1);
}
