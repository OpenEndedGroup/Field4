// Standalone, optional diagnostic; not linked into Field. Uses public NGX discovery APIs only.
#include <vulkan/vulkan.h>
#include <nvsdk_ngx_vk.h>
#include <iostream>
#include <vector>
#include <string>
#include <set>
#include <filesystem>

int main(int argc,char** argv) {
    if(argc!=2) { std::cerr<<"usage: ngx_probe SDK/lib/Linux_x86_64/rel\n";return 2; }
    std::wstring path=std::filesystem::absolute(argv[1]).wstring();const wchar_t* paths[]={path.c_str()};
    NVSDK_NGX_FeatureCommonInfo common{};common.PathListInfo={paths,1};
    NVSDK_NGX_FeatureDiscoveryInfo info{};
    info.SDKVersion=NVSDK_NGX_Version_API;
    info.Identifier.IdentifierType=NVSDK_NGX_Application_Identifier_Type_Project_Id;
    info.Identifier.v.ProjectDesc={"23b4726b-f804-4bb6-9bf4-11eab67dd0d1",NVSDK_NGX_ENGINE_TYPE_CUSTOM,"Field4 feasibility probe"};
    info.ApplicationDataPath=L"/tmp/field4-ngx-probe";info.FeatureInfo=&common;
    const NVSDK_NGX_Feature features[]={NVSDK_NGX_Feature_SuperSampling,NVSDK_NGX_Feature_RayReconstruction,NVSDK_NGX_Feature_FrameGeneration};
    const char* names[]={"SuperResolution","RayReconstruction","FrameGeneration"};
    std::set<std::string> extensionNames;
    for(auto feature:features) {
        info.FeatureID=feature;uint32_t count=0;VkExtensionProperties* props=nullptr;
        auto result=NVSDK_NGX_VULKAN_GetFeatureInstanceExtensionRequirements(&info,&count,&props);
        std::cout<<"instanceExtensions feature="<<feature<<" result=0x"<<std::hex<<result<<std::dec<<" count="<<count<<"\n";
        if(NVSDK_NGX_SUCCEED(result)) for(uint32_t i=0;i<count;i++)extensionNames.insert(props[i].extensionName);
    }
    std::vector<const char*> extensions;for(auto& name:extensionNames)extensions.push_back(name.c_str());
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};app.pApplicationName="Field4 NGX discovery";app.apiVersion=VK_API_VERSION_1_3;
    VkInstanceCreateInfo create{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};create.pApplicationInfo=&app;
    create.enabledExtensionCount=extensions.size();create.ppEnabledExtensionNames=extensions.data();
    VkInstance instance{};auto result=vkCreateInstance(&create,nullptr,&instance);
    if(result!=VK_SUCCESS) {std::cerr<<"vkCreateInstance="<<result<<"\n";return 1;}
    uint32_t count=0;vkEnumeratePhysicalDevices(instance,&count,nullptr);std::vector<VkPhysicalDevice> devices(count);
    vkEnumeratePhysicalDevices(instance,&count,devices.data());
    for(auto device:devices) {
        VkPhysicalDeviceProperties properties;vkGetPhysicalDeviceProperties(device,&properties);
        std::cout<<"device="<<properties.deviceName<<"\n";
        info.FeatureID=NVSDK_NGX_Feature_SuperSampling;
        uint32_t deviceExtensionCount=0;VkExtensionProperties* deviceExtensions=nullptr;
        auto extensionResult=NVSDK_NGX_VULKAN_GetFeatureDeviceExtensionRequirements(instance,device,&info,&deviceExtensionCount,&deviceExtensions);
        std::cout<<"SR device extensions result=0x"<<std::hex<<extensionResult<<std::dec<<" count="<<deviceExtensionCount<<"\n";
        if(NVSDK_NGX_SUCCEED(extensionResult))for(uint32_t e=0;e<deviceExtensionCount;e++)std::cout<<"  "<<deviceExtensions[e].extensionName<<"\n";
        for(int i=0;i<3;i++) {
            info.FeatureID=features[i];NVSDK_NGX_FeatureRequirement requirements{};
            auto r=NVSDK_NGX_VULKAN_GetFeatureRequirements(instance,device,&info,&requirements);
            std::cout<<names[i]<<" result=0x"<<std::hex<<r<<std::dec;
            if(NVSDK_NGX_SUCCEED(r)) std::cout<<" supportFlags="<<requirements.FeatureSupported<<" minArch="<<requirements.MinHWArchitecture<<" minOS="<<requirements.MinOSVersion;
            std::cout<<"\n";
        }
    }
    vkDestroyInstance(instance,nullptr);
}
