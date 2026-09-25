#version 460
layout(push_constant) uniform Scene { vec4 sizeJitter; vec4 times; vec4 settings; } pc;
layout(location=0) out vec3 world;
layout(location=1) out vec3 normal;
layout(location=2) out vec4 nowClip;
layout(location=3) out vec4 oldClip;
layout(location=4) flat out int objectId;
const int indices[36]=int[](0,2,1,1,2,3,4,5,6,5,7,6,0,1,4,1,5,4,2,6,3,3,6,7,0,4,2,2,4,6,1,3,5,3,7,5);
vec3 corner(int i){return vec3((i&1)==0?-1:1,(i&2)==0?-1:1,(i&4)==0?-1:1);}
vec3 objectPoint(int id,vec3 p,float time){
    if(id>=39){float k=float(id-39);return p*(pc.settings.x==1?.035:0)+vec3(sin(k*2.4+time)*3,1.4+sin(k*.8+time*.7),cos(k*1.7+time)*2);}
    if(id==0)return p*vec3(5,.06,4)+vec3(0,-.08,0);
    if(id==1)return p*vec3(.85,.7,.85)+vec3(-1.5,.7,-.5);
    if(id==2)return p*vec3(.7,.9,.7)+vec3(1.4+sin(time)*.6,.9,-.4);
    int n=id-3;
    if(n<18){p*=vec3(.015,1.2,.015);p.xy=mat2(.8,.6,-.6,.8)*p.xy;return p+vec3(float(n)*.19-1.7,1.7,1.4);}
    p*=vec3(.015,1.2,.015);p.xy=mat2(.8,-.6,.6,.8)*p.xy;return p+vec3(float(n-18)*.19-1.7,1.7,1.45);
}
vec4 project(vec3 p,float t){
    float a=pc.settings.y==1?t*.18:0;
    vec3 eye=vec3(5*sin(a)+5*cos(a),3.7,5*cos(a)-5*sin(a));
    vec3 f=normalize(vec3(0,.8,0)-eye),r=normalize(cross(f,vec3(0,1,0))),u=cross(r,f);
    vec3 q=p-eye;float z=dot(q,f);float near=.1,far=100;
    return vec4(dot(q,r)*1.9*pc.sizeJitter.y/pc.sizeJitter.x,dot(q,u)*1.9,far/(far-near)*z-far*near/(far-near),z);
}
void main(){
    if(gl_VertexIndex<3){
        vec2 xy=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2)*2-1;
        gl_Position=vec4(xy,.999999,1);objectId=-1;world=vec3(0);normal=vec3(0,1,0);nowClip=oldClip=gl_Position;return;
    }
    objectId=(gl_VertexIndex-3)/36;int v=(gl_VertexIndex-3)%36;
    float t=pc.times.x,old=pc.times.y;
    vec3 p=corner(indices[v]);world=objectPoint(objectId,p,t);
    int tri=(v/3)*3;vec3 a=objectPoint(objectId,corner(indices[tri]),t),b=objectPoint(objectId,corner(indices[tri+1]),t),c=objectPoint(objectId,corner(indices[tri+2]),t);
    normal=normalize(cross(b-a,c-a));
    nowClip=project(world,t);oldClip=project(objectPoint(objectId,p,old),old);
    nowClip.x+=2*pc.times.z/pc.sizeJitter.x*nowClip.w;
    oldClip.x+=2*pc.times.w/pc.sizeJitter.x*oldClip.w;
    gl_Position=nowClip;
    gl_Position.xy+=vec2(2*pc.sizeJitter.z/pc.sizeJitter.x,-2*pc.sizeJitter.w/pc.sizeJitter.y)*gl_Position.w;
}
