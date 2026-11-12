#version 450
#extension GL_EXT_multiview : require
layout(location=0) in vec2 uv;
layout(location=0) out vec4 outColor;
layout(set=0,binding=8) uniform sampler2DArray source;
layout(push_constant) uniform FrostBlurPush {
    vec4 stepSigma; // source-UV step XY, sigma in output pixels, blur-space exponent (zero means linear)
} pc;
vec2 encode(vec2 value,float gamma) {
    return pow(max(value,vec2(0)),vec2(gamma));
}
vec4 sampleInBlurSpace(vec2 q,float gamma) {
    vec4 linear=texture(source,vec3(q,gl_ViewIndex));
    if(gamma==1)return linear;
    vec2 size=vec2(textureSize(source,0).xy),p=q*size-.5;
    // Encoding must precede bilinear filtering, including the first half-size reduction.
    // The vertical pass normally lands at exact texel centres and needs no extra fetches.
    if(all(lessThan(abs(p-round(p)),vec2(.0001)))) {
        linear.rb=encode(linear.rb,gamma);
        return linear;
    }
    ivec2 origin=ivec2(floor(p)),limit=ivec2(size)-1;
    vec2 f=fract(p);
    vec2 a=encode(texelFetch(source,ivec3(clamp(origin,ivec2(0),limit),gl_ViewIndex),0).rb,gamma);
    vec2 b=encode(texelFetch(source,ivec3(clamp(origin+ivec2(1,0),ivec2(0),limit),gl_ViewIndex),0).rb,gamma);
    vec2 c=encode(texelFetch(source,ivec3(clamp(origin+ivec2(0,1),ivec2(0),limit),gl_ViewIndex),0).rb,gamma);
    vec2 d=encode(texelFetch(source,ivec3(clamp(origin+ivec2(1,1),ivec2(0),limit),gl_ViewIndex),0).rb,gamma);
    linear.rb=mix(mix(a,b,f.x),mix(c,d,f.x),f.y);
    return linear;
}
void main() {
    float sigma=max(pc.stepSigma.z,.001),total=0;
    float gamma=pc.stepSigma.w>0?clamp(pc.stepSigma.w,.25,4):1;
    vec4 sum=vec4(0);
    int radius=min(48,int(ceil(3*sigma)));
    for(int i=-radius;i<=radius;i++) {
        float weight=exp(-.5*float(i*i)/(sigma*sigma));
        sum+=sampleInBlurSpace(uv+float(i)*pc.stepSigma.xy,gamma)*weight;
        total+=weight;
    }
    outColor=sum/total;
    // Decode after each separable axis; re-encoding before the next axis cancels this transform.
    // The intermediate stays linear float16, avoiding HDR overflow in an encoded storage image.
    if(gamma!=1)outColor.rb=pow(max(outColor.rb,vec2(0)),vec2(1/gamma));
}
