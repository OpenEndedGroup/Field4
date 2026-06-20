package afield.stdlib

import afield.Box
import afield.ToHTML
import afield.four.Renderer
import afield.four.buffers.BufferParameters
import afield.four.buffers.FrameBufferGroup
import afield.four.buffers.VulkanBuffer
import afield.four.device.CommandBuffer
import afield.four.device.Fence
import afield.four.modules.ComputeMesh
import afield.four.modules.ComputeModule
import afield.four.modules.ComputeModulePointer
import afield.four.modules.Parameters
import afield.four.modules.fromContext
import afield.four.modules.hostReadBarrier
import afield.four.modules.pointer
import afield.stacksl.StackSLCompileOptions
import afield.stacksl.StackSLCompileResult
import afield.stacksl.StackSLCompiler
import afield.stacksl.StackSLSeverity
import afield.stacksl.compileStackSLToGlsl
import org.joml.Vector4f
import org.joml.Vector4fc
import org.joml.primitives.Rectanglef
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
import org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.max

var Box.stackSL by Inheritance<String?>()

fun Box.stackSLPrefix(): String {
    val base = id.take(12).replace('-', '_').ifBlank { "box" }
    return "stacksl_$base"
}

fun Box.compileStackSL(
    source: String = stackSL ?: "",
    prefix: String = stackSLPrefix(),
    entrypoint: String = "main",
    sampleTextures: List<String> = listOf("source"),
    tolerant: Boolean = false,
    declareSamplers: Boolean = true,
    externalizeConstants: Boolean = false
): StackSLCompileResult =
    StackSLCompiler.compile(
        source,
        StackSLCompileOptions(
            prefix = prefix,
            entrypoint = entrypoint,
            sampleTextures = sampleTextures,
            tolerant = tolerant,
            declareSamplers = declareSamplers,
            externalizeConstants = externalizeConstants
        )
    )

fun Box.stackSLToGlsl(
    source: String = stackSL ?: "",
    prefix: String = stackSLPrefix(),
    entrypoint: String = "main",
    sampleTextures: List<String> = listOf("source"),
    tolerant: Boolean = false,
    declareSamplers: Boolean = true,
    externalizeConstants: Boolean = false
): String =
    compileStackSLToGlsl(
        source = source,
        prefix = prefix,
        entrypoint = entrypoint,
        sampleTextures = sampleTextures,
        tolerant = tolerant,
        declareSamplers = declareSamplers,
        externalizeConstants = externalizeConstants
    )

fun slEval(
    source: String,
    input: Vector4fc,
    renderer: Renderer = fromContext()
): Vec4 =
    StackSLEvalCache.forRenderer(renderer).run(source, listOf(input)).first()

fun slGlsl(
    source: String,
    prefix: String = "stacksl",
    entrypoint: String = "main",
    sampleTextures: List<String> = listOf("source"),
    tolerant: Boolean = false,
    declareSamplers: Boolean = true,
    externalizeConstants: Boolean = false
): String =
    compileStackSLToGlsl(
        source = source,
        prefix = prefix,
        entrypoint = entrypoint,
        sampleTextures = sampleTextures,
        tolerant = tolerant,
        declareSamplers = declareSamplers,
        externalizeConstants = externalizeConstants
    )

fun slSweep(
    source: String,
    range: Pair<Vector4fc, Vector4fc>,
    samples: Int = 100,
    renderer: Renderer = fromContext()
): StackSLSweep {
    require(samples > 0) { "slSweep sample count must be positive" }
    val inputs = interpolateSweepInputs(range.first, range.second, samples)
    val values = StackSLEvalCache.forRenderer(renderer).run(source, inputs)
    return StackSLSweep(
        source = source,
        start = Vector4f(range.first),
        end = Vector4f(range.second),
        inputs = inputs.map { Vector4f(it) },
        values = values
    )
}

data class StackSLSweep(
    val source: String,
    val start: Vec4,
    val end: Vec4,
    val inputs: List<Vec4>,
    val values: List<Vec4>
) : ToHTML {
    val size: Int get() = values.size

    operator fun get(index: Int): Vec4 = values[index]

    fun channel(channel: Int): List<Float> = values.map {
        when (channel) {
            0 -> it.x
            1 -> it.y
            2 -> it.z
            3 -> it.w
            else -> throw IllegalArgumentException("channel must be 0, 1, 2, or 3")
        }
    }

    override fun toHTML(): String = stackSLSweepHtml(this)

    override fun toString(): String =
        "StackSLSweep(source=\"$source\", samples=$size, first=${values.firstOrNull()}, last=${values.lastOrNull()})"
}

