#include "field_opendlssnr.h"
#include "nr_graph.h"
#include "numeric.h"
#include "reference.h"
#include "vk_context.h"
#include <cmath>
#include <cstddef>
#include <map>
#include <memory>
#include <mutex>
#include <sstream>

static_assert(sizeof(F4NrImage) == 32 && sizeof(F4NrControls) == 64);
namespace {
// Upstream volk globals are private to this hidden DSO. Serialize and reload
// dispatch on every entry, including destruction, so separate Field devices
// cannot overwrite one another's calls.
std::recursive_mutex gate;
thread_local std::string error;
template <class F> int checked(F &&f) {
  std::lock_guard lock(gate);
  try {
    f();
    error.clear();
    return 0;
  } catch (const std::exception &e) {
    error = e.what();
    return -1;
  } catch (...) {
    error = "Unknown native exception";
    return -1;
  }
}
struct Plan {
  vk::DeviceRequirements req;
  VkPhysicalDevice8BitStorageFeatures storage{
      VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_8BIT_STORAGE_FEATURES};
  VkPhysicalDeviceHostQueryResetFeatures host{
      VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_HOST_QUERY_RESET_FEATURES};
  VkPhysicalDeviceVulkanMemoryModelFeatures memory{
      VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_MEMORY_MODEL_FEATURES};
};
void activate(VkInstance instance, VkDevice device = VK_NULL_HANDLE) {
  VK_CHECK(volkInitialize());
  volkLoadInstance(instance);
  if (device)
    volkLoadDevice(device);
}
bool contains(const std::vector<VkExtensionProperties> &extensions,
              const char *name) {
  for (const auto &e : extensions)
    if (!strcmp(e.extensionName, name))
      return true;
  return false;
}
void verifyFeatures(const void *desired, const void *actual, size_t size) {
  auto want = (const uint32_t *)((const char *)desired + 16);
  auto got = (const uint32_t *)((const char *)actual + 16);
  for (size_t i = 0; i < (size - 16) / 4; i++)
    if (want[i] == 1 && got[i] != 1)
      throw std::runtime_error(
          "Required OpenDLSS-NR feature unavailable (sType " +
          std::to_string(((const VkBaseOutStructure *)desired)->sType) +
          ", field " + std::to_string(i) + ")");
}
struct Runtime {
  vk::Context context;
  nr::Model model;
  nr::Kernels kernels;
  Runtime(VkInstance instance, VkPhysicalDevice physical, VkDevice device,
          uint32_t family, const char *weights, const char *shaders)
      : context(instance, physical, device, family),
        model(context, weights, true), kernels(context, shaders) {
    kernels.setSiluTable(ref::siluTable());
  }
};
std::map<std::string, std::weak_ptr<Runtime>> runtimes;
struct Image {
  VkImage image{};
  VkDeviceMemory memory{};
  VkImageView view{};
};
struct Pass {
  std::shared_ptr<Runtime> runtime;
  nr::Geometry geometry;
  std::unique_ptr<nr::Graph> graph;
  nr::Activation *features{};
  VkCommandPool pool{};
  VkCommandBuffer network{};
  VkDescriptorSetLayout setLayout{};
  VkPipelineLayout layout{};
  VkDescriptorPool descriptors{};
  VkDescriptorSet sets[2]{};
  VkSampler sampler{};
  VkPipeline preprocess{}, composite{};
  std::vector<VkDescriptorSet> graphSets;
  Image history[2];
  F4NrImage images[4]{};
  uint32_t parity = 0;
  bool initialized = false;
  float blendScale = 0;
  uint64_t historyBytes = 0;
  Pass(std::shared_ptr<Runtime> r, const F4NrImage *inputs, const char *shaders)
      : runtime(std::move(r)),
        geometry(nr::Geometry::fromValid(inputs[0].width, inputs[0].height)) {
    try {
      auto &c = runtime->context;
      auto d = c.device();
      memcpy(images, inputs, sizeof(images));
      if (inputs[0].width < 33 || inputs[0].height < 33 ||
          inputs[0].width > 8192 || inputs[0].height > 8192)
        throw std::runtime_error("NR dimensions must be 33..8192");
      if (inputs[1].format != VK_FORMAT_R16G16B16A16_SFLOAT ||
          inputs[1].width != inputs[0].width ||
          inputs[1].height != inputs[0].height)
        throw std::runtime_error("NR output must be matching RGBA16F");
      for (int i = 0; i < 4; i++)
        if ((i < 2 || inputs[i].image) &&
            (!inputs[i].view || inputs[i].width != inputs[0].width ||
             inputs[i].height != inputs[0].height))
          throw std::runtime_error("Invalid NR image dimensions/view");
      graph = std::make_unique<nr::Graph>(c, runtime->model, runtime->kernels,
                                          geometry, nr::Graph::Options{});
      features = graph->allocate("Field NR features",
                                 geometry.fullWidth * geometry.fullHeight, 16,
                                 nr::Format::F32);
      VkCommandPoolCreateInfo pi{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
      pi.queueFamilyIndex = c.queueFamily();
      VK_CHECK(vkCreateCommandPool(d, &pi, nullptr, &pool));
      VkCommandBufferAllocateInfo ai{
          VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
      ai.commandPool = pool;
      ai.level = VK_COMMAND_BUFFER_LEVEL_SECONDARY;
      ai.commandBufferCount = 1;
      VK_CHECK(vkAllocateCommandBuffers(d, &ai, &network));
      VkCommandBufferInheritanceInfo inheritance{
          VK_STRUCTURE_TYPE_COMMAND_BUFFER_INHERITANCE_INFO};
      VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
      bi.flags = VK_COMMAND_BUFFER_USAGE_SIMULTANEOUS_USE_BIT;
      bi.pInheritanceInfo = &inheritance;
      VK_CHECK(vkBeginCommandBuffer(network, &bi));
      c.descriptorCapture = &graphSets;
      try {
        graph->record(network, *features);
      } catch (...) {
        c.descriptorCapture = nullptr;
        throw;
      }
      c.descriptorCapture = nullptr;
      VK_CHECK(vkEndCommandBuffer(network));
      uint16_t half =
          nr::auxHalf(runtime->model.tensor(70, 0, "blend_scale"), 0, 0);
      blendScale = num::f16ToF32(half);
      VkDescriptorSetLayoutBinding bindings[8]{};
      for (uint32_t i = 0; i < 8; i++)
        bindings[i] = {i,
                       i < 4 ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                             : (i < 6 ? VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                                      : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                       1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
      VkDescriptorSetLayoutCreateInfo li{
          VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
      li.bindingCount = 8;
      li.pBindings = bindings;
      VK_CHECK(vkCreateDescriptorSetLayout(d, &li, nullptr, &setLayout));
      VkPushConstantRange push{VK_SHADER_STAGE_COMPUTE_BIT, 0, 80};
      VkPipelineLayoutCreateInfo pli{
          VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
      pli.setLayoutCount = 1;
      pli.pSetLayouts = &setLayout;
      pli.pushConstantRangeCount = 1;
      pli.pPushConstantRanges = &push;
      VK_CHECK(vkCreatePipelineLayout(d, &pli, nullptr, &layout));
      auto pipeline = [&](const char *name, VkPipeline &result) {
        VkShaderModule module =
            c.loadShaderModule(std::string(shaders) + "/" + name + ".spv");
        VkComputePipelineCreateInfo pci{
            VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
        pci.layout = layout;
        pci.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
        pci.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        pci.stage.module = module;
        pci.stage.pName = "main";
        VK_CHECK(vkCreateComputePipelines(d, VK_NULL_HANDLE, 1, &pci, nullptr,
                                          &result));
      };
      pipeline("field_nr_preprocess", preprocess);
      pipeline("field_nr_composite", composite);
      VkSamplerCreateInfo si{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
      si.magFilter = si.minFilter = VK_FILTER_LINEAR;
      si.addressModeU = si.addressModeV = si.addressModeW =
          VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
      VK_CHECK(vkCreateSampler(d, &si, nullptr, &sampler));
      for (auto &h : history) {
        VkImageCreateInfo ii{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
        ii.imageType = VK_IMAGE_TYPE_2D;
        ii.format = VK_FORMAT_R16G16B16A16_SFLOAT;
        ii.extent = {inputs[0].width, inputs[0].height, 1};
        ii.mipLevels = ii.arrayLayers = 1;
        ii.samples = VK_SAMPLE_COUNT_1_BIT;
        ii.tiling = VK_IMAGE_TILING_OPTIMAL;
        ii.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
                   VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        VK_CHECK(vkCreateImage(d, &ii, nullptr, &h.image));
        VkMemoryRequirements mr;
        vkGetImageMemoryRequirements(d, h.image, &mr);
        VkPhysicalDeviceMemoryProperties mp;
        vkGetPhysicalDeviceMemoryProperties(c.physical(), &mp);
        uint32_t type = UINT32_MAX;
        for (uint32_t i = 0; i < mp.memoryTypeCount; i++)
          if ((mr.memoryTypeBits & (1u << i)) &&
              (mp.memoryTypes[i].propertyFlags &
               VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) {
            type = i;
            break;
          }
        if (type == UINT32_MAX)
          throw std::runtime_error("No NR image memory type");
        VkMemoryAllocateInfo mi{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        mi.allocationSize = mr.size;
        historyBytes += mr.size;
        mi.memoryTypeIndex = type;
        VK_CHECK(vkAllocateMemory(d, &mi, nullptr, &h.memory));
        VK_CHECK(vkBindImageMemory(d, h.image, h.memory, 0));
        VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        vi.image = h.image;
        vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
        vi.format = ii.format;
        vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        VK_CHECK(vkCreateImageView(d, &vi, nullptr, &h.view));
      }
      VkDescriptorPoolSize sizes[] = {
          {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 8},
          {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 4},
          {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 4}};
      VkDescriptorPoolCreateInfo dpi{
          VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
      dpi.maxSets = 2;
      dpi.poolSizeCount = 3;
      dpi.pPoolSizes = sizes;
      VK_CHECK(vkCreateDescriptorPool(d, &dpi, nullptr, &descriptors));
      for (uint32_t j = 0; j < 2; j++) {
        VkDescriptorSetAllocateInfo dai{
            VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        dai.descriptorPool = descriptors;
        dai.descriptorSetCount = 1;
        dai.pSetLayouts = &setLayout;
        VK_CHECK(vkAllocateDescriptorSets(d, &dai, &sets[j]));
        VkDescriptorImageInfo im[6] = {
            {sampler, (VkImageView)inputs[0].view,
             static_cast<VkImageLayout>(inputs[0].layout)},
            {sampler, history[1 - j].view, VK_IMAGE_LAYOUT_GENERAL},
            {sampler,
             (VkImageView)(inputs[2].view ? inputs[2].view : inputs[0].view),
             static_cast<VkImageLayout>(inputs[2].view ? inputs[2].layout : inputs[0].layout)},
            {sampler,
             (VkImageView)(inputs[3].view ? inputs[3].view : inputs[0].view),
             static_cast<VkImageLayout>(inputs[3].view ? inputs[3].layout : inputs[0].layout)},
            {VK_NULL_HANDLE, history[j].view, VK_IMAGE_LAYOUT_GENERAL},
            {VK_NULL_HANDLE, (VkImageView)inputs[1].view,
             VK_IMAGE_LAYOUT_GENERAL}};
        VkDescriptorBufferInfo buffers[] = {
            {features->buffer.buffer, 0, VK_WHOLE_SIZE},
            {graph->head().buffer.buffer, 0, VK_WHOLE_SIZE}};
        VkWriteDescriptorSet writes[8]{};
        for (uint32_t i = 0; i < 8; i++) {
          writes[i] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
          writes[i].dstSet = sets[j];
          writes[i].dstBinding = i;
          writes[i].descriptorCount = 1;
          writes[i].descriptorType = bindings[i].descriptorType;
          if (i < 4)
            writes[i].pImageInfo = &im[i];
          else if (i < 6)
            writes[i].pBufferInfo = &buffers[i - 4];
          else
            writes[i].pImageInfo = &im[i - 2];
        }
        vkUpdateDescriptorSets(d, 8, writes, 0, nullptr);
      }
    } catch (...) {
      release();
      throw;
    }
  }
  void record(VkCommandBuffer cmd, F4NrControls controls) {
    if (!std::isfinite(controls.tone) || !std::isfinite(controls.structure) ||
        !std::isfinite(controls.skin) || fabs(controls.tone) > 65504 ||
        fabs(controls.structure) > 65504 || fabs(controls.skin) > 65504 ||
        controls.style < 0 || controls.style > 2 || controls.encoding > 2 ||
        !std::isfinite(controls.intensity) || controls.intensity < 0 ||
        controls.intensity > 1 || !std::isfinite(controls.paper_white) ||
        controls.paper_white <= 0)
      throw std::runtime_error("Invalid NR controls");
    if (controls.temporal && (!images[2].image || !images[3].image))
      throw std::runtime_error(
          "Temporal NR requires explicit motion and depth");
    // One queue orders successive frames; include readers as well as writers
    // before reusing scratch/history.
    VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    mb.srcAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
    mb.dstAccessMask = mb.srcAccessMask;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                         VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 1, &mb, 0,
                         nullptr, 0, nullptr);
    if (!initialized) {
      for (auto &h : history) {
        VkImageMemoryBarrier ib{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        ib.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        ib.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        ib.srcQueueFamilyIndex = ib.dstQueueFamilyIndex =
            VK_QUEUE_FAMILY_IGNORED;
        ib.image = h.image;
        ib.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        ib.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                             VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0,
                             nullptr, 1, &ib);
        VkClearColorValue zero{};
        vkCmdClearColorImage(cmd, h.image, VK_IMAGE_LAYOUT_GENERAL, &zero, 1,
                             &ib.subresourceRange);
      }
      runtime->context.transferBarrier(cmd);
      controls.history_valid = 0;
      initialized = true;
    }
    controls.blend_scale = blendScale;
    struct Push {
      uint32_t fw, fh, w, h;
      F4NrControls c;
    } push{geometry.fullWidth, geometry.fullHeight, geometry.validWidth,
           geometry.validHeight, controls};
    static_assert(sizeof(Push) == 80);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, preprocess);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, 1,
                            &sets[parity], 0, nullptr);
    vkCmdPushConstants(cmd, layout, VK_SHADER_STAGE_COMPUTE_BIT, 0,
                       sizeof(push), &push);
    vkCmdDispatch(cmd, (geometry.fullWidth + 7) / 8,
                  (geometry.fullHeight + 7) / 8, 1);
    runtime->context.computeBarrier(cmd);
    vkCmdExecuteCommands(cmd, 1, &network);
    runtime->context.computeBarrier(cmd);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, composite);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, 1,
                            &sets[parity], 0, nullptr);
    vkCmdPushConstants(cmd, layout, VK_SHADER_STAGE_COMPUTE_BIT, 0,
                       sizeof(push), &push);
    vkCmdDispatch(cmd, (geometry.validWidth + 7) / 8,
                  (geometry.validHeight + 7) / 8, 1);
    parity = 1 - parity;
  }
  ~Pass() { release(); }
  void release() {
    auto d = runtime->context.device();
    vkDestroyCommandPool(d, pool, nullptr);
    if (!graphSets.empty())
      vkFreeDescriptorSets(d, runtime->context.descriptorPool(),
                           uint32_t(graphSets.size()), graphSets.data());
    vkDestroyDescriptorPool(d, descriptors, nullptr);
    vkDestroyPipeline(d, preprocess, nullptr);
    vkDestroyPipeline(d, composite, nullptr);
    vkDestroyPipelineLayout(d, layout, nullptr);
    vkDestroyDescriptorSetLayout(d, setLayout, nullptr);
    vkDestroySampler(d, sampler, nullptr);
    for (auto &h : history) {
      vkDestroyImageView(d, h.view, nullptr);
      vkDestroyImage(d, h.image, nullptr);
      vkFreeMemory(d, h.memory, nullptr);
    }
  }
};
} // namespace
extern "C" {
uint32_t f4nr_abi() { return 2; }
uint32_t f4nr_image_size() { return sizeof(F4NrImage); }
uint32_t f4nr_controls_size() { return sizeof(F4NrControls); }
const char *f4nr_error() { return error.c_str(); }
const char *f4nr_extensions() {
  return "VK_KHR_cooperative_matrix\nVK_EXT_shader_float8\nVK_KHR_pipeline_"
         "executable_properties\nVK_NV_cooperative_matrix2\nVK_NV_cuda_kernel_"
         "launch\nVK_KHR_shader_clock\nVK_NV_shader_sm_builtins\n";
}
int f4nr_probe(uint64_t instance, uint64_t physical, void **result) {
  return checked([&] {
    *result = nullptr;
    activate((VkInstance)instance);
    auto p = std::make_unique<Plan>();
    uint32_t n = 0;
    VK_CHECK(vkEnumerateDeviceExtensionProperties((VkPhysicalDevice)physical,
                                                  nullptr, &n, nullptr));
    std::vector<VkExtensionProperties> ext(n);
    VK_CHECK(vkEnumerateDeviceExtensionProperties((VkPhysicalDevice)physical,
                                                  nullptr, &n, ext.data()));
    for (auto name : p->req.extensions)
      if (!contains(ext, name))
        throw std::runtime_error(std::string("Missing NR extension ") + name);
    vk::DeviceRequirements actual;
    vkGetPhysicalDeviceFeatures2((VkPhysicalDevice)physical, &actual.features);
#define VERIFY(field)                                                          \
  verifyFeatures(&p->req.field, &actual.field, sizeof(actual.field))
    VERIFY(f11);
    VERIFY(f12);
    VERIFY(f13);
    VERIFY(coop);
    VERIFY(coop2);
    VERIFY(fp8);
    VERIFY(executable);
    VERIFY(clock);
    VERIFY(sm);
#undef VERIFY
    if (!actual.features.features.shaderInt16 ||
        !actual.features.features.shaderInt64)
      throw std::runtime_error("NR requires shaderInt16 and shaderInt64");
    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties((VkPhysicalDevice)physical, &props);
    if (props.apiVersion < VK_API_VERSION_1_3)
      throw std::runtime_error("NR requires Vulkan 1.3");
    *result = p.release();
  });
}
int f4nr_apply(void *handle, uint64_t address) {
  return checked([&] {
    auto &r = ((Plan *)handle)->req;
    auto *features = (VkPhysicalDeviceFeatures2 *)address;
    features->features.shaderInt16 = features->features.shaderInt64 = VK_TRUE;
    auto *tail = (VkBaseOutStructure *)features;
    bool f11 = false, f12 = false, f13 = false;
    bool memory = false, float16 = false, host = false, storage8 = false;
    for (auto *s = tail; s; s = s->pNext) {
      tail = s;
      switch (s->sType) {
      case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES: {
        auto *f = (VkPhysicalDeviceVulkan11Features *)s;
        f->storageBuffer16BitAccess = f->uniformAndStorageBuffer16BitAccess =
            VK_TRUE;
        f11 = true;
        break;
      }
      case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES: {
        auto *f = (VkPhysicalDeviceVulkan12Features *)s;
        f->storageBuffer8BitAccess = f->uniformAndStorageBuffer8BitAccess =
            f->shaderFloat16 = f->shaderInt8 = f->vulkanMemoryModel =
                f->vulkanMemoryModelDeviceScope = f->hostQueryReset =
                    f->bufferDeviceAddress = VK_TRUE;
        f12 = true;
        break;
      }
      case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES: {
        auto *f = (VkPhysicalDeviceVulkan13Features *)s;
        f->subgroupSizeControl = f->computeFullSubgroups = f->maintenance4 =
            VK_TRUE;
        f13 = true;
        break;
      }
      case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_MEMORY_MODEL_FEATURES: {
        auto *f = (VkPhysicalDeviceVulkanMemoryModelFeatures *)s;
        f->vulkanMemoryModel = f->vulkanMemoryModelDeviceScope = VK_TRUE;
        memory = true;
        break;
      }
      case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES: {
        auto *f = (VkPhysicalDeviceShaderFloat16Int8Features *)s;
        f->shaderInt8 = f->shaderFloat16 = VK_TRUE;
        float16 = true;
        break;
      }
      case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_HOST_QUERY_RESET_FEATURES:
        ((VkPhysicalDeviceHostQueryResetFeatures *)s)->hostQueryReset = VK_TRUE;
        host = true;
        break;
      case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_8BIT_STORAGE_FEATURES:
        ((VkPhysicalDevice8BitStorageFeatures *)s)->storageBuffer8BitAccess =
            ((VkPhysicalDevice8BitStorageFeatures *)s)
                ->uniformAndStorageBuffer8BitAccess = VK_TRUE;
        storage8 = true;
        break;
      default:
        break;
      }
    }
    if (!f11 || !f13)
      throw std::runtime_error(
          "Field NR requires the existing Vulkan11/13 feature chain");
    if (!f12) {
      auto *plan = (Plan *)handle;
      auto append = [&](void *next) {
        tail->pNext = (VkBaseOutStructure *)next;
        tail = tail->pNext;
      };
      if (!float16)
        throw std::runtime_error("Field split chain lacks float16/int8");
      if (!storage8) {
        plan->storage.storageBuffer8BitAccess =
            plan->storage.uniformAndStorageBuffer8BitAccess = VK_TRUE;
        append(&plan->storage);
      }
      if (!host) {
        plan->host.hostQueryReset = VK_TRUE;
        append(&plan->host);
      }
      if (!memory) {
        plan->memory.vulkanMemoryModel =
            plan->memory.vulkanMemoryModelDeviceScope = VK_TRUE;
        append(&plan->memory);
      }
    }
    r.coop.pNext = nullptr;
    tail->pNext = (VkBaseOutStructure *)&r.sm;
  });
}
void f4nr_plan_close(void *p) {
  std::lock_guard lock(gate);
  delete (Plan *)p;
}
int f4nr_create(uint64_t instance, uint64_t physical, uint64_t device,
                uint32_t family, const char *model, const char *shaders,
                const F4NrImage *images, void **result) {
  return checked([&] {
    *result = nullptr;
    activate((VkInstance)instance, (VkDevice)device);
    std::string key = std::to_string(device) + "|" + model + "|" + shaders;
    auto runtime = runtimes[key].lock();
    if (!runtime) {
      runtime = std::make_shared<Runtime>(
          (VkInstance)instance, (VkPhysicalDevice)physical, (VkDevice)device,
          family, model, shaders);
      runtimes[key] = runtime;
    }
    *result = new Pass(runtime, images, shaders);
  });
}
int f4nr_record(void *pass, uint64_t command, const F4NrControls *controls) {
  return checked([&] {
    auto *p = (Pass *)pass;
    activate(p->runtime->context.instance(), p->runtime->context.device());
    p->record((VkCommandBuffer)command, *controls);
  });
}
int f4nr_destroy(void *pass) {
  return checked([&] {
    auto *p = (Pass *)pass;
    activate(p->runtime->context.instance(), p->runtime->context.device());
    delete p;
  });
}
uint64_t f4nr_head_buffer(void *pass) {
  std::lock_guard lock(gate);
  return (uint64_t)((Pass *)pass)->graph->head().buffer.buffer;
}
uint64_t f4nr_allocated_bytes(void *pass) {
  std::lock_guard lock(gate);
  auto *p = (Pass *)pass;
  return p->runtime->context.allocatedBufferBytes + p->historyBytes;
}
uint32_t f4nr_padded_width(void *pass) {
  std::lock_guard lock(gate);
  return ((Pass *)pass)->geometry.fullWidth;
}
}
