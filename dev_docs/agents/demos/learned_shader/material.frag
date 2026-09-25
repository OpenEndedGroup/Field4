#version 460
layout(set=0,binding=0) uniform sampler2D learnedImage;
layout(location=0) in vec2 uv;
layout(location=0) out vec4 color;
void main() {
    // The left half is the model's RGB; the right is an editable treatment.
    vec2 st=vec2(fract(uv.x*2),uv.y);
    vec3 base=texture(learnedImage,st).rgb;
    vec3 treatment=mix(base,base.gbr,.65);
    treatment*=.85+.15*sin(st.y*160);
    color=vec4(uv.x<.5 ? base : treatment,1);
}