val StackSLSweep.h: String
    get() = toHTML()

private object StackSLEvalCache {
    private val evaluators = WeakHashMap<Renderer, WeakReference<StackSLEvaluator>>()

    @Synchronized
    fun forRenderer(renderer: Renderer): StackSLEvaluator {
        val existing = evaluators[renderer]?.get()
        if (existing != null) return existing
        val evaluator = StackSLEvaluator(renderer)
        evaluators[renderer] = WeakReference(evaluator)
        return evaluator
    }
}

private class StackSLEvaluator(private val renderer: Renderer) {
    private var capacity = 0
    private var currentSource: String? = null
    private var inputBuffer: VulkanBuffer? = null
    private var outputBuffer: VulkanBuffer? = null
    private var inputParameters: BufferParameters? = null
    private var outputParameters: BufferParameters? = null
    private var module: ComputeModulePointer? = null

    @Synchronized
    fun run(source: String, inputs: List<Vector4fc>): List<Vec4> {
        require(inputs.isNotEmpty()) { "StackSL evaluator needs at least one input sample" }
        ensureCapacity(inputs.size)
        ensureModule(source)
        writeInputs(inputs)
        execute(inputs.size)
        return readOutputs(inputs.size)
    }

    private fun ensureCapacity(samples: Int) {
        if (capacity >= samples) return

        val nextCapacity = max(samples, max(100, capacity * 2))
        val bytes = nextCapacity * 16L
        if (inputBuffer == null) {
            inputBuffer = VulkanBuffer(
                renderer = renderer,
                requestedSize = bytes,
                usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                reqMask = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
            )
            outputBuffer = VulkanBuffer(
                renderer = renderer,
                requestedSize = bytes,
                usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                reqMask = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
            )
            inputParameters = bufferParameters(STACKSL_INPUT_BUFFER, inputBuffer!!)
            outputParameters = bufferParameters(STACKSL_OUTPUT_BUFFER, outputBuffer!!)
        } else {
            inputBuffer!!.realloc(bytes)
            outputBuffer!!.realloc(bytes)
            inputParameters!!.size = bytes
            outputParameters!!.size = bytes
            inputBuffer!!.source = inputParameters!!.store()
            outputBuffer!!.source = outputParameters!!.store()
        }
        capacity = nextCapacity
    }

    private fun ensureModule(source: String) {
        if (module != null && currentSource == source) return

        val shader = stackSLComputeShader(source)
        val errors = mutableListOf<String>()
        val parameters = Parameters(
            renderer = renderer,
            compute = shader,
            computeSourceName = "stacksl-eval.comp",
            name = "StackSL Eval",
            ssboResolver = { requested ->
                when (requested.name) {
                    STACKSL_INPUT_BUFFER -> inputParameters
                    STACKSL_OUTPUT_BUFFER -> outputParameters
                    else -> null
                }
            },
            errorHandler = { desc, throwable ->
                errors += throwable?.message?.let { "$desc: $it" } ?: desc
            }
        )

        val existing = module
        if (existing == null) {
            val created = ComputeModule(parameters)
            if (!created.valid) {
                throw IllegalArgumentException(errors.ifEmpty { listOf("Could not compile StackSL eval compute shader") }.joinToString("\n"))
            }
            val pointer = created.pointer()
            pointer += STACKSL_DISPATCH_NAME to ComputeMesh(renderer, pointer)
            module = pointer
        } else {
            val reloaded = existing.reload(parameters) { desc, throwable ->
                errors += throwable?.message?.let { "$desc: $it" } ?: desc
            }
            if (!reloaded) {
                throw IllegalArgumentException(errors.ifEmpty { listOf("Could not reload StackSL eval compute shader") }.joinToString("\n"))
            }
        }
        currentSource = source
    }

    private fun writeInputs(inputs: List<Vector4fc>) {
        inputBuffer!!.mapAndWrite {
            inputs.forEach {
                put(it.x())
                put(it.y())
                put(it.z())
                put(it.w())
            }
        }
    }

