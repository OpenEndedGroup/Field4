#version 450
layout(location=0) in vec2 uv;
layout(location=1) flat in int eye;
layout(location=0) out vec4 outColor;
layout(set=0,binding=0) uniform sampler2DArray sharpImage;
layout(set=0,binding=1) uniform sampler2DArray mediumImage;
layout(set=0,binding=2) uniform sampler2DArray broadImage;
layout(push_constant) uniform CompositePush {
    vec4 selection; // selector gain, offset, effect mix, source-green scale
    vec4 reconstruction; // red/blue green reconstruction weights, exposure, reserved
} pc;
// Live grading controls use a UBO: the compositor still needs only 32 push-constant bytes.
layout(std140,set=0,binding=3) uniform CompositeGrade {
    vec4 selectorRange; // selector minimum/maximum, cubic fold strength, reserved
    vec4 selectorSource; // sharp/medium/broad green weights, reserved
    vec4 paint; // opacity threshold, smooth knee width, normalization mix, reserved
    vec4 colorFold; // red/green/blue fold gains, overall fold mix
    vec4 foldLimits; // red/green/blue fold caps, fold scale
    vec4 alphaFold; // fold gain, cap, strength, reserved
    vec4 saturation; // base saturation, shadow desaturation, shadow knee, shadow slope
    vec4 gain; // per-channel linear gain, reserved
    vec4 gamma; // per-channel artistic gamma, master artistic gamma
    vec4 shadowTone; // shadow hue RGB, luminance-preserving strength
    vec4 highlightTone; // highlight hue RGB, luminance-preserving strength
    vec4 toneRange; // shadow end, highlight start, luminance balance, reserved
    vec4 background; // linear background RGB, strength
    vec4 fxaa; // overall mix, relative edge threshold, absolute threshold, span pixels
    vec4 subpixel; // subpixel smoothing, output-eye width/height, linear-light FXAA flag
    vec4 supersampling; // per-pixel rotation mix, footprint radius, stable integer seed, reserved
} grade;

// Editable effective LUT. Outside 0..1 these sum-to-one weights deliberately go
// negative, so preserve signed RGB until background and artistic gamma are applied.
vec3 blurWeights(float green) {
    float lo=min(grade.selectorRange.x,grade.selectorRange.y);
    float hi=max(grade.selectorRange.x,grade.selectorRange.y);
    float t=clamp(green*pc.selection.x+pc.selection.y,lo,hi);
    return vec3(max(1.0-2.0*t,0.0),1.0-abs(2.0*t-1.0),max(2.0*t-1.0,0.0));
}
float luminance(vec3 c) {
    return dot(c,vec3(0.2126,0.7152,0.0722));
}
float smoothMaximum(float a,float b,float width) {
    if(width<=1e-6)return max(a,b);
    float h=clamp(0.5+0.5*(a-b)/width,0.0,1.0);
    return mix(b,a,h)+width*h*(1.0-h);
}
vec3 signedGamma(vec3 c,vec3 gamma_) {
    return sign(c)*pow(abs(c),1.0/max(gamma_,vec3(0.001)));
}

// Hue transfer at constant Rec.709 linear luminance. Reduce chroma, rather than
// clipping RGB, when the requested tint would leave the display gamut.
vec3 lumaPreservingTint(vec3 color,vec4 tint,float region) {
    float strength=clamp(tint.a*region,0.0,1.0);
    vec3 hue=max(tint.rgb,vec3(0));
    float hueY=luminance(hue);
    if(strength<=0.0||hueY<=1e-6)return color;
    vec3 target=hue*(luminance(color)/hueY);
    vec3 delta=(target-color)*strength;
    float chromaScale=1.0;
    for(int channel=0;channel<3;channel++) {
        if(delta[channel]>1e-6)chromaScale=min(chromaScale,(1.0-color[channel])/delta[channel]);
        else if(delta[channel]<-1e-6)chromaScale=min(chromaScale,-color[channel]/delta[channel]);
    }
    return color+delta*clamp(chromaScale,0.0,1.0);
}

