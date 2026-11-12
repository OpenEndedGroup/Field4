#version 450
layout(location=0) flat in vec4 endpoints;
layout(location=1) flat in vec4 widthsArc;
layout(location=2) flat in vec4 colorA;
layout(location=3) flat in vec4 colorB;
layout(location=4) flat in vec4 ink;
layout(location=5) flat in vec4 limits;
layout(location=0) out vec4 outColor;
layout(location=6) in float backdropDistance;
void main() {
    if(backdropDistance<0)discard;
    // Empty reveal intervals must never leave a rounded cap behind.
    if(limits.y<=limits.x)discard;
    vec2 delta=endpoints.zw-endpoints.xy;
    float len=length(delta);
    if(len<1e-6)discard;
    vec2 axis=delta/len,q=gl_FragCoord.xy-endpoints.xy;
    float along=dot(q,axis),side=dot(q,vec2(-axis.y,axis.x));
    float t=clamp(along/len,0,1);
    // Perspective-correct world arclength; pixel-space coverage stays per-eye and spatial only.
    float worldT=(t/limits.w)/max((1-t)/limits.z+t/limits.w,1e-8);
    float arc=mix(widthsArc.z,widthsArc.w,worldT);
    float radius=mix(widthsArc.x,widthsArc.y,t);
    float sdf=abs(side)-radius;
    uint flags=uint(ink.w);
    float outside=max((flags&1u)!=0u?-along:0,(flags&2u)!=0u?along-len:0);
    if(outside>0)sdf=length(vec2(outside,side))-radius;
    float unitsPerPixel=max(fwidth(arc),1e-7);
    // Rounded reveal and dash ends use the same SDF as the outer edge.
    float trimOutside=max(limits.x-arc,arc-limits.y)/unitsPerPixel;
    // Reject distant unrevealed fragments before differentiating the cap SDF.
    // At tessellation joins its derivative can otherwise inflate AA into isolated dots.
    if(trimOutside>radius+1.5)discard;
    if(trimOutside>0)sdf=max(sdf,length(vec2(trimOutside,side))-radius);
    if(ink.x>1e-6) {
        float local=mod(arc+ink.z,ink.x)-ink.x*.5;
        float dashOutside=(abs(local)-ink.x*clamp(ink.y,.01,1)*.5)/unitsPerPixel;
        if(dashOutside>0)sdf=max(sdf,length(vec2(dashOutside,side))-radius);
    }
    float coverage=clamp(.5-sdf/max(fwidth(sdf),.75),0,1);
    // Grow sub-diameter reveals into a stroke instead of popping in a full cap-sized dot.
    coverage*=clamp((limits.y-limits.x)/unitsPerPixel/max(2*radius,1),0,1);
    vec4 c=mix(colorA,colorB,worldT);
    // White ink: structure comes from coverage, authored opacity and stroke width.
    outColor=vec4(vec3(1.0),c.a*coverage);
}