    private fun execute(samples: Int) {
        val pointer = module ?: throw IllegalStateException("StackSL eval module was not initialized")
        val dispatch = pointer.current.meshes[STACKSL_DISPATCH_NAME] as? ComputeMesh
            ?: throw IllegalStateException("StackSL eval dispatch mesh was not initialized")
        dispatch.x = ceil(samples.toDouble() / STACKSL_WORKGROUP_SIZE.toDouble()).toInt()
        dispatch.y = 1
        dispatch.z = 1
        dispatch.initializePushConstantsFromModule(pointer)
        dispatch.push.set("sampleCount", samples)

        val command = CommandBuffer(renderer.computeCommandPool, primary = true, oneTimeSubmit = true)
        val fence = Fence(renderer.device, signaled = true)
        try {
            command.beginRecording()
            pointer.current.render(command, Rectanglef())
            hostReadBarrier(command, outputBuffer)
            command.endRecording()

            fence.reset()
            MemoryStack.stackPush().use { stack ->
                renderer.graphQueue.submit(
                    stack.pointers(command.vkCommandBuffer),
                    null,
                    null,
                    null,
                    fence
                )
            }
            fence.fenceWait()
        } finally {
            fence.cleanup()
            command.cleanup()
        }
    }

    private fun readOutputs(samples: Int): List<Vec4> {
        val out = ArrayList<Vec4>(samples)
        outputBuffer!!.mapAndRead {
            repeat(samples) {
                out += Vector4f(get(), get(), get(), get())
            }
        }
        return out
    }

    private fun bufferParameters(name: String, buffer: VulkanBuffer): BufferParameters {
        val group = FrameBufferGroup(renderer, n = 1, buffers = mutableListOf(buffer))
        val parameters = BufferParameters(name = name).apply {
            size = buffer.requestedSize
            storage()
            hosted()
            result = group
        }
        buffer.source = parameters.store()
        return parameters
    }
}

private fun stackSLComputeShader(source: String): String {
    val compiled = StackSLCompiler.compile(
        source,
        StackSLCompileOptions(
            prefix = STACKSL_FUNCTION_NAME,
            includeHeader = false,
            declareSamplers = false,
            externalizeConstants = false
        )
    )
    val errors = compiled.diagnostics.filter { it.severity == StackSLSeverity.ERROR }
    require(errors.isEmpty()) { errors.joinToString("\n") }
    require(compiled.analysis.requiredSamplers.isEmpty()) {
        "slEval/slSweep do not provide texture samplers; unsupported StackSL sampler(s): " +
            compiled.analysis.requiredSamplers.joinToString(", ")
    }

    return """
        #version 450

        layout(local_size_x = $STACKSL_WORKGROUP_SIZE, local_size_y = 1, local_size_z = 1) in;

        layout(set = 0, binding = 0, std430) readonly buffer $STACKSL_INPUT_BUFFER {
            vec4 values[];
        } stackslInput;

        layout(set = 0, binding = 1, std430) writeonly buffer $STACKSL_OUTPUT_BUFFER {
            vec4 values[];
        } stackslOutput;

        layout(push_constant) uniform StackSLEvalPush {
            int sampleCount;
        } stackslPc;

        ${compiled.glsl}

        void main() {
            uint index = gl_GlobalInvocationID.x;
            if (int(index) >= stackslPc.sampleCount) return;
            stackslOutput.values[index] = $STACKSL_FUNCTION_NAME(stackslInput.values[index]);
        }
    """.trimIndent()
}

private fun interpolateSweepInputs(start: Vector4fc, end: Vector4fc, samples: Int): List<Vec4> {
    if (samples == 1) return listOf(Vector4f(start))
    return List(samples) { index ->
        val t = index.toFloat() / (samples - 1).toFloat()
        Vector4f(
            start.x() + (end.x() - start.x()) * t,
            start.y() + (end.y() - start.y()) * t,
            start.z() + (end.z() - start.z()) * t,
            start.w() + (end.w() - start.w()) * t
        )
    }
}

