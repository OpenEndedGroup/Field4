#version 450
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_multiview : require
layout(std430,buffer_reference) readonly buffer FrostShape {
    uvec4 counts; // exposed edge count, padded rectlet count, reserved
    vec4 pieces[]; // edges XYXY, then rectlets left/bottom/right/top
};
layout(push_constant) uniform FrostPush {
    mat4 model; // panel-local metres to world
    FrostShape shape; // shared exposed-boundary mask
    vec4 bounds; // local bounds
    vec4 settings; // opacity, brightness, sigma, scene scale
} pc;
layout(set=0,binding=8) uniform sampler2DArray sharpBehind;
layout(set=0,binding=9) uniform sampler2DArray blurredBehind;
layout(std140,set=0,binding=10) uniform FrostTone {
    float blackLevel; // per-panel linear-light floor, before the final compositor
    float globalBlackLevel; // optional document-wide floor; zero leaves the panel setting in charge
};
layout(location=0) in vec2 localPoint;
layout(location=0) out vec4 outColor;
void main() {
    float distanceToEdge=1e20;
    for(uint i=0;i<pc.shape.counts.x;i++) {
        vec4 e=pc.shape.pieces[i];
        vec2 axis=e.zw-e.xy;
        float t=clamp(dot(localPoint-e.xy,axis)/max(dot(axis,axis),1e-20),0,1);
        distanceToEdge=min(distanceToEdge,length(localPoint-e.xy-t*axis));
    }
    bool inside=false;
    for(uint i=0;i<pc.shape.counts.y;i++) {
        vec4 r=pc.shape.pieces[pc.shape.counts.x+i];
        inside=inside||all(greaterThanEqual(localPoint,r.xy))&&all(lessThanEqual(localPoint,r.zw));
    }
    float sdf=inside?-distanceToEdge:distanceToEdge;
    float coverage=clamp(.5-sdf/max(fwidth(sdf),1e-7),0,1);
    vec3 uv=vec3(gl_FragCoord.xy/vec2(textureSize(sharpBehind,0).xy),gl_ViewIndex);
    vec3 sharp=texture(sharpBehind,uv).rgb;
    // Identity is exact, avoiding even a downsampling residual when blur is zero.
    vec3 blurred=pc.settings.z<=0?sharp:texture(blurredBehind,uv).rgb;
    vec3 replacement=blurred*vec3(pc.settings.y,1,pc.settings.y);
    // Green is control data. Reconstructed display green follows the lifted red/blue channels.
    replacement.rb=max(replacement.rb,vec2(max(blackLevel,globalBlackLevel)));
    vec3 delta=replacement-sharp;
    outColor=vec4(delta*coverage*pc.settings.x,0); // signed light, additive ONE/ONE; alpha unchanged
}
