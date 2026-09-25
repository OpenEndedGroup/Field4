#version 460
layout(push_constant) uniform Scene { vec4 sizeJitter; vec4 times; vec4 settings; } pc;
layout(location=0) in vec3 world;
layout(location=1) in vec3 normal;
layout(location=2) in vec4 nowClip;
layout(location=3) in vec4 oldClip;
layout(location=4) flat in int objectId;
layout(location=0) out vec4 color;
layout(location=1) out float depth;
layout(location=2) out vec2 motion;
void main(){
    if(objectId<0){color=vec4(.014,.022,.04,1);depth=1;motion=vec2(0);return;}
    vec3 n=normalize(normal);
    vec3 base=objectId==0?vec3(.23):objectId==1?vec3(.05,.27,.65):objectId==2?vec3(.8,.14,.045):vec3(.9,.68,.21);
    if(objectId<3){vec2 st=world.xz*9;float checkers=mod(floor(st.x)+floor(st.y),2);base*=mix(.22,1,checkers);}
    vec3 light=normalize(vec3(sin(pc.times.x)*4,5,3)-world);
    float diffuse=.2+.8*abs(dot(n,light));
    float specular=pow(max(0,abs(dot(n,normalize(light+normalize(vec3(5,3.7,5)-world))))),96);
    color=vec4(base*diffuse+specular*1.8,1);
    if(pc.settings.x==1){
        color.rgb=objectId==0?vec3(.04,.08,.16):base;
        // Deliberately changing appearance has no geometric correspondence. It is visible in
        // comparisons, but not represented as fictional velocity or an undocumented vendor mask.
        if(objectId==1)color.rgb=.45+.4*cos(world*8+pc.times.x*vec3(2,3,5));
        if(objectId>=3)color.rgb=mix(vec3(.1,.8,.6),vec3(.9,.08,.3),step(0,sin(pc.times.x*1.7)));
    }
    vec2 uv=nowClip.xy/nowClip.w*vec2(.5,-.5)+.5;
    vec2 prev=oldClip.xy/oldClip.w*vec2(.5,-.5)+.5;
    motion=(prev-uv)*pc.sizeJitter.xy;
    depth=gl_FragCoord.z;
}
