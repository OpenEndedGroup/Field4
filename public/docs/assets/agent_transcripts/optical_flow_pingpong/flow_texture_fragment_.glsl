#version 460
layout(location=0) in vec2 uv;
layout(location=0) out vec4 color;
layout(set=0,binding=0) uniform sampler2D previousImage;
layout(set=0,binding=1) uniform sampler2D currentImage;
layout(set=0,binding=2) uniform sampler2D motion;
void main() {
    vec2 panel=vec2(uv.x,1-uv.y)*2,st=fract(panel);
    vec4 field=texture(motion,st); // xy: input pixels, z: hardware matching cost
    vec3 rgb;
    if(panel.y<1)rgb=panel.x<1?texture(previousImage,st).rgb:texture(currentImage,st).rgb;
    else if(panel.x>=1)rgb=vec3(clamp(field.z/100,0,1));
    else {
        float angle=atan(field.y,field.x);
        rgb=(.5+.5*cos(angle+vec3(0,2.094,4.189)))*clamp(length(field.xy)/8,0,1);
        vec2 cell=(fract(st*vec2(textureSize(currentImage,0))/40)-.5)*40;
        vec2 d=normalize(field.xy+vec2(.0001));
        float along=dot(cell,d),across=abs(dot(cell,vec2(-d.y,d.x)));
        float arrow=float((abs(along)<12&&across<.8)||(along>6&&along<13&&abs(across-(13-along))<1));
        rgb=mix(rgb,vec3(1),arrow*.8*clamp(length(field.xy),0,1));
    }
    color=vec4(rgb,1);
}
