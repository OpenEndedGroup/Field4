// A feedback StackSL program, ping-ponging on the CPU.
//
// The gallery's feedback entries are the ones a still cannot describe: the program samples the
// PREVIOUS frame, so the image is the end of a loop rather than a function of the pixel. On the
// GPU that is a `PingPongRenderToTexture`. Here it is two `CpuTexture2D` buffers and a `for` loop,
// which is the whole point — the same program, the same emitted GLSL, no device.
//
// This box also shows the other half of the backend: the host shader is yours. `StackSLCpu`
// renders whatever fragment shader you hand it, so the `{{ stack.auto.program }}` template below
// is the same kind of thing a live module would carry, `sampler2D` and push constant included.

import afield.stacksl.cpu.CpuTexture2D
import afield.stacksl.cpu.StackSLCpu

run {

    // The `diffusion-feedback` gallery program, verbatim: blur the previous frame, keep 97.5% of
    // it, and add threshold-ed `fbm` blobs that drift with time. Chosen over the other feedback
    // entry because `blur` is a NINE-TAP sampler — it proves the CPU texture path in a way a
    // single `sample` does not — and because a diffusion accumulates spatial structure, so the
    // frames below are visibly a history rather than four similar pictures.
    val program = """
        0.004 blur
        0.975 *
        uv 5 * 0.25t + fbm 0.62 threshold 0.05 *
        +
        saturate
    """.trimIndent()

    val template = """
        #version 450
        layout(location = 0) in vec2 fragUV;
        layout(location = 0) out vec4 outColor;

        layout(set = 0, binding = 0) uniform sampler2D source;
        layout(push_constant) uniform Push { vec4 params; } pc;

        {{ stack.auto.program }}

        void main() {
            vec4 stackInput = vec4(fragUV, 0.0, pc.params.x);
            vec3 c = program(stackInput).rgb;
            if (any(isnan(c)) || any(isinf(c))) c = vec3(0.0);
            outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
        }
    """.trimIndent()

    val dim = 112
    val frames = 72
    val keepEvery = 12

    // Frame zero's history is a cleared black target, exactly as the GPU harness starts.
    var history = CpuTexture2D(dim, dim, FloatArray(dim * dim * 4) { if (it % 4 == 3) 1f else 0f })
    val kept = mutableListOf<IntArray>()

    val started = System.nanoTime()
    for (frame in 0 until frames) {
        val raster = StackSLCpu.renderProgram(
            program = program,
            template = template,
            width = dim,
            height = dim,
            pushParams = floatArrayOf(frame / 12f, 0f, 0f, 0f),
            samplers = history
        )
        history = CpuTexture2D(raster)          // the ping-pong swap, in one line
        if (frame % keepEvery == keepEvery - 1) kept += raster.toArgb()
    }
    println("$frames CPU feedback frames at ${dim}x$dim in %.2fs".format((System.nanoTime() - started) / 1e9))
    println("frames shown: " + (1..kept.size).joinToString(", ") { "${it * keepEvery}" })

    // Stitch the kept frames into one horizontal strip, with a two-pixel gutter.
    val gutter = 2
    val stripWidth = kept.size * dim + (kept.size - 1) * gutter
    val strip = IntArray(stripWidth * dim) { 0xFF101014.toInt() }
    kept.forEachIndexed { index, frame ->
        val x0 = index * (dim + gutter)
        for (y in 0 until dim) System.arraycopy(frame, y * dim, strip, y * stripWidth + x0, dim)
    }
    display(afield.util.PngEncoder.encode(strip, stripWidth, dim))

    // The thing to notice: nothing in the program draws the smeared structure. It is the previous
    // frame, read back through a nine-tap `blur`, faded by `0.975 *`, ninety-six times over. If
    // `texture()` were being ignored you would see only the current frame's hard-edged blobs, the
    // same in every panel.
}