private fun stackSLSweepHtml(sweep: StackSLSweep): String {
    val width = 560
    val height = 220
    val padLeft = 42
    val padRight = 16
    val padTop = 30
    val padBottom = 34
    val plotWidth = width - padLeft - padRight
    val plotHeight = height - padTop - padBottom
    val values = sweep.values
    val finiteValues = values.flatMap { listOf(it.x, it.y, it.z, it.w) }.filter { it.isFinite() }
    val rawMin = finiteValues.minOrNull() ?: 0f
    val rawMax = finiteValues.maxOrNull() ?: 1f
    val padding = max((rawMax - rawMin) * 0.08f, 0.001f)
    val minValue = if (rawMin == rawMax) rawMin - 1f else rawMin - padding
    val maxValue = if (rawMin == rawMax) rawMax + 1f else rawMax + padding
    val range = maxValue - minValue
    val zeroY = if (minValue <= 0f && maxValue >= 0f) padTop + (1.0 - ((0f - minValue) / range).toDouble()) * plotHeight else null
    val colors = listOf("#f87171", "#a3e635", "#60a5fa", "#9ca3af")
    val names = listOf("x", "y", "z", "w")
    val fieldCyan = "var(--field-cyan, rgb(72, 201, 255))"

    fun xFor(index: Int): Double =
        padLeft + if (values.size <= 1) 0.0 else index.toDouble() / (values.size - 1).toDouble() * plotWidth

    fun yFor(value: Float): Double =
        padTop + (1.0 - ((value - minValue) / range).toDouble()) * plotHeight

    fun channelValue(vec: Vec4, channel: Int): Float = when (channel) {
        0 -> vec.x
        1 -> vec.y
        2 -> vec.z
        else -> vec.w
    }

    val stats = names.indices.map { channel -> channelStats(values.map { channelValue(it, channel) }) }

    fun pathFor(channel: Int): String {
        val points = values.mapIndexedNotNull { index, vec ->
            val v = channelValue(vec, channel)
            if (!v.isFinite()) null else "${formatSvg(xFor(index))},${formatSvg(yFor(v))}"
        }
        return points.joinToString(" ")
    }

    fun markerLine(index: Int, color: String): String {
        val x = formatSvg(xFor(index))
        return "<line x1='$x' y1='$padTop' x2='$x' y2='${padTop + plotHeight}' stroke='$color' stroke-width='0.6' stroke-opacity='0.42'/>"
    }

    fun markerDot(index: Int, value: Float, color: String): String =
        "<circle cx='${formatSvg(xFor(index))}' cy='${formatSvg(yFor(value))}' r='2.4' fill='$color' stroke='#0b0f14' stroke-width='1'/>"

    return buildString {
        append("<div class='stacksl-sweep' style='font-family: \"Iosevka Web\", monospace; color: $fieldCyan; background: #101418; border: 1px solid #2b3440; border-radius: 6px; padding: 10px; display: inline-block;'>")
        append("<svg width='$width' height='$height' viewBox='0 0 $width $height' style='display:block; background:#0b0f14;'>")
        append("<rect x='$padLeft' y='$padTop' width='$plotWidth' height='$plotHeight' fill='#0f1720' stroke='#263241'/>")
        for (i in 0..4) {
            val y = padTop + i * plotHeight / 4.0
            append("<line x1='$padLeft' y1='${formatSvg(y)}' x2='${padLeft + plotWidth}' y2='${formatSvg(y)}' stroke='#1f2937' stroke-width='1'/>")
        }
        zeroY?.let {
            append("<line x1='$padLeft' y1='${formatSvg(it)}' x2='${padLeft + plotWidth}' y2='${formatSvg(it)}' stroke='#3b4656' stroke-width='1.2' stroke-dasharray='4 4'/>")
        }
        append("<text x='8' y='${padTop + 4}' fill='$fieldCyan' font-size='10'>${formatFloat(maxValue)}</text>")
        append("<text x='8' y='${padTop + plotHeight}' fill='$fieldCyan' font-size='10'>${formatFloat(minValue)}</text>")
        stats.forEachIndexed { channel, channelStats ->
            if (channelStats.minIndex >= 0) append(markerLine(channelStats.minIndex, colors[channel]))
            if (channelStats.maxIndex >= 0 && channelStats.maxIndex != channelStats.minIndex) append(markerLine(channelStats.maxIndex, colors[channel]))
        }
        colors.forEachIndexed { channel, color ->
            append("<polyline fill='none' stroke='$color' stroke-width='1.8' points='${pathFor(channel)}'/>")
        }
        stats.forEachIndexed { channel, channelStats ->
            if (channelStats.minIndex >= 0) append(markerDot(channelStats.minIndex, channelStats.min, colors[channel]))
            if (channelStats.maxIndex >= 0) append(markerDot(channelStats.maxIndex, channelStats.max, colors[channel]))
        }
        colors.forEachIndexed { channel, color ->
            val lx = padLeft + channel * 48
            val ly = height - 12
            append("<line x1='$lx' y1='$ly' x2='${lx + 18}' y2='$ly' stroke='$color' stroke-width='2'/>")
            append("<text x='${lx + 23}' y='${ly + 4}' fill='$fieldCyan' font-size='11'>${names[channel]}</text>")
        }
        append("</svg>")
        append("<div style='display:grid; grid-template-columns: auto auto auto; gap: 3px 14px; margin-top: 7px; font-size: 11px; color: #94a3b8;'>")
        append("<span>input 0</span><span>${vecSummary(sweep.inputs.firstOrNull())}</span><span>${vecSummary(values.firstOrNull())}</span>")
        append("<span>input ${values.lastIndex.coerceAtLeast(0)}</span><span>${vecSummary(sweep.inputs.lastOrNull())}</span><span>${vecSummary(values.lastOrNull())}</span>")
        append("</div>")
        append("<div style='display:grid; grid-template-columns: 22px repeat(4, minmax(54px, auto)); gap: 3px 12px; margin-top: 9px; font-size: 11px; color: #94a3b8;'>")
        append("<span></span><span style='color:$fieldCyan;'>avg</span><span style='color:$fieldCyan;'>min</span><span style='color:$fieldCyan;'>median</span><span style='color:$fieldCyan;'>max</span>")
        stats.forEachIndexed { channel, channelStats ->
            append("<span style='color:${colors[channel]};'>${names[channel]}</span>")
            append("<span>${formatFloat(channelStats.average)}</span>")
            append("<span>${formatFloat(channelStats.min)}</span>")
            append("<span>${formatFloat(channelStats.median)}</span>")
            append("<span>${formatFloat(channelStats.max)}</span>")
        }
        append("</div>")
        append("</div>")
    }
}