// Resolve supersampled RGBA in linear scene space, before nonlinear grading.
// Four bilinear taps cover the output pixel; at 100% the original single fetch is used.
vec2 outputSize() {
    return grade.subpixel.y>0.0&&grade.subpixel.z>0.0?grade.subpixel.yz:vec2(textureSize(sharpImage,0).xy);
}
// Integer hash has no frame or eye dependence: a stationary view has stationary
// grain, and both eyes use the same screen-space pattern rather than independent noise.
uint sampleMaskHash(uvec2 pixel,uint seed) {
    uint h=pixel.x*1664525u+pixel.y*1013904223u+seed*747796405u;
    h=(h^(h>>16u))*2246822519u;
    h=(h^(h>>13u))*3266489917u;
    return h^(h>>16u);
}
vec4 sharpSample(vec3 p) {
    vec2 sourceSize=vec2(textureSize(sharpImage,0).xy);
    vec2 size=outputSize();
    if(all(lessThanEqual(sourceSize,size+vec2(0.5))))return textureLod(sharpImage,p,0.0);
    uvec2 pixel=uvec2(clamp(floor(p.xy*size),vec2(0),size-vec2(1)));
    uint bits=sampleMaskHash(pixel,uint(max(grade.supersampling.z,0.0)));
    float angle=float(bits>>8u)*(6.28318530718/16777216.0)*clamp(grade.supersampling.x,0.0,1.0);
    float c=cos(angle),s=sin(angle);
    mat2 rotation=mat2(c,s,-s,c);
    // Opposite pairs keep the centroid exactly zero; equal weights preserve constant RGBA.
    // Rotation is in output-pixel coordinates, before dividing by the non-square eye size.
    float radius=0.25*max(grade.supersampling.y,0.0);
    vec2 a=rotation*vec2(radius,radius)/size;
    vec2 b=rotation*vec2(radius,-radius)/size;
    return 0.25*(textureLod(sharpImage,p+vec3(a,0),0.0)
    +textureLod(sharpImage,p-vec3(a,0),0.0)
    +textureLod(sharpImage,p+vec3(b,0),0.0)
    +textureLod(sharpImage,p-vec3(b,0),0.0));
}

// One world-frame image evaluation. Alpha is summed opacity; it is not display alpha.
vec3 composeScene(vec2 at) {
    vec3 p=vec3(clamp(at,vec2(0),vec2(1)),float(eye));
    vec4 sharp=sharpSample(p);
    vec4 medium=textureLod(mediumImage,p,0.0);
    vec4 broad=textureLod(broadImage,p,0.0);

    //sharp -= broad*0.2;

    vec3 source=max(grade.selectorSource.xyz,vec3(0));
    float sourceSum=dot(source,vec3(1));
    float g=(sourceSum>1e-6?dot(vec3(sharp.g,medium.g,broad.g),source)/sourceSum:broad.g)*pc.selection.w;
    float foldedGreen=g-g*g*g*grade.selectorRange.z;
    vec3 weights=blurWeights(foldedGreen);
    vec4 c=mix(sharp,sharp*weights.x+medium*weights.y+broad*weights.z,pc.selection.z);
    c.g=dot(c.rb,pc.reconstruction.xy)/max(pc.reconstruction.x+pc.reconstruction.y,1e-6);

    // Optional channel foldovers retain the original recipe's signed response.
    vec3 folded=c.rgb;
    folded.r+=min(grade.foldLimits.x,broad.g*broad.g*g*grade.colorFold.x)*grade.foldLimits.w;
    folded.g-=min(grade.foldLimits.y,g*g*grade.colorFold.y)*grade.foldLimits.w;
    folded.b-=min(grade.foldLimits.z,g*broad.g*grade.colorFold.z)*c.b*grade.foldLimits.w;
    c.rgb=mix(c.rgb,folded,clamp(grade.colorFold.w,0.0,1.0));
    c.a*=1.0-clamp(grade.alphaFold.z,0.0,1.0)*clamp(g*broad.g*grade.alphaFold.x,0.0,max(grade.alphaFold.y,0.0));

    // C1 knee from additive light below the threshold to opacity-weighted paint above it.
    float denominator=max(smoothMaximum(max(c.a,0.0),max(grade.paint.x,0.0001),max(grade.paint.y,0.0)),0.0001);
    c.rgb*=mix(1.0,1.0/denominator,clamp(grade.paint.z,0.0,1.0));
    float energy=dot(abs(c.rgb),vec3(1.0/3.0));
    c.rgb=mix(vec3(luminance(c.rgb)),c.rgb,max(grade.saturation.x,0.0));
    float desaturate=clamp(grade.saturation.y*max(grade.saturation.z-energy,0.0)*grade.saturation.w,0.0,1.0);
    c.rgb=mix(c.rgb,vec3(energy),desaturate);

    // Signed foreground can darken the background. Neither is clipped before artistic gamma.
    float coverage=clamp(max(c.a,0.0)/denominator,0.0,1.0);
    vec3 color=c.rgb+grade.background.rgb*grade.background.a*(1.0-coverage);
    color*=grade.gain.rgb*pc.reconstruction.z;
    // Toning operates on final display-linear colour, after artistic gamma.
    // This makes its luminance invariant survive per-channel gamma and gamut limits.
    color=clamp(signedGamma(color,grade.gamma.rgb*grade.gamma.w),vec3(0),vec3(1));
    float tone=clamp(luminance(color)+grade.toneRange.z,0.0,1.0);
    float shadow=1.0-smoothstep(0.0,max(grade.toneRange.x,0.0001),tone);
    float highlight=smoothstep(min(grade.toneRange.y,0.9999),1.0,tone);
    color=lumaPreservingTint(color,grade.shadowTone,shadow);
    color=lumaPreservingTint(color,grade.highlightTone,highlight);
    return color;
}

