// Field-owned bridge. RTXDI headers are supplied by the separately pinned optional SDK.
#include <Rtxdi/RtxdiParameters.h>
#include <Rtxdi/DI/ReSTIRDIParameters.h>
#define RTXDI_ALLOWED_BIAS_CORRECTION RTXDI_BIAS_CORRECTION_RAY_TRACED
#include <Rtxdi/Utils/RandomSamplerState.hlsli>
#include <Rtxdi/DI/Reservoir.hlsli>
struct Settings {
    uint64_t surfaces, lights, previousLights, pixels;
    uint width, height, frameIndex, page;
    uint stage, candidates, lightCount, previousLightCount;
    uint temporal, spatial, historyValid, maxHistory;
    float radius, normalThreshold, depthThreshold, epsilon;
    uint diagnostic, seed; float weight; uint shadows;
};
[[vk::push_constant]] Settings p;
[[vk::binding(0,0)]] RWStructuredBuffer<RTXDI_PackedDIReservoir> reservoirs;
[[vk::binding(1,0)]] StructuredBuffer<float2> offsets;
[[vk::binding(2,0)]] RaytracingAccelerationStructure sceneAS;
#define RTXDI_LIGHT_RESERVOIR_BUFFER reservoirs
#define RTXDI_NEIGHBOR_OFFSETS_BUFFER offsets
#include <Rtxdi/DI/ReservoirStorage.hlsli>

struct SurfaceData { float4 position, normal, albedo, source, reprojection; };
struct RAB_Surface { SurfaceData data; };
struct RAB_Material { float3 albedo; };
struct RAB_LightInfo { float4 center, edgeU, edgeV, radiance; uint4 identity; };
struct RAB_LightSample { float3 position; float3 contribution; };
static RAB_Surface centerSurface;
RAB_Surface RAB_EmptySurface() { return (RAB_Surface)0; }
bool RAB_IsSurfaceValid(RAB_Surface s) { return s.data.normal.w>0; }
float3 RAB_GetSurfaceNormal(RAB_Surface s) { return s.data.normal.xyz; }
float RAB_GetSurfaceLinearDepth(RAB_Surface s) { return s.data.normal.w; }
RAB_Material RAB_GetMaterial(RAB_Surface s) { RAB_Material m; m.albedo=s.data.albedo.xyz; return m; }
bool RAB_AreMaterialsSimilar(RAB_Material a, RAB_Material b) { return all(abs(a.albedo-b.albedo)<0.001); }
RAB_Surface RAB_GetGBufferSurface(int2 xy, bool previous) {
    RAB_Surface s=RAB_EmptySurface();
    if(any(xy<0)||any(xy>=int2(p.width,p.height))||(previous&&!p.historyValid))return s;
    uint page=previous?1-p.page:p.page;
    uint index=(page*p.height+xy.y)*p.width+xy.x;
    s.data=vk::RawBufferLoad<SurfaceData>(p.surfaces+uint64_t(index)*80);
    // Global geometry revisions reset history. Within an unchanged opaque diffuse scene,
    // reject positional, normal and material changes before the SDK's reprojection search.
    if(previous && (length(s.data.position.xyz-centerSurface.data.position.xyz)>max(0.002,centerSurface.data.normal.w*0.003)
        || !RAB_AreMaterialsSimilar(RAB_GetMaterial(s),RAB_GetMaterial(centerSurface)))) s.data.normal.w=0;
    return s;
}
int2 RAB_ClampSamplePositionIntoView(int2 xy,bool previous) { return clamp(xy,int2(0,0),int2(p.width,p.height)-1); }
RAB_LightInfo RAB_EmptyLightInfo(){return (RAB_LightInfo)0;}
RAB_LightInfo RAB_LoadLightInfo(uint index,bool previous) {
    return vk::RawBufferLoad<RAB_LightInfo>((previous?p.previousLights:p.lights)+uint64_t(index)*80);
}
int RAB_TranslateLightIndex(uint index,bool currentToPrevious) {
    if(index>=(currentToPrevious?p.lightCount:p.previousLightCount))return -1;
    return int(RAB_LoadLightInfo(index,!currentToPrevious).identity.y);
}
RAB_LightSample RAB_EmptyLightSample(){return (RAB_LightSample)0;}
RAB_LightSample RAB_SamplePolymorphicLight(RAB_LightInfo light,RAB_Surface surface,float2 uv) {
    RAB_LightSample s;
    s.position=light.center.xyz+(uv.x-0.5)*light.edgeU.xyz+(uv.y-0.5)*light.edgeV.xyz;
    float3 delta=s.position-surface.data.position.xyz;
    float r2=dot(delta,delta);
    float3 crossEdges=cross(light.edgeU.xyz,light.edgeV.xyz);
    float area=length(crossEdges);
    float3 direction=delta*rsqrt(max(r2,1e-20));
    float geometry=max(dot(surface.data.normal.xyz,direction),0)*max(dot(crossEdges/area,-direction),0);
    s.contribution= r2>p.epsilon*p.epsilon ? surface.data.albedo.rgb*light.radiance.rgb*(area*geometry/(RTXDI_PI*r2)) : 0;
    return s;
}
float RAB_GetLightSampleTargetPdfForSurface(RAB_LightSample sample,RAB_Surface surface) {
    return dot(sample.contribution,float3(0.2126,0.7152,0.0722));
}
bool RAB_GetConservativeVisibility(RAB_Surface surface,RAB_LightSample sample) {
    if(!p.shadows)return true;
    RayDesc ray;
    ray.Origin=surface.data.position.xyz+surface.data.normal.xyz*p.epsilon;
    float3 delta=sample.position-ray.Origin;
    float distance=length(delta);
    if(distance<=2*p.epsilon)return true;
    ray.Direction=delta/distance;ray.TMin=0;ray.TMax=distance-p.epsilon;
    RayQuery<RAY_FLAG_FORCE_OPAQUE|RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH> q;
    q.TraceRayInline(sceneAS,RAY_FLAG_NONE,255,ray);
    while(q.Proceed()){}
    return q.CommittedStatus()==COMMITTED_NOTHING;
}
bool RAB_GetTemporalConservativeVisibility(RAB_Surface current,RAB_Surface previous,RAB_LightSample sample) {
    // Previous and current BVHs agree: every geometry/instance revision resets all history.
    return RAB_GetConservativeVisibility(previous,sample);
}
#include <Rtxdi/DI/TemporalResampling.hlsli>
#include <Rtxdi/DI/SpatialResampling.hlsli>

