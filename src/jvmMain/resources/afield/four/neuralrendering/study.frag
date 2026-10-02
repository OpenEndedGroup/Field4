#version 460
layout(location=0) in vec2 uv;
layout(location=0) out vec4 color;
layout(push_constant) uniform Study { vec4 frame; } pc;

float hash(vec2 p) {
    return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);
}
float noise(vec2 p) {
    vec2 i=floor(p), f=fract(p);
    f=f*f*(3-2*f);
    return mix(mix(hash(i),hash(i+vec2(1,0)),f.x),
               mix(hash(i+vec2(0,1)),hash(i+vec2(1)),f.x),f.y);
}
vec3 linearize(vec3 c) {
    return mix(c/12.92,pow((c+.055)/1.055,vec3(2.4)),step(vec3(.04045),c));
}
void main() {
    float time=pc.frame.x;
    vec2 p=(uv-.5)*2;
    float grain=noise(uv*pc.frame.yz*.7);
    vec3 paper=vec3(.88,.85,.77)+.025*(grain-.5);
    float ink=0;
    // Three breathing, imperfect loops. The source is drawn here, in Field, every frame.
    for(int i=0;i<3;i++) {
        float k=float(i);
        vec2 q=p-vec2(.035*sin(time*.6+k*2),.035*cos(time*.7+k));
        q=mat2(cos(k*.8),sin(k*.8),-sin(k*.8),cos(k*.8))*q;
        q.y*=1.05+.16*sin(time*.3+k);
        float a=atan(q.y,q.x), r=length(q);
        float radius=.27+k*.22+.025*sin(a*3+time*.55+k*2)+.018*sin(a*7-time*.3+k);
        float rough=.004*(noise(q*110)-.5)+.006*sin(a*23+k*4);
        float width=.013+.017*(.5+.5*sin(a*2+time*.3+k*1.7));
        float edge=abs(r-radius+rough);
        float body=1-smoothstep(width,width+.005,edge);
        float dry=1-.35*smoothstep(.48,.72,noise(q*160+vec2(k*10)));
        ink=max(ink,body*dry);
        // Loose charcoal echoes around the brush stroke.
        for(int j=1;j<4;j++) {
            float d=float(j)*.017;
            float echo=abs(r-radius-d-.008*sin(a*11+k+time*.2));
            ink=max(ink,(1-smoothstep(.001,.004,echo))*.13);
        }
    }
    // Fixed flecks move with the paper, not with a fresh random seed each frame.
    vec2 cell=floor(uv*85);
    vec2 local=fract(uv*85)-.5;
    float speck=step(.974,hash(cell))*(1-smoothstep(.03,.10,length(local)));
    ink=max(ink,speck*.65);
    vec3 pigment=vec3(.055,.046,.041);
    color=vec4(linearize(mix(paper,pigment,ink)),1);
}
