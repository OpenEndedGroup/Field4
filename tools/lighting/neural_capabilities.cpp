// Read-only physical-device discovery; no device features are enabled by this probe.
#include <vulkan/vulkan.h>
#include <iostream>
#include <vector>
#include <set>
#include <string>
int main() {
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};app.apiVersion=VK_API_VERSION_1_3;
    VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};ci.pApplicationInfo=&app;
    VkInstance instance{};if(vkCreateInstance(&ci,nullptr,&instance)!=VK_SUCCESS)return 1;
    uint32_t n=0;vkEnumeratePhysicalDevices(instance,&n,nullptr);std::vector<VkPhysicalDevice> devices(n);
    vkEnumeratePhysicalDevices(instance,&n,devices.data());std::cout<<"[";bool first=true;
    for(auto device:devices) {
        uint32_t count=0;vkEnumerateDeviceExtensionProperties(device,nullptr,&count,nullptr);
        std::vector<VkExtensionProperties> exts(count);vkEnumerateDeviceExtensionProperties(device,nullptr,&count,exts.data());
        std::set<std::string> names;for(auto& e:exts)names.insert(e.extensionName);
        bool optical=names.count(VK_NV_OPTICAL_FLOW_EXTENSION_NAME),coop=names.count(VK_NV_COOPERATIVE_VECTOR_EXTENSION_NAME);
        VkPhysicalDeviceOpticalFlowFeaturesNV of{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPTICAL_FLOW_FEATURES_NV};
        VkPhysicalDeviceCooperativeVectorFeaturesNV cv{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_COOPERATIVE_VECTOR_FEATURES_NV};
        VkPhysicalDeviceFeatures2 f{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
        if(optical){of.pNext=f.pNext;f.pNext=&of;}if(coop){cv.pNext=f.pNext;f.pNext=&cv;}vkGetPhysicalDeviceFeatures2(device,&f);
        VkPhysicalDeviceOpticalFlowPropertiesNV op{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPTICAL_FLOW_PROPERTIES_NV};
        VkPhysicalDeviceCooperativeVectorPropertiesNV cp{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_COOPERATIVE_VECTOR_PROPERTIES_NV};
        VkPhysicalDeviceProperties2 p{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};
        if(optical){op.pNext=p.pNext;p.pNext=&op;}if(coop){cp.pNext=p.pNext;p.pNext=&cp;}vkGetPhysicalDeviceProperties2(device,&p);
        if(!first)std::cout<<",";first=false;
        std::cout<<"{\"device\":\""<<p.properties.deviceName<<"\",\"apiVersion\":"<<p.properties.apiVersion
            <<",\"driverVersion\":"<<p.properties.driverVersion<<",\"enabledByProbe\":false,\"opticalFlow\":{\"supported\":"<<of.opticalFlow
            <<",\"gridMask\":"<<op.supportedOutputGridSizes<<",\"cost\":"<<op.costSupported<<",\"hints\":"<<op.hintSupported
            <<",\"minimum\":["<<op.minWidth<<","<<op.minHeight<<"],\"maximum\":["<<op.maxWidth<<","<<op.maxHeight<<"],\"formats\":{";
        auto getFormats=reinterpret_cast<PFN_vkGetPhysicalDeviceOpticalFlowImageFormatsNV>(vkGetInstanceProcAddr(instance,"vkGetPhysicalDeviceOpticalFlowImageFormatsNV"));
        bool firstFormat=true;for(auto usage:{VK_OPTICAL_FLOW_USAGE_INPUT_BIT_NV,VK_OPTICAL_FLOW_USAGE_OUTPUT_BIT_NV,VK_OPTICAL_FLOW_USAGE_COST_BIT_NV}) {
            if(!firstFormat)std::cout<<",";firstFormat=false;std::cout<<"\""<<usage<<"\":[";
            if(optical && getFormats) {
                VkOpticalFlowImageFormatInfoNV info{VK_STRUCTURE_TYPE_OPTICAL_FLOW_IMAGE_FORMAT_INFO_NV};info.usage=usage;
                count=0;getFormats(device,&info,&count,nullptr);std::vector<VkOpticalFlowImageFormatPropertiesNV> formats(count);
                for(auto& format:formats)format.sType=VK_STRUCTURE_TYPE_OPTICAL_FLOW_IMAGE_FORMAT_PROPERTIES_NV;
                getFormats(device,&info,&count,formats.data());for(uint32_t i=0;i<count;i++){if(i)std::cout<<",";std::cout<<formats[i].format;}
            }std::cout<<"]";
        }
        std::cout<<"}},\"cooperativeVector\":{\"inference\":"<<cv.cooperativeVector<<",\"training\":"<<cv.cooperativeVectorTraining
            <<",\"trainingFp16\":"<<cp.cooperativeVectorTrainingFloat16Accumulation<<",\"trainingFp32\":"<<cp.cooperativeVectorTrainingFloat32Accumulation
            <<",\"stageMask\":"<<cp.cooperativeVectorSupportedStages<<",\"maxComponents\":"<<cp.maxCooperativeVectorComponents<<",\"combinations\":[";
        auto getCoop=reinterpret_cast<PFN_vkGetPhysicalDeviceCooperativeVectorPropertiesNV>(vkGetInstanceProcAddr(instance,"vkGetPhysicalDeviceCooperativeVectorPropertiesNV"));
        if(coop && getCoop) {
            count=0;getCoop(device,&count,nullptr);std::vector<VkCooperativeVectorPropertiesNV> combos(count);
            for(auto& c:combos)c.sType=VK_STRUCTURE_TYPE_COOPERATIVE_VECTOR_PROPERTIES_NV;getCoop(device,&count,combos.data());
            for(uint32_t i=0;i<count;i++){auto& c=combos[i];if(i)std::cout<<",";std::cout<<"["<<c.inputType<<","<<c.inputInterpretation<<","<<c.matrixInterpretation<<","<<c.biasInterpretation<<","<<c.resultType<<","<<c.transpose<<"]";}
        }
        std::cout<<"]},\"queues\":[";count=0;vkGetPhysicalDeviceQueueFamilyProperties(device,&count,nullptr);
        std::vector<VkQueueFamilyProperties> queues(count);vkGetPhysicalDeviceQueueFamilyProperties(device,&count,queues.data());
        for(uint32_t i=0;i<count;i++){if(i)std::cout<<",";std::cout<<"{\"family\":"<<i<<",\"flags\":"<<queues[i].queueFlags<<",\"count\":"<<queues[i].queueCount<<"}";}
        std::cout<<"]}";
    }
    std::cout<<"]\n";vkDestroyInstance(instance,nullptr);
}
