// Field's tiny MLP. Slang stays separate from the Forth/GLSL compiler.
// 16 features -> 32 ReLU -> 32 ReLU -> RGB. FP16 row-major weights, 64-byte offsets.
[[vk::binding(0,0)]] ByteAddressBuffer weights;
[[vk::binding(1,0)]] RWStructuredBuffer<float4> pixels;
struct Params {uint width;uint height;float time;uint view;uint w0;uint b0;uint w1;uint b1;uint w2;uint b2;};
[[vk::push_constant]] Params p;
float3 original(float3 v) {
    float3 result=0;
    for(int k=1;k<=48;k++){float a=k*.61803399;
        for(int c=0;c<3;c++)result[c]+=sin((v.x*cos(a)+v.y*sin(a))*3+v.z*(.3+.1*c)+c*2+a)*(.35/k);}
    return .5+.35*tanh(result*3);
}
void features(float3 v,out float f[16]) {
    float x=v.x,y=v.y,t=v.z;const float pi=3.14159265359;
    f[0]=x;f[1]=y;f[2]=t;f[3]=1;f[4]=sin(x*pi);f[5]=cos(x*pi);f[6]=sin(y*pi);f[7]=cos(y*pi);
    f[8]=sin(t*pi);f[9]=cos(t*pi);f[10]=sin((x+y)*pi);f[11]=cos((x-y)*pi);
    f[12]=sin(2*x*pi);f[13]=sin(2*y*pi);f[14]=cos(2*x*pi);f[15]=cos(2*y*pi);
}
float value(uint offset){uint word=weights.Load(offset&~3u);return f16tof32((word>>((offset&2u)*8))&65535u);}
float3 learned(float3 v) {
    float f[16];features(v,f);
#if FIELD_COOPERATIVE
    CoopVec<half,16> input;for(int i=0;i<16;i++)input[i]=half(f[i]);
    var h1=coopVecMatMulAdd<half,32>(input,CoopVecComponentType::Float16,weights,p.w0,CoopVecComponentType::Float16,
        weights,p.b0,CoopVecComponentType::Float16,CoopVecMatrixLayout::RowMajor,false,32);
    h1=max(h1,CoopVec<half,32>(half(0)));
    var h2=coopVecMatMulAdd<half,32>(h1,CoopVecComponentType::Float16,weights,p.w1,CoopVecComponentType::Float16,
        weights,p.b1,CoopVecComponentType::Float16,CoopVecMatrixLayout::RowMajor,false,64);
    h2=max(h2,CoopVec<half,32>(half(0)));
    var outv=coopVecMatMulAdd<half,3>(h2,CoopVecComponentType::Float16,weights,p.w2,CoopVecComponentType::Float16,
        weights,p.b2,CoopVecComponentType::Float16,CoopVecMatrixLayout::RowMajor,false,64);
    return float3(outv[0],outv[1],outv[2]);
#else
    float h1[32],h2[32];float3 result;
    for(int j=0;j<32;j++){float sum=value(p.b0+j*2);for(int i=0;i<16;i++)sum+=f[i]*value(p.w0+(j*16+i)*2);h1[j]=max(sum,0);}
    for(int j=0;j<32;j++){float sum=value(p.b1+j*2);for(int i=0;i<32;i++)sum+=h1[i]*value(p.w1+(j*32+i)*2);h2[j]=max(sum,0);}
    for(int j=0;j<3;j++){float sum=value(p.b2+j*2);for(int i=0;i<32;i++)sum+=h2[i]*value(p.w2+(j*32+i)*2);result[j]=sum;}
    return result;
#endif
}
[shader("compute")]
[numthreads(8,8,1)]
void main(uint3 id:SV_DispatchThreadID){
    if(id.x>=p.width||id.y>=p.height)return;
    float2 uv=(float2(id.xy)+.5)/float2(p.width,p.height);int panel=p.view==2?min(int(uv.x*3),2):0;
    if(p.view==2)uv.x=frac(uv.x*3);
    float3 v=float3(uv*2-1,p.time);float3 result;
    if(p.view==0 || (p.view==2 && panel==0))result=original(v);
    else {result=learned(v);if(p.view==2 && panel==2)result=abs(result-original(v))*8;}
    pixels[id.y*p.width+id.x]=float4(result,1);
}
