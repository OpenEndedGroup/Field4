// Field's private C ABI. No SDK structs, STL objects, or allocation ownership cross this boundary.
#include <vulkan/vulkan.h>
#include <nvsdk_ngx_vk.h>
#include <nvsdk_ngx_helpers.h>
#include <nvsdk_ngx_helpers_vk.h>
#include <nvsdk_ngx_helpers_dlssd_vk.h>
#include <nvsdk_ngx_helpers_dlssd.h>
#include <cstdint>
#include <cstddef>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <memory>
#include <set>

#define API extern "C" __attribute__((visibility("default")))
namespace {
std::mutex gate; // NGX host API is not thread safe, including discovery and teardown.
thread_local std::string error;
std::wstring sdkPath, cachePath;
constexpr auto project = "23b4726b-f804-4bb6-9bf4-11eab67dd0d1";
struct View;
struct Runtime { VkDevice device; VkInstance instance; VkPhysicalDevice physical; NVSDK_NGX_Parameter* caps = nullptr; std::set<View*> views; };
struct View { Runtime* runtime; NVSDK_NGX_Handle* handle = nullptr; NVSDK_NGX_Parameter* params = nullptr; uint32_t width, height; };
// Versioned explicit layout, independently checked by Kotlin. All resources are borrowed.
struct Image { uint64_t image, view; uint32_t format, width, height, aspect; };
static_assert(sizeof(Image)==32 && offsetof(Image,format)==16 && offsetof(Image,aspect)==28);
int result(NVSDK_NGX_Result r, const char* action) {
    if(NVSDK_NGX_SUCCEED(r)) return 0;
    char message[256]; std::snprintf(message,sizeof(message),"%s: NGX 0x%08x",action,unsigned(r));error=message;return -1;
}
NVSDK_NGX_FeatureCommonInfo common(const wchar_t** paths) {
    NVSDK_NGX_FeatureCommonInfo c{};c.PathListInfo={paths,1};return c;
}
NVSDK_NGX_FeatureDiscoveryInfo discovery(NVSDK_NGX_FeatureCommonInfo* c) {
    NVSDK_NGX_FeatureDiscoveryInfo d{};d.SDKVersion=NVSDK_NGX_Version_API;
    d.Identifier.IdentifierType=NVSDK_NGX_Application_Identifier_Type_Project_Id;
    d.Identifier.v.ProjectDesc={project,NVSDK_NGX_ENGINE_TYPE_CUSTOM,"Field4"};
    d.ApplicationDataPath=cachePath.c_str();d.FeatureInfo=c;d.FeatureID=NVSDK_NGX_Feature_SuperSampling;return d;
}
NVSDK_NGX_Resource_VK resource(const Image& i,bool write) {
    return NVSDK_NGX_Create_ImageView_Resource_VK(reinterpret_cast<VkImageView>(i.view),reinterpret_cast<VkImage>(i.image),
        {i.aspect,0,1,0,1},VkFormat(i.format),i.width,i.height,write);
}
}
API int f4ngx_abi() { return 4; }
API int f4ngx_image_size() { return sizeof(Image); }
API const char* f4ngx_error() { return error.c_str(); }
// Paths are UTF-8 converted by the caller to native wchar_t (UTF-32 on this Linux ABI).
API void f4ngx_configure(const wchar_t* sdk,const wchar_t* cache) {
    std::lock_guard<std::mutex> lock(gate);sdkPath=sdk;cachePath=cache;
}
API int f4ngx_extensions(uint64_t instance,uint64_t physical,int rr,char* output,uint32_t capacity) {
    std::lock_guard<std::mutex> lock(gate);
    const wchar_t* paths[]={sdkPath.c_str()};auto c=common(paths);auto d=discovery(&c);
    if(rr)d.FeatureID=NVSDK_NGX_Feature_RayReconstruction;
    uint32_t count=0;VkExtensionProperties* props=nullptr;
    auto r=physical ? NVSDK_NGX_VULKAN_GetFeatureDeviceExtensionRequirements(reinterpret_cast<VkInstance>(instance),reinterpret_cast<VkPhysicalDevice>(physical),&d,&count,&props)
                    : NVSDK_NGX_VULKAN_GetFeatureInstanceExtensionRequirements(&d,&count,&props);
    if(result(r,"query SR extensions"))return -1;
    std::string names;for(uint32_t i=0;i<count;i++){names+=props[i].extensionName;names+='\n';}
    if(names.size()+1>capacity){error="extension output buffer too small";return -1;}
    std::memcpy(output,names.c_str(),names.size()+1);return 0;
}
API int f4ngx_open(uint64_t instance,uint64_t physical,uint64_t device,void** output) {
    std::lock_guard<std::mutex> lock(gate);*output=nullptr;
    const wchar_t* paths[]={sdkPath.c_str()};auto c=common(paths);auto d=discovery(&c);
    auto rt=std::make_unique<Runtime>();rt->device=reinterpret_cast<VkDevice>(device);
    rt->instance=reinterpret_cast<VkInstance>(instance);rt->physical=reinterpret_cast<VkPhysicalDevice>(physical);
    if(result(NVSDK_NGX_VULKAN_Init_with_ProjectID(project,NVSDK_NGX_ENGINE_TYPE_CUSTOM,"Field4",cachePath.c_str(),
        reinterpret_cast<VkInstance>(instance),reinterpret_cast<VkPhysicalDevice>(physical),rt->device,vkGetInstanceProcAddr,vkGetDeviceProcAddr,&c),"NGX init"))return -1;
    // Reinitialize before querying requirements: after Shutdown1 the Linux runtime can return
    // NotImplemented until Init reloads the feature. Extension discovery still happens pre-device.
    NVSDK_NGX_FeatureRequirement req{};
    if(result(NVSDK_NGX_VULKAN_GetFeatureRequirements(reinterpret_cast<VkInstance>(instance),reinterpret_cast<VkPhysicalDevice>(physical),&d,&req),"SR requirements")) {
        NVSDK_NGX_VULKAN_Shutdown1(rt->device);return -1;
    }
    if(req.FeatureSupported!=0) {
        error="SR feature support flags="+std::to_string(req.FeatureSupported);
        NVSDK_NGX_VULKAN_Shutdown1(rt->device);return -1;
    }
    int available=0, needsDriver=0, initResult=0;
    auto r=NVSDK_NGX_VULKAN_GetCapabilityParameters(&rt->caps);
    if(NVSDK_NGX_SUCCEED(r)) {
        rt->caps->Get(NVSDK_NGX_Parameter_SuperSampling_Available,&available);
        rt->caps->Get(NVSDK_NGX_Parameter_SuperSampling_NeedsUpdatedDriver,&needsDriver);
        rt->caps->Get(NVSDK_NGX_Parameter_SuperSampling_FeatureInitResult,&initResult);
    }
    if(result(r,"NGX capabilities") || !available || needsDriver) {
        if(NVSDK_NGX_SUCCEED(r)) error="SR unavailable: available="+std::to_string(available)+" needsUpdatedDriver="+std::to_string(needsDriver)+" featureInitResult="+std::to_string(initResult);
        if(rt->caps)NVSDK_NGX_VULKAN_DestroyParameters(rt->caps);
        NVSDK_NGX_VULKAN_Shutdown1(rt->device);return -1;
    }
    *output=rt.release();return 0;
}
API int f4ngx_rr_available(void* runtime) {
    std::lock_guard<std::mutex> lock(gate);auto* rt=static_cast<Runtime*>(runtime);
    const wchar_t* paths[]={sdkPath.c_str()};auto c=common(paths);auto d=discovery(&c);d.FeatureID=NVSDK_NGX_Feature_RayReconstruction;
    NVSDK_NGX_FeatureRequirement req{};
    if(result(NVSDK_NGX_VULKAN_GetFeatureRequirements(rt->instance,rt->physical,&d,&req),"RR requirements"))return -1;
    if(req.FeatureSupported!=0){error="RR support flags="+std::to_string(req.FeatureSupported);return -1;}
    int available=0,driver=0;rt->caps->Get(NVSDK_NGX_Parameter_SuperSamplingDenoising_Available,&available);
    rt->caps->Get(NVSDK_NGX_Parameter_SuperSamplingDenoising_NeedsUpdatedDriver,&driver);
    if(!available||driver){error="RR unavailable: available="+std::to_string(available)+" needsUpdatedDriver="+std::to_string(driver);return -1;}
    return 0;
}
API int f4ngx_rr_optimal(void* runtime,uint32_t width,uint32_t height,int mode,uint32_t* sizes) {
    std::lock_guard<std::mutex> lock(gate);float sharp=0;
    return result(NGX_DLSSD_GET_OPTIMAL_SETTINGS(static_cast<Runtime*>(runtime)->caps,width,height,NVSDK_NGX_PerfQuality_Value(mode),
        sizes,sizes+1,sizes+2,sizes+3,sizes+4,sizes+5,&sharp),"RR optimal settings");
}
API int f4ngx_rr_create(void* runtime,uint64_t command,uint32_t width,uint32_t height,uint32_t outWidth,uint32_t outHeight,int mode,void** output) {
    std::lock_guard<std::mutex> lock(gate);*output=nullptr;auto v=std::make_unique<View>();
    v->runtime=static_cast<Runtime*>(runtime);v->width=width;v->height=height;
    if(result(NVSDK_NGX_VULKAN_AllocateParameters(&v->params),"allocate RR parameters"))return -1;
    NVSDK_NGX_DLSSD_Create_Params p{};p.InWidth=width;p.InHeight=height;p.InTargetWidth=outWidth;p.InTargetHeight=outHeight;
    p.InPerfQualityValue=NVSDK_NGX_PerfQuality_Value(mode);p.InDenoiseMode=NVSDK_NGX_DLSS_Denoise_Mode_DLUnified;
    p.InRoughnessMode=NVSDK_NGX_DLSS_Roughness_Mode_Packed;p.InUseHWDepth=NVSDK_NGX_DLSS_Depth_Type_HW;
    p.InFeatureCreateFlags=NVSDK_NGX_DLSS_Feature_Flags_IsHDR|NVSDK_NGX_DLSS_Feature_Flags_MVLowRes|NVSDK_NGX_DLSS_Feature_Flags_AutoExposure;
    auto r=NGX_VULKAN_CREATE_DLSSD_EXT1(v->runtime->device,reinterpret_cast<VkCommandBuffer>(command),1,1,&v->handle,v->params,&p);
    if(result(r,"create RR view")){NVSDK_NGX_VULKAN_DestroyParameters(v->params);return -1;}
    v->runtime->views.insert(v.get());*output=v.release();return 0;
}
API int f4ngx_rr_evaluate(void* view,uint64_t command,const Image* images,const float* frame,const float* matrices,int reset) {
    std::lock_guard<std::mutex> lock(gate);auto* v=static_cast<View*>(view);
    auto color=resource(images[0],false),depth=resource(images[1],false),motion=resource(images[2],false),output=resource(images[3],true);
    auto diffuse=resource(images[4],false),specular=resource(images[5],false),normal=resource(images[6],false);
    NVSDK_NGX_VK_DLSSD_Eval_Params p{};p.pInColor=&color;p.pInOutput=&output;p.pInDepth=&depth;p.pInMotionVectors=&motion;
    p.pInDiffuseAlbedo=&diffuse;p.pInSpecularAlbedo=&specular;p.pInNormals=&normal;p.pInRoughness=&normal;
    NVSDK_NGX_Resource_VK distance{};
    if(images[7].image) { distance=resource(images[7],false);p.pInSpecularHitDistance=&distance; }
    p.InRenderSubrectDimensions={images[0].width,images[0].height};p.InJitterOffsetX=frame[0];p.InJitterOffsetY=frame[1];
    p.InFrameTimeDeltaInMsec=frame[2];p.InPreExposure=frame[3];p.InExposureScale=1.f;p.InMVScaleX=1.f;p.InMVScaleY=1.f;p.InReset=reset;
    p.pInWorldToViewMatrix=const_cast<float*>(matrices);p.pInViewToClipMatrix=const_cast<float*>(matrices+16);
    return result(NGX_VULKAN_EVALUATE_DLSSD_EXT(reinterpret_cast<VkCommandBuffer>(command),v->handle,v->params,&p),"evaluate RR");
}
API int f4ngx_optimal(void* runtime,uint32_t width,uint32_t height,int mode,uint32_t* sizes) {
    std::lock_guard<std::mutex> lock(gate);float sharp=0;
    return result(NGX_DLSS_GET_OPTIMAL_SETTINGS(static_cast<Runtime*>(runtime)->caps,width,height,NVSDK_NGX_PerfQuality_Value(mode),
        sizes,sizes+1,sizes+2,sizes+3,sizes+4,sizes+5,&sharp),"optimal settings");
}
API int f4ngx_create(void* runtime,uint64_t command,uint32_t width,uint32_t height,uint32_t outWidth,uint32_t outHeight,int mode,void** output) {
    std::lock_guard<std::mutex> lock(gate);*output=nullptr;auto v=std::make_unique<View>();
    v->runtime=static_cast<Runtime*>(runtime);v->width=width;v->height=height;
    if(result(NVSDK_NGX_VULKAN_AllocateParameters(&v->params),"allocate view parameters"))return -1;
    NVSDK_NGX_DLSS_Create_Params p{};p.Feature={width,height,outWidth,outHeight,NVSDK_NGX_PerfQuality_Value(mode)};
    p.InFeatureCreateFlags=NVSDK_NGX_DLSS_Feature_Flags_IsHDR | NVSDK_NGX_DLSS_Feature_Flags_MVLowRes | NVSDK_NGX_DLSS_Feature_Flags_AutoExposure;
    auto r=NGX_VULKAN_CREATE_DLSS_EXT1(v->runtime->device,reinterpret_cast<VkCommandBuffer>(command),1,1,&v->handle,v->params,&p);
    if(result(r,"create DLSS view")){NVSDK_NGX_VULKAN_DestroyParameters(v->params);return -1;}
    v->runtime->views.insert(v.get());*output=v.release();return 0;
}
API int f4ngx_evaluate(void* view,uint64_t command,const Image* images,const float* frame,int reset) {
    std::lock_guard<std::mutex> lock(gate);auto* v=static_cast<View*>(view);
    auto color=resource(images[0],false),depth=resource(images[1],false),motion=resource(images[2],false),output=resource(images[3],true);
    NVSDK_NGX_VK_DLSS_Eval_Params p{};p.Feature.pInColor=&color;p.Feature.pInOutput=&output;p.pInDepth=&depth;p.pInMotionVectors=&motion;
    // Creation uses the SDK optimal size; evaluation uses the actual validated input dimensions.
    p.InRenderSubrectDimensions={images[0].width,images[0].height};p.InJitterOffsetX=frame[0];p.InJitterOffsetY=frame[1];
    p.InFrameTimeDeltaInMsec=frame[2];p.InPreExposure=frame[3];p.InExposureScale=1.f;p.InMVScaleX=1.f;p.InMVScaleY=1.f;p.InReset=reset;
    return result(NGX_VULKAN_EVALUATE_DLSS_EXT(reinterpret_cast<VkCommandBuffer>(command),v->handle,v->params,&p),"evaluate DLSS");
}
API uint64_t f4ngx_memory(void* runtime) {
    std::lock_guard<std::mutex> lock(gate);unsigned long long bytes=0;
    if(NVSDK_NGX_FAILED(NGX_DLSS_GET_STATS(static_cast<Runtime*>(runtime)->caps,&bytes)))return 0;
    return bytes;
}
// Caller guarantees all recorded work has completed, and destroys every view before its runtime.
API void f4ngx_destroy(void* view) {
    std::lock_guard<std::mutex> lock(gate);auto* v=static_cast<View*>(view);
    v->runtime->views.erase(v);NVSDK_NGX_VULKAN_ReleaseFeature(v->handle);NVSDK_NGX_VULKAN_DestroyParameters(v->params);delete v;
}
API void f4ngx_close(void* runtime) {
    std::lock_guard<std::mutex> lock(gate);auto* r=static_cast<Runtime*>(runtime);
    // Device shutdown is a final safety net for a live box whose close was omitted. Field has
    // already waited for the device before this call; individual view retirement normally wins.
    for(auto* v:r->views){NVSDK_NGX_VULKAN_ReleaseFeature(v->handle);NVSDK_NGX_VULKAN_DestroyParameters(v->params);delete v;}
    NVSDK_NGX_VULKAN_DestroyParameters(r->caps);NVSDK_NGX_VULKAN_Shutdown1(r->device);delete r;
}
