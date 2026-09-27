// Field's GPU-resident 16 -> 32 -> 32 -> RGB experiment.
// Uses Slang 2026.18 native CoopVec autodiff and Vulkan training intrinsics directly.
// This fixed network deliberately does not import RTXNS's older CoopVec autodiff extension.
[[vk::binding(0,0)]] ByteAddressBuffer weights;
[[vk::binding(1,0)]] RWByteAddressBuffer gradients;
[[vk::binding(2,0)]] RWStructuredBuffer<float4> optimizer;
[[vk::binding(3,0)]] StructuredBuffer<float4> samples;
[[vk::binding(4,0)]] RWStructuredBuffer<float4> pixels;
[[vk::binding(5,0)]] RWByteAddressBuffer mutableWeights;
struct Params {uint count;uint step;uint w0;uint b0;uint w1;uint b1;uint w2;uint b2;
    float rate;uint width;uint height;float time;uint words;uint view;};
[[vk::push_constant]] Params p;
CoopVec<half,16> features(float3 v) {
    float x=v.x,y=v.y,t=v.z;const float pi=3.14159265359;
    float f[16]={x,y,t,1,sin(x*pi),cos(x*pi),sin(y*pi),cos(y*pi),sin(t*pi),cos(t*pi),
        sin((x+y)*pi),cos((x-y)*pi),sin(2*x*pi),sin(2*y*pi),cos(2*x*pi),cos(2*y*pi)};
    CoopVec<half,16> result;for(int i=0;i<16;i++)result[i]=half(f[i]);return result;
}
[BackwardDerivative(backwardLayer<I,O>)]
CoopVec<half,O> layer<let I:int,let O:int>(CoopVec<half,I> x,uint matrix,uint bias) {
    return coopVecMatMulAdd<half,O>(x,CoopVecComponentType::Float16,weights,matrix,CoopVecComponentType::Float16,
        weights,bias,CoopVecComponentType::Float16,CoopVecMatrixLayout::TrainingOptimal,false,0);
}
void backwardLayer<let I:int,let O:int>(inout DifferentialPair<CoopVec<half,I>> x,uint matrix,uint bias,CoopVec<half,O> gradient) {
    coopVecOuterProductAccumulate(gradient,x.p,gradients,matrix,0,CoopVecMatrixLayout::TrainingOptimal,CoopVecComponentType::Float16);
    coopVecReduceSumAccumulate(gradient,gradients,bias);
    x=diffPair(x.p,coopVecMatMul<half,I>(gradient,CoopVecComponentType::Float16,weights,matrix,
        CoopVecComponentType::Float16,CoopVecMatrixLayout::TrainingOptimal,true,0));
}
[Differentiable]
CoopVec<half,3> model(CoopVec<half,16> x) {
    var h1=max(layer<16,32>(x,p.w0,p.b0),CoopVec<half,32>(half(0)));
    var h2=max(layer<32,32>(h1,p.w1,p.b1),CoopVec<half,32>(half(0)));
    return layer<32,3>(h2,p.w2,p.b2);
}
[shader("compute")][numthreads(64,1,1)]
void train(uint3 id:SV_DispatchThreadID) {
    if(id.x>=p.count || samples[id.x*2+1].w==0)return;
    var input=features(samples[id.x*2].xyz);var prediction=model(input);
    float3 target=samples[id.x*2+1].rgb;
    CoopVec<half,3> loss;
    for(int c=0;c<3;c++)loss[c]=half((float(prediction[c])-target[c])*(2.0*128.0/(3.0*p.count)));
    var differentiableInput=diffPair(input);bwd_diff(model)(differentiableInput,loss);
}
// Each invocation owns a packed pair, avoiding racing 16-bit updates. FP32 master weights/moments
// retain sub-half-ulp updates; FP16 gradients are loss-scaled by 128 and cleared before each step.
[shader("compute")][numthreads(64,1,1)]
void optimize(uint3 id:SV_DispatchThreadID) {
    uint word=id.x;if(word>=p.words)return;uint old=weights.Load(word*4),grad=gradients.Load(word*4),packed=0;
    for(uint lane=0;lane<2;lane++){
        uint index=word*2+lane;float4 state=optimizer[index];
        float master=p.step==1?f16tof32((old>>(lane*16))&65535):state.x;
        float g=f16tof32((grad>>(lane*16))&65535)/128.0;
        if(!isfinite(g))g=0;g=clamp(g,-10.0,10.0);
        float m=.9*state.y+.1*g,v=.999*state.z+.001*g*g;
        master-=p.rate*(m/(1-pow(.9,float(p.step))))/(sqrt(v/(1-pow(.999,float(p.step))))+1e-8);
        optimizer[index]=float4(master,m,v,0);packed|=(f32tof16(master)&65535)<<(lane*16);
    }
    mutableWeights.Store(word*4,packed);
}
[shader("compute")][numthreads(8,8,1)]
void evaluate(uint3 id:SV_DispatchThreadID) {
    if(id.x>=p.width || id.y>=p.height)return;
    var result=model(features(float3((float2(id.xy)+.5)/float2(p.width,p.height)*2-1,p.time)));
    pixels[id.y*p.width+id.x]=float4(result[0],result[1],result[2],1);
}
