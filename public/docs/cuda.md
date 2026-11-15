# CUDA Beside the Renderer

On Linux with an NVIDIA GPU, a Field box can run CUDA kernels on the same GPU the renderer draws
with, reading and writing the renderer's own buffers in place. A kernel moves particles and a mesh
draws them from the same memory, and nothing is copied through the CPU. Native libraries built on
CUDA, such as Intel's Open Image Denoise, can work on Field's buffers the same way.

You need the NVIDIA driver; the CUDA toolkit is optional. Kernels written in CUDA C are compiled
when the box runs by NVRTC, which ships with the toolkit and with the `cuda-nvrtc` package that
PyTorch and CuPy environments install. Without NVRTC, load PTX instead
([Kernels without NVRTC](#kernels-without-nvrtc)). On macOS, or a machine without the driver, the
first CUDA call stops the box with a message saying why.

```diffstatic_runner {capture-profile="linux-nvidia" data-lang=kotlin screenshot="assets/cuda/particles.png" capture="native-window" native-wait-frames="3" max-width="1600" wait-output=true wait-output-kind="all" wait-output-contains="cuda particles running" embed=true hidden=true timeout="180" name="cuda-particles" alt="Two hundred thousand coloured dots, scattered as a disc and folded by a CUDA kernel's flow, drawn by a Field mesh from the same GPU buffer" macro-file="../../dev_docs/agents/transcripts/cuda_particles.yaml" capture-box="cuda_flow"}
// Built and launched from dev_docs/agents/transcripts/cuda_particles.yaml.
```

## A kernel that moves particles

The scene above has three boxes: a renderer, a box that runs CUDA, and a box that draws. The CUDA
box starts by asking for the renderer's CUDA session
([`CudaVulkan.forRenderer`](reference/graphics.md#cudavulkan-kt--cudavulkan-companion--forrenderer)) and a buffer both
sides can use ([`sharedBuffer`](reference/graphics.md#cudavulkan-kt--cudavulkan--sharedbuffer)):

```diffstatic {data-lang=kotlin}
import afield.cuda.CudaVulkan

// One CUDA context per renderer, on the same GPU (matched by UUID).
val cuda = CudaVulkan.forRenderer(renderer)

// Each particle is a float4: x, y in world units (about -2..2), a phase, and a size.
val count = 200_000
val particles = cuda.sharedBuffer("particles", count * 16L)
```

`particles.buffer` is an ordinary `VulkanBuffer` for shaders, and `particles.pointer` is the
address a kernel writes to. Asking again for `"particles"` at the same size returns the same buffer,
so rerunning the box keeps the particles where they are; a new size makes a new buffer.

Next comes the CUDA C, as a string compiled for this GPU by
[`compile`](reference/graphics.md#cudavulkan-kt--cudavulkan--compile). Two kernels: `seed` scatters the
particles in a disc, and `flow` moves each one a small step along a flow that turns slowly with `t`.
Kernels are declared `extern "C"` so they can be found by name:

```diffstatic {data-lang=kotlin}
val program =
    cuda.compile(
        """
    // Advect every particle through a flow that turns slowly with t.
    extern "C" __global__ void flow(float4* p, int n, float t, float dt) {
        int i = blockIdx.x * blockDim.x + threadIdx.x;
        if (i >= n) return;
        float4 q = p[i];
        float u = sinf(2.5f * q.y + t) - 0.35f * q.x;
        float v = cosf(2.5f * q.x - 0.7f * t + 0.3f * q.z) - 0.35f * q.y;
        q.x += dt * u;
        q.y += dt * v;
        p[i] = q;
    }
    """,
    )
val flow = program.function("flow")
```

The same source is not compiled twice, so rerunning the box with unchanged kernels costs nothing.
A compile error stops the box with NVRTC's message, line number included.

`launch` takes the number of blocks, the threads per block, the stream, and then the kernel's
arguments in order. `Int`, `Float`, `Long` and `Double` map to `int`, `float`, a pointer or 64-bit
integer, and `double`. [`cuda.run`](reference/graphics.md#cudavulkan-kt--cudavulkan--run) runs work once and waits for
it; [`cuda.everyFrame`](reference/graphics.md#cudavulkan-kt--cudavulkan--everyframe) runs it between every two frames:

```diffstatic {data-lang=kotlin}
val threads = 256
val blocks = (count + threads - 1) / threads

// Scatter the particles once, then move them between every two frames.
cuda.run { stream -> seed.launch(blocks, threads, stream, particles.pointer, count) }
var t = 0f
val dt = 1f / 60f
cuda.everyFrame("flow") { stream ->
    t += dt
    flow.launch(blocks, threads, stream, particles.pointer, count, t, dt)
}

// The draw box beneath this one reads the same memory.
prop<VulkanBuffer>(cudaParticles) = particles.buffer
prop<Int>(cudaParticleCount) = count
```

`everyFrame` keeps the kernel and the frames from overlapping. Each frame signals when it has
finished on the GPU, the kernel waits for that signal, and the next frame waits for the kernel.
The CPU waits on neither. Rerunning the box replaces the `"flow"` task, so editing the kernel and
rerunning changes the motion without a pause. `cuda.stopEveryFrame("flow")` stops it.

The draw box is lesson 3b's particle drawing from the [graphics guide](afield_four_graphics.md),
with the CUDA box as its parent. Its vertex shader reads the buffer through a buffer reference and
builds a small square for each particle:

```diffstatic {data-lang=kotlin}
var module = moduleHere {
    primitiveType = triangles
}
var m = Mesh()
module += "particles" to m
renderer += "cuda particles" to module

// Published by the CUDA box above with `prop`.
m.particles = { cudaParticles }
m.aspectRatio = { window.width / window.height.toFloat() }

// Six vertices per particle, no index buffer.
m.set(cudaParticleCount * 6, 0) {}

m
```

On an RTX 4090 the `flow` kernel takes well under a tenth of a millisecond for 200,000 particles.

## Kernels without NVRTC

`cuda.module(ptx)` loads PTX text instead of CUDA C. PTX is NVIDIA's portable assembly; the driver
compiles it for the GPU when it loads. Write it with `nvcc -ptx` on any machine with the toolkit,
take it from NVRTC elsewhere, or write it by hand.
`afield.cuda.CudaInteropProof.FILL_PTX` is a short hand-written example.
A PTX error stops the box with the driver's message and the offending line.

Field looks for NVRTC in this order: the `FIELD_NVRTC_LIBRARY` environment variable, the system
library path, then the conda package caches under `~/.local/share/micromamba`,
`~/.local/share/mamba`, `~/miniforge3`, `~/mambaforge`, `~/miniconda3` and `~/anaconda3`.

## Native Open Image Denoise on CUDA

The [path tracer](path_tracing.md) can allocate its accumulation buffer so that CUDA can open it
(`exportAccumulation = true`), and [`OidnComputeDenoiser`](path_tracing.md#denoising-a-still) can
do the same for its albedo and normal guides (`exportGuides = true`). Intel's native Open Image
Denoise, running on CUDA, then denoises the tracer's image in place. On an RTX 4090 at 1920 × 1080:

| | One denoise |
| --- | ---: |
| Native Open Image Denoise on CUDA, in place, `high` quality | 7.0 ms |
| The same, `balanced` quality | 5.3 ms |
| Field's Vulkan port of the network (`OidnComputeDenoiser`) | 12.3 ms |

The native result and Field's port agree to 71 dB. The figures include handing the buffers from
Vulkan to CUDA and back. Field does not ship native Open Image Denoise; the measurement harness
takes the directory holding `libOpenImageDenoise.so`.

??? aside "How the numbers were made"

    `afield.cuda.CudaInteropProof` runs the denoise, checks the output by reading it through Vulkan
    and through Open Image Denoise (they agree bit for bit), and also runs a PTX and an NVRTC kernel
    whose results are compared exactly against the values they must have written, including in the
    texture Field displays. It is the `cuda.interop.smoke` test, and skips where there is no CUDA.
    The binding's details and what the drivers insist on are in
    [CUDA_DRIVER_FFM.md](https://github.com/OpenEndedGroup/Field4/blob/main/dev_docs/graphics/CUDA_DRIVER_FFM.md).

## The lower level

`CudaVulkan` is built on a small binding of the CUDA driver API, `CudaDriver`, which can be used
directly. It covers devices (looked up by the Vulkan device's UUID), the primary context, streams,
events for timing, modules and kernel launch, device memory, and importing Vulkan memory and
timeline semaphores. `VulkanBuffer(..., exportOpaqueFd = true)` makes any buffer importable, and
`ExportedTimelineSemaphore` is the shared counter both APIs wait on and signal. The context is the
one the CUDA runtime uses too, so cuBLAS, cuDNN or ONNX Runtime opened in the same process can work
on the same pointers.

- [`CudaVulkan`](reference/graphics.md#cudavulkan-kt-cudavulkan): the box-facing session used above —
  [`forRenderer`](reference/graphics.md#cudavulkan-kt--cudavulkan-companion--forrenderer),
  [`sharedBuffer`](reference/graphics.md#cudavulkan-kt--cudavulkan--sharedbuffer),
  [`compile`](reference/graphics.md#cudavulkan-kt--cudavulkan--compile), [`module`](reference/graphics.md#cudavulkan-kt--cudavulkan--module),
  [`run`](reference/graphics.md#cudavulkan-kt--cudavulkan--run), [`everyFrame`](reference/graphics.md#cudavulkan-kt--cudavulkan--everyframe) and
  [`stopEveryFrame`](reference/graphics.md#cudavulkan-kt--cudavulkan--stopeveryframe).
- [`CudaDriver`](reference/graphics.md#cudadriver-kt-cudadriver): the driver API binding, with
  [`CudaContext`](reference/graphics.md#cudadriver-kt--cudadriver--cudacontext) and
  [`CudaFunction`](reference/graphics.md#cudadriver-kt--cudadriver--cudafunction).
- [`Nvrtc`](reference/graphics.md#nvrtc-kt-nvrtc): CUDA C to PTX.
- [`VulkanBuffer`](reference/graphics.md#vulkanbuffer-kt-vulkanbuffer) and
  [`ExportedTimelineSemaphore`](reference/graphics.md#exportedtimelinesemaphore-kt-exportedtimelinesemaphore):
  the Vulkan side.

## Where to go next

- [Field 4 Graphics](afield_four_graphics.md): the renderer, modules and meshes the draw box uses,
  and lesson 3b, which does the particle motion in a compute shader instead.
- [Progressive Path Tracing](path_tracing.md#denoising-a-still): the denoiser native Open Image
  Denoise stands in for.
- The starting point: `cuda_particles` in the [agent transcripts](agent_transcripts.md#cuda_particles),
  [`dev_docs/agents/transcripts/cuda_particles.yaml`](https://github.com/OpenEndedGroup/Field4/blob/main/dev_docs/agents/transcripts/cuda_particles.yaml).
- Source: [`afield/cuda/`](https://github.com/OpenEndedGroup/Field4/tree/main/src/jvmMain/kotlin/afield/cuda).

Open questions and next builds:

- Native Open Image Denoise from a box: a `denoiseAt` like `OidnComputeDenoiser`'s, with the
  denoise in the frame through `everyFrame`, would bring the 7 ms path to live scenes.
- cuBLAS and cuDNN through the same context: a learned shader or a small network evaluated on
  buffers the renderer draws.
- Windows: the binding loads `libcuda.so` only.