// Keep perceptual edge detection, with selectable linear-light colour averaging.
// The target performs the one final sRGB encoding; artistic gamma belongs to grading.
vec3 linearToSrgb(vec3 c) {
    return mix(12.92*c,1.055*pow(max(c,vec3(0)),vec3(1.0/2.4))-0.055,step(vec3(0.0031308),c));
}
vec3 srgbToLinear(vec3 c) {
    return mix(c/12.92,pow((c+0.055)/1.055,vec3(2.4)),step(vec3(0.04045),c));
}
bool linearAA() {
    return grade.subpixel.w>=0.5;
}
vec3 displaySample(vec2 at) {
    vec3 color=clamp(composeScene(at),vec3(0),vec3(1));
    return linearAA()?color:linearToSrgb(color);
}
float aaLuma(vec3 color) {
    return luminance(linearAA()?linearToSrgb(color):color);
}

// Compact FXAA-style directional filter plus a bounded subpixel low-pass.
// It evaluates this same compositor at neighbours: no extra RTT, history, or pose delay.
vec3 spatialAA(vec2 at,vec3 center) {
    vec2 texel=1.0/outputSize();
    vec3 n=displaySample(at+texel*vec2(0,-1)),s=displaySample(at+texel*vec2(0,1));
    vec3 e=displaySample(at+texel*vec2(1,0)),w=displaySample(at+texel*vec2(-1,0));
    float mY=aaLuma(center),nY=aaLuma(n),sY=aaLuma(s),eY=aaLuma(e),wY=aaLuma(w);
    float low=min(mY,min(min(nY,sY),min(eY,wY)));
    float high=max(mY,max(max(nY,sY),max(eY,wY)));
    float contrast=high-low;
    if(contrast<max(grade.fxaa.z,high*grade.fxaa.y))return center;
    vec3 nw=displaySample(at+texel*vec2(-1,-1)),ne=displaySample(at+texel*vec2(1,-1));
    vec3 sw=displaySample(at+texel*vec2(-1,1)),se=displaySample(at+texel*vec2(1,1));
    float nwY=aaLuma(nw),neY=aaLuma(ne),swY=aaLuma(sw),seY=aaLuma(se);
    low=min(low,min(min(nwY,neY),min(swY,seY)));
    high=max(high,max(max(nwY,neY),max(swY,seY)));
    vec2 direction=vec2(-((nwY+neY)-(swY+seY)),(nwY+swY)-(neY+seY));
    float reduce=max((nwY+neY+swY+seY)*(0.25/8.0),1.0/128.0);
    direction=clamp(direction/(min(abs(direction.x),abs(direction.y))+reduce),vec2(-grade.fxaa.w),vec2(grade.fxaa.w))*texel;
    vec3 a=0.5*(displaySample(at-direction/6.0)+displaySample(at+direction/6.0));
    vec3 b=a*0.5+0.25*(displaySample(at-direction*0.5)+displaySample(at+direction*0.5));
    float bY=aaLuma(b);
    vec3 edge=(bY<low||bY>high)?a:b;
    float averageY=(nY+sY+eY+wY)*0.25;
    float subpixel=clamp((abs(averageY-mY)/max(contrast,1e-6)-0.25)/0.75,0.0,1.0);
    subpixel=subpixel*subpixel*(3.0-2.0*subpixel);
    vec3 neighbourhood=(center+n+s+e+w+nw+ne+sw+se)/9.0;
    return mix(edge,neighbourhood,subpixel*clamp(grade.subpixel.x,0.0,1.0));
}
void main() {
    vec3 color=composeScene(uv);
    if(grade.fxaa.x>0.0) {
        vec3 center=clamp(color,vec3(0),vec3(1));
        if(!linearAA())center=linearToSrgb(center);
        vec3 filtered=mix(center,spatialAA(uv,center),clamp(grade.fxaa.x,0.0,1.0));
        color=linearAA()?filtered:srgbToLinear(filtered);
    }
    outColor=vec4(max(color,vec3(0)),1.0);
}
