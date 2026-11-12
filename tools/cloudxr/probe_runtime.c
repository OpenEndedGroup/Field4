#include <openxr/openxr.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
int main(void) {
    uint32_t count=0; XrResult r=xrEnumerateInstanceExtensionProperties(NULL,0,&count,NULL);
    printf("enumerate: %d, extensions: %u\n",r,count); if(XR_FAILED(r))return 1;
    XrExtensionProperties *p=calloc(count,sizeof(*p));
    for(uint32_t i=0;i<count;i++)p[i].type=XR_TYPE_EXTENSION_PROPERTIES;
    r=xrEnumerateInstanceExtensionProperties(NULL,count,&count,p); if(XR_FAILED(r))return 1;
    int vk2=0;
    for(uint32_t i=0;i<count;i++){puts(p[i].extensionName);if(!strcmp(p[i].extensionName,"XR_KHR_vulkan_enable2"))vk2=1;}
    free(p);printf("Field Vulkan enable2: %s\n",vk2?"available":"MISSING");
    return vk2?0:2;
}
