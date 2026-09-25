#version 460
layout(push_constant) uniform P { vec4 state; } p;
layout(location=0) out vec4 color;
void main() {
    // This is the teacher. Edit it here; there is no Python version to keep in sync.
    vec2 xy=gl_FragCoord.xy/p.state.xy*2-1;
    float t=p.state.z;
    vec3 rgb=.5+.22*cos(vec3(xy.x*2+t,xy.y*2-t*.7,xy.x+xy.y+t*.5)+vec3(0,2,4));
    for(int k=1;k<=96;k++) {
        float a=float(k)*.61803399;
        rgb+=.028/float(k)*sin(vec3(xy.x*cos(a)+xy.y*sin(a))*3+t+vec3(0,2,4)+a);
    }
    color=vec4(rgb,1);
}