private data class SweepChannelStats(
    val average: Float,
    val min: Float,
    val median: Float,
    val max: Float,
    val minIndex: Int,
    val maxIndex: Int
)

private fun channelStats(values: List<Float>): SweepChannelStats {
    val indexedFinite = values.mapIndexedNotNull { index, value -> if (value.isFinite()) index to value else null }
    if (indexedFinite.isEmpty()) return SweepChannelStats(Float.NaN, Float.NaN, Float.NaN, Float.NaN, -1, -1)
    val finite = indexedFinite.map { it.second }.sorted()
    val midpoint = finite.size / 2
    val median = if (finite.size % 2 == 1) finite[midpoint] else (finite[midpoint - 1] + finite[midpoint]) * 0.5f
    val min = finite.first()
    val max = finite.last()
    return SweepChannelStats(
        average = finite.average().toFloat(),
        min = min,
        median = median,
        max = max,
        minIndex = indexedFinite.first { it.second == min }.first,
        maxIndex = indexedFinite.first { it.second == max }.first
    )
}

private fun formatSvg(value: Double): String = "%.2f".format(java.util.Locale.US, value)

private fun formatFloat(value: Float): String = "%.3g".format(java.util.Locale.US, value)

private fun vecSummary(value: Vec4?): String {
    if (value == null) return ""
    return "(${formatFloat(value.x)}, ${formatFloat(value.y)}, ${formatFloat(value.z)}, ${formatFloat(value.w)})"
}

private fun escapeHtml(text: String): String =
    text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

private const val STACKSL_FUNCTION_NAME = "stacksl_eval"
private const val STACKSL_INPUT_BUFFER = "StackSLInput"
private const val STACKSL_OUTPUT_BUFFER = "StackSLOutput"
private const val STACKSL_DISPATCH_NAME = "dispatch"
private const val STACKSL_WORKGROUP_SIZE = 64