[numthreads(8,8,1)]
void main(uint2 xy:SV_DispatchThreadID) {
    if(any(xy>=uint2(p.width,p.height)))return;
    RAB_Surface surface=RAB_GetGBufferSurface(xy,false);centerSurface=surface;
    RTXDI_ReservoirBufferParameters layout=(RTXDI_ReservoirBufferParameters)0;
    layout.reservoirBlockRowPitch=((p.width+15)/16)*256;
    layout.reservoirArrayPitch=layout.reservoirBlockRowPitch*((p.height+15)/16);
    RTXDI_RuntimeParameters runtime=(RTXDI_RuntimeParameters)0;
    runtime.neighborOffsetMask=255;runtime.frameIndex=p.frameIndex;
    RTXDI_RandomSamplerState rng=RTXDI_InitRandomSampler(xy,p.frameIndex+p.seed,p.stage+1);
    if(p.stage==4) {
        float3 reference=surface.data.source.rgb;
        if(RAB_IsSurfaceValid(surface)) {
            reference=0;
            for(uint k=0;k<p.candidates && p.lightCount>0;k++) {
                uint li=min(uint(RTXDI_GetNextRandom(rng)*p.lightCount),p.lightCount-1);
                float2 uv=float2(RTXDI_GetNextRandom(rng),RTXDI_GetNextRandom(rng));
                RAB_LightSample ls=RAB_SamplePolymorphicLight(RAB_LoadLightInfo(li,false),surface,uv);
                reference+=ls.contribution*float(p.lightCount)*(RAB_GetConservativeVisibility(surface,ls)?1:0);
            }
            reference=lerp(surface.data.source.rgb,reference/float(p.candidates),p.weight);
        }
        vk::RawBufferStore<float4>(p.pixels+uint64_t(xy.y*p.width+xy.x)*16,float4(reference,RAB_IsSurfaceValid(surface)?1:-1));
        return;
    }
    RTXDI_DIReservoir reservoir=RTXDI_EmptyDIReservoir();
    RAB_LightSample sample=RAB_EmptyLightSample();
    int2 reusedPixel=int2(-1,-1);
    if(RAB_IsSurfaceValid(surface)) {
        if(p.stage==0) {
            for(uint i=0;i<p.candidates && p.lightCount>0;i++) {
                uint index=min(uint(RTXDI_GetNextRandom(rng)*p.lightCount),p.lightCount-1);
                float2 uv=float2(RTXDI_GetNextRandom(rng),RTXDI_GetNextRandom(rng));
                sample=RAB_SamplePolymorphicLight(RAB_LoadLightInfo(index,false),surface,uv);
                RTXDI_StreamSample(reservoir,index,uv,RTXDI_GetNextRandom(rng),
                    RAB_GetLightSampleTargetPdfForSurface(sample,surface),float(p.lightCount));
            }
            RTXDI_FinalizeResampling(reservoir,1,reservoir.M);
            reservoir.M=1; // one independently resampled candidate set per frame, SDK convention
        } else {
            reservoir=RTXDI_LoadDIReservoir(layout,xy,p.stage-1);
            if(p.stage==1 && p.temporal && p.historyValid) {
                RTXDI_DITemporalResamplingParameters t=(RTXDI_DITemporalResamplingParameters)0;
                t.maxHistoryLength=p.maxHistory;t.biasCorrectionMode=RTXDI_BIAS_CORRECTION_RAY_TRACED;
                t.depthThreshold=p.depthThreshold;t.normalThreshold=p.normalThreshold;
                reservoir=RTXDI_DITemporalResampling(xy,surface,reservoir,rng,runtime,layout,
                    surface.data.reprojection.xyz,2,t,reusedPixel,sample);
            }
            if(p.stage==2 && p.spatial>0) {
                RTXDI_DISpatialResamplingParameters s=(RTXDI_DISpatialResamplingParameters)0;
                s.numSamples=p.spatial;s.numDisocclusionBoostSamples=p.spatial;s.samplingRadius=p.radius;
                s.biasCorrectionMode=RTXDI_BIAS_CORRECTION_RAY_TRACED;s.depthThreshold=p.depthThreshold;
                s.normalThreshold=p.normalThreshold;s.enableMaterialSimilarityTest=1;
                reservoir=RTXDI_DISpatialResampling(xy,surface,reservoir,rng,runtime,layout,1,s,sample);
            }
        }
    }
    // Ray-traced bias correction assumes input reservoirs already include source visibility.
    // Discard an invisible initial/temporal sample while retaining M, as in the SDK sample.
    if(p.stage<2 && RTXDI_IsValidDIReservoir(reservoir)) {
        sample=RAB_SamplePolymorphicLight(RAB_LoadLightInfo(RTXDI_GetDIReservoirLightIndex(reservoir),false),surface,RTXDI_GetDIReservoirSampleUV(reservoir));
        if(!RAB_GetConservativeVisibility(surface,sample))RTXDI_StoreVisibilityInDIReservoir(reservoir,0,true);
    }
    if(p.stage<3) {
        RTXDI_StoreDIReservoir(reservoir,layout,xy,p.stage);
        // Keep a separate diagnostic flag in the current surface snapshot.
        if(p.stage==1) {
            surface.data.reprojection.w=all(reusedPixel>=0)?0:1;
            vk::RawBufferStore<SurfaceData>(p.surfaces+uint64_t((p.page*p.height+xy.y)*p.width+xy.x)*80,surface.data);
        }
        return;
    }
    float3 color=surface.data.source.rgb;float visible=-1;
    if(RAB_IsSurfaceValid(surface)) {
        color=0;visible=0;
        if(RTXDI_IsValidDIReservoir(reservoir)) {
            sample=RAB_SamplePolymorphicLight(RAB_LoadLightInfo(RTXDI_GetDIReservoirLightIndex(reservoir),false),surface,RTXDI_GetDIReservoirSampleUV(reservoir));
            visible=RAB_GetConservativeVisibility(surface,sample)?1:0;
            color=sample.contribution*RTXDI_GetDIReservoirInvPdf(reservoir)*visible;
        }
        if(p.diagnostic==1)color=surface.data.normal.xyz*0.5+0.5;
        if(p.diagnostic==2)color=surface.data.position.www;
        if(p.diagnostic==3)color=visible.xxx;
        if(p.diagnostic==4)color=float3(surface.data.reprojection.xy/float2(p.width,p.height)*-8+0.5,0.5);
        if(p.diagnostic==5) {
            uint id=RTXDI_IsValidDIReservoir(reservoir)?RAB_LoadLightInfo(RTXDI_GetDIReservoirLightIndex(reservoir),false).identity.x:0;
            color=RTXDI_IsValidDIReservoir(reservoir)?float3((id*71)%251,(id*137)%251,(id*197)%251)/250.0:0;
        }
        if(p.diagnostic==6)color=(reservoir.M/float(p.maxHistory+1)).xxx;
        if(p.diagnostic==7)color=reservoir.weightSum.xxx;
        if(p.diagnostic==8)color=surface.data.reprojection.www;
        color=lerp(surface.data.source.rgb,color,p.weight);
        // Match the SDK's final-visibility convention; keep M when discarding an occluded sample.
        RTXDI_StoreVisibilityInDIReservoir(reservoir,visible.xxx,true);
        RTXDI_StoreDIReservoir(reservoir,layout,xy,2);
    }
    vk::RawBufferStore<float4>(p.pixels+uint64_t(xy.y*p.width+xy.x)*16,float4(color,visible));
}
