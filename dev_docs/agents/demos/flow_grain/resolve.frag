#version 450
layout(set=0,binding=0) uniform sampler2D colorTexture;
layout(set=0,binding=1) uniform sampler2D motionTexture;
layout(push_constant) uniform Push {
    vec4 look; // w: 0 color; 3 previous UV; 4 displacement magnified.
} pc;
layout(location=0) in vec2 uv;
layout(location=0) out vec4 color;
void main() {
    color = texture(colorTexture,uv);
    vec4 motion = texture(motionTexture,uv);
    if (pc.look.w > 2.5 && pc.look.w < 3.5) color = vec4(motion.z > .5 ? vec3(motion.xy,0) : vec3(1,0,0),1);
    if (pc.look.w > 3.5) color = vec4(motion.z > .5 ? vec3(.5+(motion.xy-uv)*12.,.5) : vec3(1,0,0),1);
}
