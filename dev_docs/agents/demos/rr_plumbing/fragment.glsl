#version 460
layout(set=0,binding=0) uniform sampler2D positions;
layout(set=0,binding=1) uniform sampler2D normals;
layout(set=0,binding=2) uniform sampler2D albedo;
layout(set=0,binding=3) uniform sampler2D velocity;
layout(location=0) out float depth;
layout(location=1) out vec2 motion;
layout(location=2) out vec4 diffuse;
layout(location=3) out vec4 specular;
layout(location=4) out vec4 normalRoughness;
void main(){ivec2 p=ivec2(gl_FragCoord.xy);vec4 pos=texelFetch(positions,p,0);
depth=pos.w;motion=-texelFetch(velocity,p,0).xy*vec2(textureSize(velocity,0));
vec3 n=texelFetch(normals,p,0).xyz;bool valid=pos.w<1 && dot(n,n)>.01;
diffuse=vec4(valid?texelFetch(albedo,p,0).rgb:vec3(0),1);
specular=vec4(0,0,0,1);normalRoughness=vec4(valid?normalize(n):vec3(0,0,1),1);
}
