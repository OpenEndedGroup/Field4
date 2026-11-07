#version 450
layout(location=0) noperspective in vec2 uv;
layout(location=1) flat in vec4 tint;
layout(location=2) flat in vec4 shape;
layout(location=0) out vec4 outColor;
void main() {
    float c=cos(shape.z),s=sin(shape.z);
    vec2 p=mat2(c,-s,s,c)*uv;
    p.x/=max(shape.w,.1);
    float d=length(p)-1;
    int form=int(shape.x+.5);
    if(form==1)d=abs(length(p)-(1-shape.y*.5))-shape.y*.5;
    if(form==2)d=(abs(p.x)+abs(p.y)-1)*.70710678;
    if(form==3) {
        float a=atan(p.y,p.x);
        d=length(p)-(.7+.3*cos(2*a));
    }
    float coverage=clamp(.5-d/max(fwidth(d),1e-5),0,1);
    // White ink; retain the marker's opacity and antialiased boundary.
    outColor=vec4(vec3(1.0),tint.a*coverage);
}
