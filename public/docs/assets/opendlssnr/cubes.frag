#version 460
layout(push_constant) uniform Scene {
    // xy = input width/height in pixels; zw = raster jitter in pixels, with Y down.
    vec4 sizeJitter;
    // xy = current/previous scene times; zw = current/previous horizontal shifts in input pixels.
    vec4 times;
    // x = particle/appearance stress test; y = orbiting camera; zw unused.
    vec4 settings;
} pc;
layout(location=0) in vec3 world;
layout(location=1) in vec3 normal;
layout(location=2) in vec4 nowClip;
layout(location=3) in vec4 oldClip;
layout(location=4) flat in int objectId;
layout(location=0) out vec4 color;
void main() {
    if(objectId<0) {
        color=vec4(.014,.022,.04,1);
        return;
    }
    vec3 n=normalize(normal);
    vec3 base=objectId==0?vec3(.23):objectId==1?vec3(.05,.27,.65):objectId==2?vec3(.8,.14,.045):vec3(.9,.68,.21);
    // Checker coordinates are world-space XZ; lighting is evaluated separately below.
    if(objectId<3) {
        vec2 st=world.xz*9;
        float checkers=mod(floor(st.x)+floor(st.y),2);
        base*=mix(.22,1,checkers);
    }
    vec3 light=normalize(vec3(sin(pc.times.x)*4,5,3)-world);
    float diffuse=.2+.8*abs(dot(n,light));
    float specular=pow(max(0,abs(dot(n,normalize(light+normalize(vec3(5,3.7,5)-world))))),96);
    color=vec4(clamp(base*diffuse+specular*1.8,0,1),1);
    if(pc.settings.x==1) {
        color.rgb=objectId==0?vec3(.04,.08,.16):base;
        // Deliberately changing appearance has no geometric correspondence. It is visible in
        // comparisons, but not represented as fictional velocity or an undocumented vendor mask.
        if(objectId==1)color.rgb=.45+.4*cos(world*8+pc.times.x*vec3(2,3,5));
        if(objectId>=3)color.rgb=mix(vec3(.1,.8,.6),vec3(.9,.08,.3),step(0,sin(pc.times.x*1.7)));
    }
}
