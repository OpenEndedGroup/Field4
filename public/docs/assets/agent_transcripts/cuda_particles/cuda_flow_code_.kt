import afield.cuda.CudaVulkan

// One CUDA context per renderer, on the same GPU (matched by UUID).
val cuda = CudaVulkan.forRenderer(renderer)

// Each particle is a float4: x, y in world units (about -2..2), a phase, and a size.
val count = 200_000
val particles = cuda.sharedBuffer("particles", count * 16L)

// CUDA C, compiled for this GPU by NVRTC when the box runs.
val program =
    cuda.compile(
        """
    extern "C" __global__ void seed(float4* p, int n) {
        int i = blockIdx.x * blockDim.x + threadIdx.x;
        if (i >= n) return;
        unsigned h = i * 747796405u + 2891336453u;
        h = ((h >> ((h >> 28) + 4u)) ^ h) * 277803737u;
        h = (h >> 22) ^ h;
        float a = (h & 0xffffu) / 65535.0f * 6.2831853f;
        float r = 2.0f * sqrtf((h >> 16) / 65535.0f);
        p[i] = make_float4(r * cosf(a), r * sinf(a), a, 0.4f + 0.3f * r);
    }

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
val seed = program.function("seed")
val flow = program.function("flow")
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

frames {
    repeat(600) { frame() }
    println("cuda particles running on ${cuda.device.name}: ${cuda.framesWithCuda} frames with CUDA")
}
