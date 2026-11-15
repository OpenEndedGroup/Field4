// What the three viewer's Path trace imports when WebGPU is available (threejs_viewer.html,
// the `pathtrace` region). three itself stays external: see build.mjs.
export { WebGPUPathTracer, OIDNDenoiser } from 'three-gpu-pathtracer/webgpu';
export { initUNetFromURL } from 'oidn-webgpu';
