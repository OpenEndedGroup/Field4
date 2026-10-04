// Browser UI policy; native Vulkan rendering and the offscreen texture producer are separate.
function useAngleVulkan(platform, override) {
  return platform === "linux" && override === "1";
}
module.exports = { useAngleVulkan };
