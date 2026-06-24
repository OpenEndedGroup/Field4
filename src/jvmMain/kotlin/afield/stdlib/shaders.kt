package afield.stdlib

import afield.*
import afield.four.Renderer
import afield.four.device.Instance.Companion.globalAsyncDebugHandler
import afield.four.modules.*
import afield.four.targets.RenderToTexture
import afield.four.texture.*
import afield.four.util.*
import afield.logger.lprintln
import afield.macros.Insert
import afield.macros.MacroRegistry
import afield.stacksl.StackSLCompileOptions
import afield.stacksl.StackSLCompiler
import afield.stacksl.StackSLSeverity
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.joml.Vector3f
import org.joml.Vector4f
import org.lwjgl.glfw.GLFW
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.Locale

// ------------------------------
// Box properties
// ------------------------------
var Box.fragment by Inheritance<String?>()
var Box.vertex by Inheritance<String?>()
var Box.vertex_fragment by Inheritance<String?>()
var Box.task by Inheritance<String?>()
var Box.mesh by Inheritance<String?>()
var Box.fragment_s by Inheritance<String?>()
var Box.vertex_s by Inheritance<String?>()
var Box.task_s by Inheritance<String?>()
var Box.mesh_s by Inheritance<String?>()
var Box._fragment by Delegation<String?>()
var Box._vertex by Delegation<String?>()
var Box._task by Delegation<String?>()
var Box._mesh by Delegation<String?>()

var Box.compute by Inheritance<String?>()
var Box.compute_s by Inheritance<String?>()
var Box._compute by Delegation<String?>()

var Box.raygen by Inheritance<String?>()
var Box.miss by Inheritance<String?>()
var Box.closesthit by Inheritance<String?>()
var Box.anyhit by Inheritance<String?>()
var Box.intersection by Inheritance<String?>()
var Box.callable by Inheritance<String?>()
var Box.raygen_s by Inheritance<String?>()
var Box.miss_s by Inheritance<String?>()
var Box.closesthit_s by Inheritance<String?>()
var Box.anyhit_s by Inheritance<String?>()
var Box.intersection_s by Inheritance<String?>()
var Box.callable_s by Inheritance<String?>()
var Box._raygen by Delegation<String?>()
var Box._miss by Delegation<String?>()
var Box._closesthit by Delegation<String?>()
var Box._anyhit by Delegation<String?>()
var Box._intersection by Delegation<String?>()
var Box._callable by Delegation<String?>()

var Box._shader by Delegation<ModulePointer?>()
var Box._computeShader by Delegation<ComputeModulePointer?>()
var Box._rayTracingShader by Delegation<RayTracingModulePointer?>()

var Box.meshShader by Inheritance<MeshModulePointer?>()
var Box._meshShader by Delegation<MeshModulePointer?>()
var Box._fieldProbe by Delegation<ShaderFieldProbe?>()
var Box._fieldProbeAutoController by Delegation<ShaderFieldAutoController?>()
var Box._fieldStatsBinding by Delegation<ShaderFieldStatsBinding?>()

val Box.shaderTemplate by Delegation<MutableMap<String, Any>>(
    defaultValue = { HashMap<String, Any>() })

val Box.stackAutoShaderSources by Delegation<MutableMap<String, String>>(
    defaultValue = { ConcurrentHashMap<String, String>() })

private val stackAutoShaderNameRegex = Regex("[A-Za-z_][A-Za-z0-9_]*")
private val stackAutoEntrypointNameRegex = Regex("[A-Za-z_][A-Za-z0-9_-]*")
private val stackAutoModuleOwners = Collections.synchronizedMap(WeakHashMap<Any, Box>())

private data class StackAutoShaderTarget(
    val name: String,
    val entrypoint: String? = null,
    val sampleTexture: String = "source"
) {
    val key: String
        get() = buildString {
            append(name)
            entrypoint?.let { append(":").append(it) }
            if (sampleTexture != "source") append("@").append(sampleTexture)
        }
}

private fun validateStackAutoShaderName(name: String) {
    require(stackAutoShaderNameRegex.matches(name)) {
        "stack.auto name '$name' must be a GLSL identifier."
    }
}

private fun validateStackAutoEntrypointName(name: String) {
    require(stackAutoEntrypointNameRegex.matches(name)) {
        "stack.auto entrypoint '$name' must be a StackSL definition name."
    }
}

private fun validateStackAutoSamplerName(name: String) {
    require(stackAutoShaderNameRegex.matches(name)) {
        "stack.auto sampler '$name' must be a GLSL identifier."
    }
}

private fun parseStackAutoShaderTarget(raw: String): StackAutoShaderTarget {
    val trimmed = raw.trim()
    val samplerMarker = trimmed.indexOf('@')
    val body = if (samplerMarker >= 0) trimmed.substring(0, samplerMarker).trim() else trimmed
    val sampleTexture = if (samplerMarker >= 0) trimmed.substring(samplerMarker + 1).trim() else "source"
    val name = body.substringBefore(':').trim()
    val entrypoint = body.substringAfter(':', missingDelimiterValue = "").trim().takeIf { it.isNotEmpty() }
    validateStackAutoShaderName(name)
    entrypoint?.let(::validateStackAutoEntrypointName)
    validateStackAutoSamplerName(sampleTexture)
    return StackAutoShaderTarget(name, entrypoint, sampleTexture)
}

private fun defaultStackAutoShaderFunction(name: String): String =
    "vec4 $name(vec4 ${name}_in) { return vec4(0.0); }"

private fun compileStackAutoShaderFunction(target: StackAutoShaderTarget, source: String): String {
    val name = target.name
    validateStackAutoShaderName(name)
    target.entrypoint?.let(::validateStackAutoEntrypointName)
    if (source.isBlank()) return defaultStackAutoShaderFunction(name)

    val result = StackSLCompiler.compile(
        source,
        StackSLCompileOptions(
            prefix = name,
            entrypoint = target.entrypoint ?: "main",
            useEntrypointAsRoot = target.entrypoint != null,
            tolerant = false,
            includeHeader = false,
            declareSamplers = false,
            externalizeConstants = false,
            sampleTextures = listOf(target.sampleTexture)
        )
    )

    if (result.ok) return result.glsl

    val diagnostics = result.diagnostics
        .filter { it.severity == StackSLSeverity.ERROR }
        .ifEmpty { result.diagnostics }
        .joinToString("\n") { it.toString() }
    throw IllegalArgumentException("Unable to compile stack.auto.${target.key}:\n$diagnostics")
}

private fun stackAutoShaderFunctionFor(box: Box, target: StackAutoShaderTarget): String {
    validateStackAutoShaderName(target.name)
    box.stackAutoShaderSources.putIfAbsent(target.name, "")
    return compileStackAutoShaderFunction(target, box.stackAutoShaderSources[target.name] ?: "")
}

private fun stackAutoTemplateResolverFor(box: Box, emitted: MutableMap<String, StackAutoShaderTarget>): Resolver =
    Resolver { key ->
        fun resolveTarget(raw: String): String {
            val target = parseStackAutoShaderTarget(raw)
            val previous = emitted[target.name]
            if (previous != null) {
                require(previous == target) {
                    "stack.auto.${target.name} was already emitted as stack.auto.${previous.key}; cannot also emit stack.auto.${target.key} in the same shader stage."
                }
                return ""
            }
            emitted[target.name] = target
            return stackAutoShaderFunctionFor(box, target)
        }

        when {
            key == "stack" -> Resolver { stackKey ->
                when {
                    stackKey == "auto" -> Resolver { name -> resolveTarget(name) }
                    stackKey.startsWith("auto.") -> resolveTarget(stackKey.removePrefix("auto."))
                    else -> null
                }
            }

            key == "stack.auto" -> Resolver { name -> resolveTarget(name) }
            key.startsWith("stack.auto.") -> resolveTarget(key.removePrefix("stack.auto."))
            else -> null
        }
    }

private fun stackAutoShaderOwner(module: Any): Box? =
    synchronized(stackAutoModuleOwners) { stackAutoModuleOwners[module] }

private fun registerStackAutoShaderOwner(box: Box, module: Any) {
    synchronized(stackAutoModuleOwners) {
        stackAutoModuleOwners[module] = box
    }
}

private fun stackAutoShaderBoxFor(module: Any): Box? =
    stackAutoShaderOwner(module) ?: Box.context.get()

fun stackAutoShaderSource(module: ModulePointer, name: String): String {
    validateStackAutoShaderName(name)
    val box = stackAutoShaderBoxFor(module)
    return box?.stackAutoShaderSources?.get(name) ?: ""
}

fun stackAutoShaderSource(module: ComputeModulePointer, name: String): String {
    validateStackAutoShaderName(name)
    val box = stackAutoShaderBoxFor(module)
    return box?.stackAutoShaderSources?.get(name) ?: ""
}

fun stackAutoShaderSource(module: MeshModulePointer, name: String): String {
    validateStackAutoShaderName(name)
    val box = stackAutoShaderBoxFor(module)
    return box?.stackAutoShaderSources?.get(name) ?: ""
}

private fun setStackAutoShaderSource(module: Any, name: String, source: String, reloadType: ShaderReloadType) {
    validateStackAutoShaderName(name)
    val box = stackAutoShaderBoxFor(module)
        ?: throw IllegalStateException("Cannot set stack.auto.$name without a module owner or active Box context.")

    val currentSource = box.stackAutoShaderSources[name] ?: ""
    if (currentSource == source) {
        return
    }

    val compiled = try {
        compileStackAutoShaderFunction(StackAutoShaderTarget(name), source)
    } catch (t: Throwable) {
        reportShaderStageError(
            box,
            "code",
            "stack.auto.$name",
            t.message ?: "Unable to compile stack.auto.$name",
            t
        )
        return
    }

    box.stackAutoShaderSources[name] = source
    requestStackAutoShaderUpdate(box, reloadType)
    lprintln("graphics", "compiled stack.auto.$name (${compiled.length} chars GLSL); queued shader update.")
}

fun stackAutoShaderSource(module: ModulePointer, name: String, source: String) {
    setStackAutoShaderSource(module, name, source, ShaderReloadType.GRAPHICS)
}

fun stackAutoShaderSource(module: ComputeModulePointer, name: String, source: String) {
    setStackAutoShaderSource(module, name, source, ShaderReloadType.COMPUTE)
}

fun stackAutoShaderSource(module: MeshModulePointer, name: String, source: String) {
    setStackAutoShaderSource(module, name, source, ShaderReloadType.MESH)
}

data class ShaderIncludeRootRequest(
    val root: String,
    val includePath: String,
    val requestedPath: String,
    val requestingSourcePath: String?,
    val includeType: ShaderIncludeType,
    val includeDepth: Long
)

fun interface ShaderIncludeRootResolver {
    fun resolve(request: ShaderIncludeRootRequest): ShaderIncludeResolution?
}

private val shaderIncludeRoots = ConcurrentHashMap<String, ShaderIncludeRootResolver>()

fun registerShaderIncludeRoot(root: String, resolver: ShaderIncludeRootResolver) {
    shaderIncludeRoots[normalizeShaderIncludeRootName(root)] = resolver
}

fun unregisterShaderIncludeRoot(root: String): ShaderIncludeRootResolver? =
    shaderIncludeRoots.remove(normalizeShaderIncludeRootName(root))

fun listShaderIncludeRoots(): Set<String> = shaderIncludeRoots.keys.toSortedSet()

data class ShaderRemoteIncludeCacheOptions(
    val cacheDirectory: Path,
    val connectTimeoutMillis: Int = 5_000,
    val readTimeoutMillis: Int = 5_000
)

private const val LYGIA_SHADER_ROOT = "lygia"
private const val LYGIA_REMOTE_BASE_URL = "https://lygia.xyz"

private fun defaultShaderIncludeCacheDirectory(): Path =
    Paths.get(System.getProperty("user.home"), ".field4", "shader-include-cache")

private fun cachePathForShaderInclude(cacheDirectory: Path, includePath: String): Path {
    val normalizedCacheDirectory = cacheDirectory.toAbsolutePath().normalize()
    val normalizedIncludePath = normalizeShaderIncludePath(includePath)
    val target = normalizedCacheDirectory.resolve(normalizedIncludePath).normalize()
    require(target.startsWith(normalizedCacheDirectory)) {
        "Shader include path '$includePath' escapes cache directory '$normalizedCacheDirectory'."
    }
    return target
}

private fun fetchRemoteShaderInclude(url: String, options: ShaderRemoteIncludeCacheOptions): String {
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    connection.requestMethod = "GET"
    connection.instanceFollowRedirects = true
    connection.connectTimeout = options.connectTimeoutMillis
    connection.readTimeout = options.readTimeoutMillis
    connection.setRequestProperty("Accept", "text/plain, text/x-glsl, */*")
    connection.setRequestProperty("User-Agent", "Field4 Shader Include Resolver")

    notify("Fetching remote shader include for ${url}", "remoteShader")

    return try {
        val code = connection.responseCode
        if (code != HttpURLConnection.HTTP_OK) {

            val details = connection.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            val message = buildString {
                append("HTTP ")
                append(code)
                append(" while fetching '")
                append(url)
                append("'")
                if (details != null) {
                    append(": ")
                    append(details)
                }
            }
            notify("error ${code} fetching remote shader include for ${url} ${details}", "remoteShader")

            throw IllegalArgumentException(message)
        }

        notify("fetched remote shader include for ${url}", "remoteShader")
        connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

fun shaderCachedUrlIncludeRootResolver(
    root: String,
    baseUrl: String,
    cacheOptions: ShaderRemoteIncludeCacheOptions = ShaderRemoteIncludeCacheOptions(
        cacheDirectory = defaultShaderIncludeCacheDirectory().resolve(normalizeShaderIncludeRootName(root))
    )
): ShaderIncludeRootResolver {
    val normalizedRoot = normalizeShaderIncludeRootName(root)
    val normalizedBaseUrl = baseUrl.trimEnd('/')
    val normalizedCacheDirectory = cacheOptions.cacheDirectory.toAbsolutePath().normalize()

    return ShaderIncludeRootResolver { request ->
        val includePath = normalizeShaderIncludePath(request.includePath)
        val cacheFile = cachePathForShaderInclude(normalizedCacheDirectory, includePath)

        if (Files.isRegularFile(cacheFile)) {
            notify("remote shader is cached at ${cacheFile.toAbsolutePath()}", "remoteShader")
            return@ShaderIncludeRootResolver ShaderIncludeResolution(
                resolvedPath = canonicalShaderRootPath(normalizedRoot, includePath),
                source = Files.readString(cacheFile)
            )
        }

        val remoteUrl = "$normalizedBaseUrl/$includePath"
        val source = fetchRemoteShaderInclude(remoteUrl, cacheOptions)
        cacheFile.parent?.let { Files.createDirectories(it) }
        Files.writeString(cacheFile, source, StandardCharsets.UTF_8)

        ShaderIncludeResolution(
            resolvedPath = canonicalShaderRootPath(normalizedRoot, includePath),
            source = source
        )
    }
}

fun shaderLygiaIncludeRootResolver(
    cacheOptions: ShaderRemoteIncludeCacheOptions = ShaderRemoteIncludeCacheOptions(
        cacheDirectory = defaultShaderIncludeCacheDirectory().resolve("lygia.xyz")
    )
): ShaderIncludeRootResolver = shaderCachedUrlIncludeRootResolver(
    root = LYGIA_SHADER_ROOT,
    baseUrl = LYGIA_REMOTE_BASE_URL,
    cacheOptions = cacheOptions
)

fun shaderLygiaIncludeRootResolver(cacheDirectory: Path): ShaderIncludeRootResolver =
    shaderLygiaIncludeRootResolver(ShaderRemoteIncludeCacheOptions(cacheDirectory = cacheDirectory))

fun shaderLygiaIncludeRootResolver(cacheDirectory: String): ShaderIncludeRootResolver =
    shaderLygiaIncludeRootResolver(Paths.get(cacheDirectory))

fun registerLygiaIncludeRoot(
    cacheOptions: ShaderRemoteIncludeCacheOptions = ShaderRemoteIncludeCacheOptions(
        cacheDirectory = defaultShaderIncludeCacheDirectory().resolve("lygia.xyz")
    )
) {
    registerShaderIncludeRoot(LYGIA_SHADER_ROOT, shaderLygiaIncludeRootResolver(cacheOptions))
}

fun registerLygiaIncludeRoot(cacheDirectory: Path) {
    registerShaderIncludeRoot(LYGIA_SHADER_ROOT, shaderLygiaIncludeRootResolver(cacheDirectory))
}

fun registerLygiaIncludeRoot(cacheDirectory: String) {
    registerLygiaIncludeRoot(Paths.get(cacheDirectory))
}

fun shaderFilesystemIncludeRootResolver(directory: Path): ShaderIncludeRootResolver {
    val rootDirectory = directory.toAbsolutePath().normalize()
    return ShaderIncludeRootResolver { request ->
        val target = rootDirectory.resolve(request.includePath).normalize()
        require(target.startsWith(rootDirectory)) {
            "Include '${request.requestedPath}' escapes filesystem shader root '$rootDirectory'."
        }
        if (!Files.isRegularFile(target)) return@ShaderIncludeRootResolver null
        ShaderIncludeResolution(
            resolvedPath = canonicalShaderRootPath(request.root, request.includePath),
            source = Files.readString(target)
        )
    }
}

fun shaderFilesystemIncludeRootResolver(directory: String): ShaderIncludeRootResolver =
    shaderFilesystemIncludeRootResolver(Paths.get(directory))

fun registerShaderFilesystemIncludeRoot(root: String, directory: Path) {
    val normalizedRoot = normalizeShaderIncludeRootName(root)
    registerShaderIncludeRoot(normalizedRoot, shaderFilesystemIncludeRootResolver(directory))
}

fun registerShaderFilesystemIncludeRoot(root: String, directory: String) {
    registerShaderFilesystemIncludeRoot(root, Paths.get(directory))
}

fun Box.fieldProbeHere(
    samples: Int = afield.four.util.FIELD_PROBE_DEFAULT_SAMPLES,
    binding: Int = afield.four.util.FIELD_PROBE_DEFAULT_BINDING,
    descriptorSet: Int = afield.four.util.FIELD_PROBE_DEFAULT_SET,
    enabled: Boolean = true,
    ordinalRange: UIntRange? = null
): ShaderFieldProbe {
    val probe = ensureFieldProbe()
    probe.resetLiveCodingRegistrations()
    probe.configure(
        samples = samples,
        binding = binding,
        descriptorSet = descriptorSet,
        enabled = enabled
    )
    if (ordinalRange != null) probe.ordinalRange(ordinalRange)
    else probe.ordinalRange()
    return probe
}

fun Box.fieldStatsHere(
    channels: Int = 1,
    sketchSize: Int = 256,
    maxPartials: Int = 65_536,
    descriptorSet: Int = 0,
    partialBinding: Int = FIELD_STATS_DEFAULT_PARTIAL_BINDING,
    resultBinding: Int = FIELD_STATS_DEFAULT_RESULT_BINDING,
    stateSlots: Int = 8
): ShaderFieldStats = _fieldStatsHere(
    channels = channels,
    sketchSize = sketchSize,
    maxPartials = maxPartials,
    descriptorSet = descriptorSet,
    partialBinding = partialBinding,
    resultBinding = resultBinding,
    stateSlots = stateSlots
)

fun Box._fieldStatsHere(
    channels: Int = 1,
    sketchSize: Int = 256,
    maxPartials: Int = 65_536,
    descriptorSet: Int = 0,
    partialBinding: Int = FIELD_STATS_DEFAULT_PARTIAL_BINDING,
    resultBinding: Int = FIELD_STATS_DEFAULT_RESULT_BINDING,
    stateSlots: Int = 8
): ShaderFieldStats {
    _fieldStatsBinding?.stats?.takeIf {
        it.box === this &&
            it.channels == channels &&
            it.sketchSize == sketchSize &&
            it.maxPartials == maxPartials &&
            it.descriptorSet == descriptorSet &&
            it.partialBinding == partialBinding &&
            it.resultBinding == resultBinding &&
            it.stateSlots == stateSlots
    }?.let { existing ->
        _fieldStatsBinding = ShaderFieldStatsBinding(existing, producer = true)
        return existing
    }

    val stats = ShaderFieldStats(
        box = this,
        channels = channels,
        sketchSize = sketchSize,
        maxPartials = maxPartials,
        stateSlots = stateSlots,
        descriptorSet = descriptorSet,
        partialBinding = partialBinding,
        resultBinding = resultBinding
    )
    _fieldStatsBinding = ShaderFieldStatsBinding(stats, producer = true)
    return stats
}

fun Box.macro_fieldStatsHere(args: List<String>): Insert {
    val call = args.joinToString("").trim()
    val body = call.removePrefix("(").removeSuffix(")").trim()
    val defaults = linkedMapOf(
        "channels" to 1,
        "sketchSize" to 256,
        "maxPartials" to 65_536,
        "descriptorSet" to 0,
        "partialBinding" to FIELD_STATS_DEFAULT_PARTIAL_BINDING,
        "resultBinding" to FIELD_STATS_DEFAULT_RESULT_BINDING,
        "stateSlots" to 8
    )
    val positionalNames = defaults.keys.toList()
    var positionalIndex = 0

    if (body.isNotEmpty()) {
        body.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { argument ->
            val (name, valueText) = if ('=' in argument) {
                argument.substringBefore('=').trim() to argument.substringAfter('=').trim()
            } else {
                require(positionalIndex < positionalNames.size) {
                    "fieldStatsHere has too many positional arguments."
                }
                positionalNames[positionalIndex++] to argument
            }
            require(name in defaults) { "Unknown fieldStatsHere argument '$name'." }
            val value = valueText.replace("_", "").toIntOrNull()
                ?: throw IllegalArgumentException(
                    "fieldStatsHere argument '$name' must be an integer literal so computeModuleHere can compile during macro expansion."
                )
            defaults[name] = value
        }
    }

    _fieldStatsHere(
        channels = defaults.getValue("channels"),
        sketchSize = defaults.getValue("sketchSize"),
        maxPartials = defaults.getValue("maxPartials"),
        descriptorSet = defaults.getValue("descriptorSet"),
        partialBinding = defaults.getValue("partialBinding"),
        resultBinding = defaults.getValue("resultBinding"),
        stateSlots = defaults.getValue("stateSlots")
    )

    return Insert(here = "_fieldStatsHere$call")
}

fun Box.useFieldStats(stats: ShaderFieldStats): ShaderFieldStats {
    _fieldStatsBinding = ShaderFieldStatsBinding(stats, producer = false)
    return stats
}

data class ShaderProbeAutoControlConfig(
    val minSamples: Int = 128,
    val clipLower: Double? = 0.0,
    val clipUpper: Double? = 1.0,
    val clipEpsilon: Double = 1.0e-6,
    val useNominalParams: Boolean = false,
    val displayChannel: String? = "auto",
    val closeDisplayOnStop: Boolean = false,
    val clearDisplayOnStop: Boolean = false,
    val runloopKeySuffix: String = "shaderFieldAuto"
)

class ShaderFieldAutoController(
    val box: Box,
    val probe: ShaderFieldProbe,
    val controller: AdaptiveDistributionController,
    val config: ShaderProbeAutoControlConfig,
    private val apply: (params: DoubleArray, output: ControlOutput, batch: FieldProbeScalarBatch) -> Unit
) {
    private val hookKey = "${config.runloopKeySuffix}:${box.id}"
    private var installed = false
    private var lastSnapshot: FieldProbeSnapshot? = null

    var tickCount: Int = 0
        private set

    var lastBatch: FieldProbeScalarBatch? = null
        private set

    var lastOutput: ControlOutput? = null
        private set

    fun tick(): ControlOutput? {
        val batch = probe.readLatestScalarBatch(
            minSamples = config.minSamples,
            clipLower = config.clipLower,
            clipUpper = config.clipUpper,
            clipEpsilon = config.clipEpsilon
        ) ?: return null

        if (batch.snapshot === lastSnapshot) return null

        val output = controller.pushSamples(batch.batch.samples, batch.batch.clipRate)
        val params = output.params(useNominalParams = config.useNominalParams)

        apply(params, output, batch)

        lastSnapshot = batch.snapshot
        lastBatch = batch
        lastOutput = output
        tickCount += 1

        config.displayChannel?.let { channel ->
            box.display(
                channel,
                shaderFieldAutoSummaryHtml(
                    batch = batch,
                    output = output,
                    parameterNames = controller.parameterNames,
                    useNominalParams = config.useNominalParams
                )
            )
        }

        return output
    }

    fun install(runloop: Runloop = Runloop.runLoop): ShaderFieldAutoController {
        if (installed) return this
        runloop.preRun[hookKey] = sequence {
            while (true) {
                tick()
                yield(Unit)
            }
        }
        installed = true
        return this
    }

    fun stop(
        runloop: Runloop = Runloop.runLoop,
        closeDisplay: Boolean = config.closeDisplayOnStop,
        clearDisplay: Boolean = config.clearDisplayOnStop
    ): ShaderFieldAutoController {
        runloop.preRun.remove(hookKey)
        installed = false

        config.displayChannel?.let { channel ->
            if (clearDisplay) box.displayClear(channel)
            if (closeDisplay) box.displayClose(channel)
        }

        if (box._fieldProbeAutoController === this) {
            box.properties.remove("_fieldProbeAutoController")
        }
        return this
    }
}

fun Box.fieldProbeAutoControl(
    probe: ShaderFieldProbe = ensureFieldProbe(),
    controller: AdaptiveDistributionController = AdaptiveDistributionController(
        ControllerConfig(),
        UniformQuantileFeatureExtractor()
    ),
    config: ShaderProbeAutoControlConfig = ShaderProbeAutoControlConfig(),
    install: Boolean = true,
    runloop: Runloop = Runloop.runLoop,
    apply: (params: DoubleArray, output: ControlOutput, batch: FieldProbeScalarBatch) -> Unit
): ShaderFieldAutoController {
    _fieldProbeAutoController?.stop(runloop = runloop)
    probe.requireContinuousSnapshotReads()

    return ShaderFieldAutoController(
        box = this,
        probe = probe,
        controller = controller,
        config = config,
        apply = apply
    ).also { runtime ->
        _fieldProbeAutoController = runtime
        if (install) runtime.install(runloop)
    }
}

private fun shaderFieldAutoSummaryHtml(
    batch: FieldProbeScalarBatch,
    output: ControlOutput,
    parameterNames: List<String>,
    useNominalParams: Boolean
): String {
    fun format(value: Double): String = String.format(Locale.US, "%.4f", value)

    val activeParams = output.params(useNominalParams)
    val parameterRows = parameterNames.indices.joinToString("") { index ->
        val name = parameterNames[index]
        """
        <tr>
            <td style="padding:2px 12px 2px 0;opacity:0.72;">$name</td>
            <td style="padding:2px 12px 2px 0;">${format(output.nominalParams[index])}</td>
            <td style="padding:2px 0 2px 0;">${format(output.appliedParams[index])}</td>
        </tr>
        """.trimIndent()
    }

    val featureError = output.diagnostics.filteredFeatureError.joinToString(", ") { format(it) }
    val p10 = batch.snapshot.percentile(0.10)
    val p50 = batch.snapshot.percentile(0.50)
    val p90 = batch.snapshot.percentile(0.90)

    return """
        <div style="padding:12px;background:#081019;color:#9fd9ff;font-family:Iosevka, monospace;font-size:11px;line-height:1.35;">
            <div style="display:flex;flex-wrap:wrap;gap:6px 14px;margin-bottom:10px;">
                <span><span style="opacity:0.65;">updates</span> ${output.diagnostics.pushIndex}</span>
                <span><span style="opacity:0.65;">objective</span> ${format(output.diagnostics.objective)}</span>
                <span><span style="opacity:0.65;">confidence</span> ${format(output.diagnostics.confidence)}</span>
                <span><span style="opacity:0.65;">clip</span> ${format(batch.batch.clipRate)}</span>
                <span><span style="opacity:0.65;">samples</span> ${batch.batch.samples.size}</span>
                <span><span style="opacity:0.65;">p10/p50/p90</span> ${format(p10)} / ${format(p50)} / ${format(p90)}</span>
            </div>
            <div style="margin-bottom:8px;opacity:0.72;">filtered feature error = [$featureError]</div>
            <div style="margin-bottom:6px;opacity:0.72;">active params = [${activeParams.joinToString(", ") { format(it) }}]</div>
            <table style="border-collapse:collapse;">
                <thead>
                    <tr>
                        <th style="text-align:left;padding:0 12px 4px 0;opacity:0.6;">parameter</th>
                        <th style="text-align:left;padding:0 12px 4px 0;opacity:0.6;">nominal</th>
                        <th style="text-align:left;padding:0 0 4px 0;opacity:0.6;">applied</th>
                    </tr>
                </thead>
                <tbody>$parameterRows</tbody>
            </table>
        </div>
    """.trimIndent()
}

// ------------------------------
// Public macro placeholders (expanded by MacroRegistry)
// ------------------------------
fun Box.moduleHere(p: Parameters): ModulePointer {
    throw MacroRegistry.MacroNotExpandedException("moduleHere should not be callable directly, but should be expanded as a macro")
}

fun Box.computeModuleHere(p: Parameters): ComputeModulePointer {
    throw MacroRegistry.MacroNotExpandedException("computeModuleHere should not be callable directly, but should be expanded as a macro")
}

fun Box.meshModuleHere(p: Parameters): MeshModulePointer {
    throw MacroRegistry.MacroNotExpandedException("meshModuleHere should not be callable directly, but should be expanded as a macro")
}

fun Box.rayTracingModuleHere(p: Parameters): RayTracingModulePointer {
    throw MacroRegistry.MacroNotExpandedException("rayTracingModuleHere should not be callable directly, but should be expanded as a macro")
}

fun Box.moduleHere(p: Parameters.() -> Unit): ModulePointer {
    throw MacroRegistry.MacroNotExpandedException("moduleHere should not be callable directly, but should be expanded as a macro")
}

fun Box.computeModuleHere(p: Parameters.() -> Unit): ComputeModulePointer {
    throw MacroRegistry.MacroNotExpandedException("computeModuleHere should not be callable directly, but should be expanded as a macro")
}

fun Box.meshModuleHere(p: Parameters.() -> Unit): MeshModulePointer {
    throw MacroRegistry.MacroNotExpandedException("meshModuleHere should not be callable directly, but should be expanded as a macro")
}

fun Box.rayTracingModuleHere(p: Parameters.() -> Unit): RayTracingModulePointer {
    throw MacroRegistry.MacroNotExpandedException("rayTracingModuleHere should not be callable directly, but should be expanded as a macro")
}

fun Box.shadertoyHere(p: Parameters): ModulePointer {
    throw MacroRegistry.MacroNotExpandedException("shadertoyHere should not be callable directly, but should be expanded as a macro")
}

fun Box.shadertoyHere(p: Parameters.() -> Unit): ModulePointer {
    throw MacroRegistry.MacroNotExpandedException("shadertoyHere should not be callable directly, but should be expanded as a macro")
}


// ------------------------------
// Utilities
// ------------------------------
private val glslSourceLineRegex = Regex("""\b(?:ERROR|WARNING):\s*\d+:(\d+):""", RegexOption.IGNORE_CASE)
private val fileLineRegex = Regex(""":[ \t]*(\d+):\s*(?:error|warning)\b""", RegexOption.IGNORE_CASE)
private val slangSourceLineRegex = Regex("""\((\d+)\):\s*(?:error|warning)\b""", RegexOption.IGNORE_CASE)
private val anyColonLineRegex = Regex(""":(\d+):""")
private val explicitLineRegex = Regex("""\bline\s+(\d+)\b""", RegexOption.IGNORE_CASE)

private val slangShaderPropertyByBase = mapOf(
    "vertex" to "vertex_s",
    "fragment" to "fragment_s",
    "compute" to "compute_s",
    "task" to "task_s",
    "mesh" to "mesh_s",
    "raygen" to "raygen_s",
    "miss" to "miss_s",
    "closesthit" to "closesthit_s",
    "anyhit" to "anyhit_s",
    "intersection" to "intersection_s",
    "callable" to "callable_s"
)
private const val sharedVertexFragmentProperty = "vertex_fragment"

private data class ShaderIncludeRef(val boxName: String, val property: String) {
    val key = "${boxName.trim()}.${property.trim()}"
}

fun numberFromErrorMessage(m: String): Int? {
    glslSourceLineRegex.find(m)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    fileLineRegex.find(m)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    slangSourceLineRegex.find(m)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    explicitLineRegex.find(m)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    return anyColonLineRegex.findAll(m).mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }.lastOrNull()
}

private fun slangPropertyFor(baseProperty: String): String? = slangShaderPropertyByBase[baseProperty.lowercase()]

private fun shaderPropertyVariants(baseProperty: String): List<String> {
    val normalized = baseProperty.lowercase()
    val shared = if (normalized == "vertex" || normalized == "fragment") listOf(sharedVertexFragmentProperty) else emptyList()
    return shared + listOfNotNull(normalized, slangPropertyFor(normalized))
}

private fun isSlangShaderProperty(property: String): Boolean =
    property.lowercase() == sharedVertexFragmentProperty ||
        property.lowercase().endsWith("_s") && baseShaderProperty(property) in slangShaderPropertyByBase

private fun baseShaderProperty(property: String): String = when (property.lowercase()) {
    "vertex", "vertex_s" -> "vertex"
    "fragment", "fragment_s" -> "fragment"
    "compute", "compute_s" -> "compute"
    "task", "task_s" -> "task"
    "mesh", "mesh_s" -> "mesh"
    "raygen", "raygen_s" -> "raygen"
    "miss", "miss_s" -> "miss"
    "closesthit", "closesthit_s" -> "closesthit"
    "anyhit", "anyhit_s" -> "anyhit"
    "intersection", "intersection_s" -> "intersection"
    "callable", "callable_s" -> "callable"
    else -> property.lowercase()
}

private fun defaultShaderProperty(kind: ShaderKind): String = when (kind) {
    ShaderKind.VERTEX_SHADER -> "vertex"
    ShaderKind.FRAGMENT_SHADER -> "fragment"
    ShaderKind.COMPUTE_SHADER -> "compute"
    ShaderKind.TASK_SHADER -> "task"
    ShaderKind.MESH_SHADER -> "mesh"
    ShaderKind.RAYGEN_SHADER -> "raygen"
    ShaderKind.MISS_SHADER -> "miss"
    ShaderKind.CLOSEST_HIT_SHADER -> "closesthit"
    ShaderKind.ANY_HIT_SHADER -> "anyhit"
    ShaderKind.INTERSECTION_SHADER -> "intersection"
    ShaderKind.CALLABLE_SHADER -> "callable"
    else -> "fragment"
}

private fun shaderKindForProperty(property: String, fallback: ShaderKind): ShaderKind = when (property.lowercase()) {
    "vertex", "vertex_s" -> ShaderKind.VERTEX_SHADER
    "fragment", "fragment_s" -> ShaderKind.FRAGMENT_SHADER
    "compute", "compute_s" -> ShaderKind.COMPUTE_SHADER
    "task", "task_s" -> ShaderKind.TASK_SHADER
    "mesh", "mesh_s" -> ShaderKind.MESH_SHADER
    "raygen", "raygen_s" -> ShaderKind.RAYGEN_SHADER
    "miss", "miss_s" -> ShaderKind.MISS_SHADER
    "closesthit", "closesthit_s" -> ShaderKind.CLOSEST_HIT_SHADER
    "anyhit", "anyhit_s" -> ShaderKind.ANY_HIT_SHADER
    "intersection", "intersection_s" -> ShaderKind.INTERSECTION_SHADER
    "callable", "callable_s" -> ShaderKind.CALLABLE_SHADER
    else -> fallback
}

private fun normalizeShaderIncludeRootName(root: String): String {
    val normalized = root.trim().trim('/').trim('\\')
    require(normalized.isNotEmpty()) { "Shader include root name cannot be empty." }
    require('/' !in normalized && '\\' !in normalized) {
        "Shader include root '$normalized' must be a single path segment."
    }
    return normalized
}

private fun normalizeShaderIncludePath(path: String): String {
    val normalized = path.replace('\\', '/').trim()
    require(normalized.isNotEmpty()) { "Shader include path cannot be empty." }

    val parts = mutableListOf<String>()
    normalized.split('/').forEach { part ->
        when (part) {
            "", "." -> Unit
            ".." -> {
                require(parts.isNotEmpty()) { "Shader include path '$path' escapes its root." }
                parts.removeLast()
            }
            else -> parts += part
        }
    }

    return parts.joinToString("/")
}

private fun canonicalShaderRootPath(root: String, includePath: String): String =
    buildString {
        append(normalizeShaderIncludeRootName(root))
        if (includePath.isNotBlank()) {
            append('/')
            append(normalizeShaderIncludePath(includePath))
        }
    }

private fun boxShaderSourceName(box: Box, property: String): String {
    val boxLabel = box.name.trim().ifBlank { "box" }
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
    val propertyLabel = property.trim().ifBlank { "shader" }
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
    return "field/$boxLabel-${box.id}/$propertyLabel.glsl"
}

private fun resolveShaderIncludeRoot(request: ShaderIncludeRequest): ShaderIncludeResolution? {
    val requestedPath = request.requestedPath.trim()
    val requestingSource = request.requestingSource?.trim()?.takeIf { it.isNotEmpty() }

    val directRoot = requestedPath.substringBefore('/', missingDelimiterValue = "")
    if (directRoot.isNotEmpty() && shaderIncludeRoots.containsKey(directRoot)) {
        val includePath = normalizeShaderIncludePath(requestedPath.substringAfter('/'))
        val resolution = shaderIncludeRoots.getValue(directRoot).resolve(
            ShaderIncludeRootRequest(
                root = directRoot,
                includePath = includePath,
                requestedPath = requestedPath,
                requestingSourcePath = requestingSource,
                includeType = request.includeType,
                includeDepth = request.includeDepth
            )
        )
        requireNotNull(resolution) {
            "Unable to resolve include '$requestedPath' from '${requestingSource ?: "<root>"}' in shader root '$directRoot'."
        }
        return resolution
    }

    if (request.includeType == ShaderIncludeType.RELATIVE && requestingSource != null) {
        val requestingRoot = requestingSource.substringBefore('/', missingDelimiterValue = "")
        if (shaderIncludeRoots.containsKey(requestingRoot)) {
            val basePath = requestingSource.removePrefix("$requestingRoot/")
            val baseDirectory = basePath.substringBeforeLast('/', "")
            val resolvedRelativePath = normalizeShaderIncludePath(
                listOfNotNull(baseDirectory.takeIf { it.isNotBlank() }, requestedPath)
                    .joinToString("/")
            )
            val resolution = shaderIncludeRoots.getValue(requestingRoot).resolve(
                ShaderIncludeRootRequest(
                    root = requestingRoot,
                    includePath = resolvedRelativePath,
                    requestedPath = requestedPath,
                    requestingSourcePath = requestingSource,
                    includeType = request.includeType,
                    includeDepth = request.includeDepth
                )
            )
            requireNotNull(resolution) {
                "Unable to resolve include '$requestedPath' from '$requestingSource' in shader root '$requestingRoot'."
            }
            return resolution
        }
    }

    return null
}

private fun parseShaderIncludeRef(path: String, defaultProperty: String): ShaderIncludeRef {
    val trimmed = path.trim()
    require(trimmed.isNotEmpty()) { "Empty shader include path." }
    val dot = trimmed.lastIndexOf('.')
    if (dot >= 0) {
        require(dot > 0 && dot < trimmed.lastIndex) {
            "Invalid shader include path '$trimmed'. Use '<box>' or '<box>.<property>'."
        }
        val boxName = trimmed.substring(0, dot).trim()
        val property = trimmed.substring(dot + 1).trim()
        require(boxName.isNotEmpty() && property.isNotEmpty()) {
            "Invalid shader include path '$trimmed'."
        }
        return ShaderIncludeRef(boxName, property)
    }
    return ShaderIncludeRef(trimmed, defaultProperty)
}

private fun findBoxByNameForInclude(inside: Box, requestedName: String): Box? {
    val name = requestedName.trim()
    if (name.isEmpty()) return null

    var found: Box? = null
    inside.visitParents {
        if (found == null && it.name == name && !it.getPropertyRaw("_tombstone").isTrue()) found = it
    }
    if (found != null) return found

    val fringe = ArrayDeque<Box>()
    val seen = mutableSetOf<Box>()
    fringe.add(inside.root)
    while (fringe.isNotEmpty()) {
        val next = fringe.removeFirst()
        if (!seen.add(next)) continue
        if (next.name == name && !next.getPropertyRaw("_tombstone").isTrue()) return next
        fringe.addAll(next.children)
    }
    return null
}

private fun getIncludePropertyValue(box: Box, property: String): String? {
    val normalized = property.trim()
    return when (normalized.lowercase()) {
        "vertex" -> box.vertex
        "vertex_fragment" -> box.vertex_fragment
        "vertex_s" -> box.vertex_s
        "fragment" -> box.fragment
        "fragment_s" -> box.fragment_s
        "compute" -> box.compute
        "compute_s" -> box.compute_s
        "task" -> box.task
        "task_s" -> box.task_s
        "mesh" -> box.mesh
        "mesh_s" -> box.mesh_s
        "raygen" -> box.raygen
        "raygen_s" -> box.raygen_s
        "miss" -> box.miss
        "miss_s" -> box.miss_s
        "closesthit" -> box.closesthit
        "closesthit_s" -> box.closesthit_s
        "anyhit" -> box.anyhit
        "anyhit_s" -> box.anyhit_s
        "intersection" -> box.intersection
        "intersection_s" -> box.intersection_s
        "callable" -> box.callable
        "callable_s" -> box.callable_s
        else -> box.getPropertyRaw(normalized) as? String
    }
}

private fun Box.ensureFieldProbe(): ShaderFieldProbe {
    val existing = _fieldProbe
    if (existing != null) return existing
    return ShaderFieldProbe(this).also { _fieldProbe = it }
}

private fun builtInShaderInclude(inside: Box, includePath: String): String? {
    return when (includePath.trim()) {
        FIELD_PROBE_INCLUDE_NAME -> inside.ensureFieldProbe().includeSource()
        FIELD_STATS_INCLUDE_NAME -> inside._fieldStatsBinding?.let {
            it.stats.includeSource(producer = it.producer)
        }
            ?: throw IllegalStateException(
                "Include '$FIELD_STATS_INCLUDE_NAME' requires fieldStatsHere(...) or useFieldStats(...)."
            )
        FIELD_QUANTILE_WARP_INCLUDE_NAME -> fieldQuantileWarpIncludeSource()
        else -> null
    }
}

private fun shaderIncludeResolverFor(inside: Box, defaultProperty: String): ShaderIncludeResolver = ShaderIncludeResolver { request ->
    val includePath = request.requestedPath.trim()

    builtInShaderInclude(inside, includePath)?.let { builtIn ->
        return@ShaderIncludeResolver ShaderIncludeResolution(
            resolvedPath = "field/include/$includePath.glsl",
            source = builtIn
        )
    }

    resolveShaderIncludeRoot(request)?.let { return@ShaderIncludeResolver it }

    if (includePath.contains('/')) {
        val source = request.requestingSource?.takeIf { it.isNotBlank() } ?: boxShaderSourceName(inside, defaultProperty)
        throw IllegalArgumentException(
            "Unable to resolve include '$includePath' from '$source': no registered include root named '${includePath.substringBefore('/')}'."
        )
    }

    val includeRef = parseShaderIncludeRef(includePath, defaultProperty)
    val targetBox = findBoxByNameForInclude(inside, includeRef.boxName)
        ?: throw IllegalArgumentException("Unable to resolve include '$includePath': no box named '${includeRef.boxName}'.")
    val includeBody = getIncludePropertyValue(targetBox, includeRef.property)
        ?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException(
            "Unable to resolve include '$includePath': '${includeRef.property}' on box '${targetBox.name}' is empty."
        )

    ShaderIncludeResolution(
        resolvedPath = boxShaderSourceName(targetBox, includeRef.property),
        source = includeBody
    )
}

private fun attachFieldProbe(box: Box, parameters: Parameters) {
    box._fieldProbe?.let { probe ->
        parameters.fieldProbe = probe

        val previousResolver = parameters.ssboResolver
        parameters.ssboResolver = { requested ->
            if (requested.name == FIELD_PROBE_BUFFER_NAME) {
                probe.bufferParameters(parameters.renderer)
            } else {
                previousResolver(requested)
            }
        }
    }

    box._fieldStatsBinding?.let { binding ->
        parameters.fieldStats = binding
        val previousResolver = parameters.ssboResolver
        parameters.ssboResolver = { requested ->
            when (requested.name) {
                FIELD_STATS_PARTIAL_BUFFER_NAME -> binding.stats.partialBufferParameters(parameters.renderer)
                FIELD_STATS_RESULT_BUFFER_NAME -> binding.stats.resultBufferParameters(parameters.renderer)
                else -> previousResolver(requested)
            }
        }
    }
}

private fun preprocess(
    inside: Box,
    source: String,
    kind: ShaderKind,
    defaultProperty: String = defaultShaderProperty(kind),
    includeStack: List<String> = emptyList(),
    priorStages: Map<ShaderKind, PreparedShaderStage> = emptyMap(),
    stackAutoEmitted: MutableMap<String, StackAutoShaderTarget> = mutableMapOf()
): String {
    val sourceWithCopiedPushConstants = expandCopyPushConstantTags(
        source = source,
        kind = kind,
        currentProperty = defaultProperty,
        priorStages = priorStages
    )

    val includeResolver = IncludeResolver { includePath, line, _ ->
        builtInShaderInclude(inside, includePath)?.let { builtIn ->
            return@IncludeResolver buildString {
                append(builtIn)
                if (!builtIn.endsWith('\n')) append('\n')
                append("#line ${line + 2}\n")
            }
        }

        val includeRef = parseShaderIncludeRef(includePath, defaultProperty)
        val includeKey = includeRef.key
        if (includeStack.contains(includeKey)) {
            val chain = (includeStack + includeKey).joinToString(" -> ")
            throw IllegalArgumentException("Include cycle at line $line while resolving '$includePath': $chain")
        }

        val targetBox = findBoxByNameForInclude(inside, includeRef.boxName)
            ?: throw IllegalArgumentException(
                "Unable to resolve include '$includePath' at line $line: no box named '${includeRef.boxName}'."
            )

        val includeBody = getIncludePropertyValue(targetBox, includeRef.property)
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException(
                "Unable to resolve include '$includePath' at line $line: '${includeRef.property}' on box '${targetBox.name}' is empty."
            )

        val nestedKind = shaderKindForProperty(includeRef.property, kind)
        val expandedInclude = preprocess(
            inside,
            includeBody,
            nestedKind,
            defaultProperty = includeRef.property,
            includeStack = includeStack + includeKey,
            priorStages = priorStages,
            stackAutoEmitted = stackAutoEmitted
        )
        buildString {
            append(expandedInclude)
            if (!expandedInclude.endsWith('\n')) append('\n')
            append("#line ${line + 2}\n")
        }
    }

    return runShaderTemplate(
        sourceWithCopiedPushConstants,
        listOf(stackAutoTemplateResolverFor(inside, stackAutoEmitted)) + getShaderTemplateResolverForBox(inside),
        includeResolver
    )
}

fun preprocess(inside: Box, code: String, kind: ShaderKind): String {
    return preprocess(inside, code, kind, defaultShaderProperty(kind), emptyList())
}

private val copyPushConstantTagRegex = Regex("""\{%\s*copyPC\s*%}""")
private val layoutKeywordRegex = Regex("""\blayout\b""")
private val pushConstantLayoutRegex = Regex("""\bpush_constant\b""")

private fun expandCopyPushConstantTags(
    source: String,
    kind: ShaderKind,
    currentProperty: String,
    priorStages: Map<ShaderKind, PreparedShaderStage>
): String {
    if (!copyPushConstantTagRegex.containsMatchIn(source)) return source

    val previousStage = previousStageForCopyPushConstants(kind, priorStages)
        ?: throw IllegalArgumentException(
            "'{% copyPC %}' in '$currentProperty' requires a previous prepared shader stage. " +
                "Supported flows are vertex -> fragment and task -> mesh -> fragment."
        )

    val declaration = extractPushConstantLayoutDeclaration(previousStage.glslSource, previousStage.sourceName)
    return copyPushConstantTagRegex.replace(source, declaration)
}

private fun previousStageForCopyPushConstants(
    kind: ShaderKind,
    priorStages: Map<ShaderKind, PreparedShaderStage>
): PreparedShaderStage? = when (kind) {
    ShaderKind.MESH_SHADER -> priorStages[ShaderKind.TASK_SHADER]
    ShaderKind.FRAGMENT_SHADER -> priorStages[ShaderKind.MESH_SHADER] ?: priorStages[ShaderKind.VERTEX_SHADER]
    ShaderKind.MISS_SHADER,
    ShaderKind.CLOSEST_HIT_SHADER,
    ShaderKind.ANY_HIT_SHADER,
    ShaderKind.INTERSECTION_SHADER,
    ShaderKind.CALLABLE_SHADER -> priorStages[ShaderKind.RAYGEN_SHADER]
    else -> null
}

private fun extractPushConstantLayoutDeclaration(source: String, sourceName: String): String {
    val matches = mutableListOf<String>()
    var searchFrom = 0

    while (true) {
        val layoutMatch = layoutKeywordRegex.find(source, searchFrom) ?: break
        val layoutStart = layoutMatch.range.first
        var cursor = skipShaderTrivia(source, layoutMatch.range.last + 1)
        if (cursor >= source.length || source[cursor] != '(') {
            searchFrom = layoutStart + 1
            continue
        }

        val layoutEnd = findMatchingDelimiter(source, cursor, '(', ')')
            ?: throw IllegalArgumentException("Unclosed layout(...) declaration while scanning '$sourceName'.")
        val layoutArgs = source.substring(cursor + 1, layoutEnd)
        if (!pushConstantLayoutRegex.containsMatchIn(layoutArgs)) {
            searchFrom = layoutEnd + 1
            continue
        }

        cursor = skipShaderTrivia(source, layoutEnd + 1)
        if (!startsWithShaderKeyword(source, cursor, "uniform")) {
            throw IllegalArgumentException(
                "Found layout(... push_constant ...) in '$sourceName' but could not parse the following uniform block."
            )
        }
        cursor = skipShaderTrivia(source, cursor + "uniform".length)

        val blockStart = findNextShaderChar(source, cursor, '{')
            ?: throw IllegalArgumentException(
                "Push constant layout in '$sourceName' is missing the '{' that starts its uniform block."
            )
        val blockEnd = findMatchingDelimiter(source, blockStart, '{', '}')
            ?: throw IllegalArgumentException("Unclosed push constant uniform block while scanning '$sourceName'.")
        val declarationEnd = findNextShaderChar(source, blockEnd + 1, ';')
            ?: throw IllegalArgumentException("Push constant uniform block in '$sourceName' is missing its trailing ';'.")

        matches += source.substring(layoutStart, declarationEnd + 1).trimEnd()
        searchFrom = declarationEnd + 1
    }

    return when (matches.size) {
        0 -> throw IllegalArgumentException("'{% copyPC %}' could not find a push constant layout declaration in '$sourceName'.")
        1 -> matches.single()
        else -> throw IllegalArgumentException(
            "'{% copyPC %}' expected exactly one push constant layout declaration in '$sourceName', found ${matches.size}."
        )
    }
}

private fun skipShaderTrivia(source: String, start: Int): Int {
    var index = start
    while (index < source.length) {
        when {
            source[index].isWhitespace() -> index += 1
            source.startsWith("//", index) -> {
                val newline = source.indexOf('\n', index + 2)
                index = if (newline == -1) source.length else newline + 1
            }
            source.startsWith("/*", index) -> {
                val end = source.indexOf("*/", index + 2)
                if (end == -1) return source.length
                index = end + 2
            }
            else -> return index
        }
    }
    return index
}

private fun findMatchingDelimiter(source: String, openIndex: Int, openChar: Char, closeChar: Char): Int? {
    var depth = 0
    var index = openIndex
    while (index < source.length) {
        when {
            source.startsWith("//", index) -> {
                val newline = source.indexOf('\n', index + 2)
                index = if (newline == -1) source.length else newline + 1
                continue
            }
            source.startsWith("/*", index) -> {
                val end = source.indexOf("*/", index + 2)
                if (end == -1) return null
                index = end + 2
                continue
            }
            source[index] == openChar -> depth += 1
            source[index] == closeChar -> {
                depth -= 1
                if (depth == 0) return index
            }
        }
        index += 1
    }
    return null
}

private fun startsWithShaderKeyword(source: String, index: Int, keyword: String): Boolean {
    if (index < 0 || index + keyword.length > source.length) return false
    if (!source.regionMatches(index, keyword, 0, keyword.length)) return false
    val before = source.getOrNull(index - 1)
    val after = source.getOrNull(index + keyword.length)
    return !before.isShaderIdentifierPart() && !after.isShaderIdentifierPart()
}

private fun findNextShaderChar(source: String, start: Int, target: Char): Int? {
    var index = start
    while (index < source.length) {
        when {
            source.startsWith("//", index) -> {
                val newline = source.indexOf('\n', index + 2)
                index = if (newline == -1) source.length else newline + 1
                continue
            }
            source.startsWith("/*", index) -> {
                val end = source.indexOf("*/", index + 2)
                if (end == -1) return null
                index = end + 2
                continue
            }
            source[index] == target -> return index
            else -> index += 1
        }
    }
    return null
}

private fun Char?.isShaderIdentifierPart(): Boolean =
    this != null && (this == '_' || this.isLetterOrDigit())

/**
 * Unified compile/introspect helper that:
 *  - runs the GLSL/spirv compilation for a stage
 *  - reports errors via box.error with a friendly stage label
 *  - tells the caller whether errors occurred
 */
private data class CompileResult(val introspection: Introspection?, val hadError: Boolean)

private enum class ShaderReloadType {
    GRAPHICS,
    COMPUTE,
    MESH,
    RAY_TRACING
}

private data class ShaderReloadKey(
    val boxIdentity: Int,
    val type: ShaderReloadType
)

private val shaderReloadScope =
    CoroutineScope(SupervisorJob() + Runloop.miscPool.asCoroutineDispatcher())

private val shaderReloadGenerations = ConcurrentHashMap<ShaderReloadKey, AtomicLong>()
private val shaderReloadErrorGate = ThreadLocal<(() -> Boolean)?>()

private fun currentShaderReloadMayReport(): Boolean =
    shaderReloadErrorGate.get()?.invoke() ?: true

private fun nextShaderGeneration(box: Box, type: ShaderReloadType): Pair<ShaderReloadKey, Long> {
    val key = ShaderReloadKey(System.identityHashCode(box), type)
    val generation = shaderReloadGenerations.computeIfAbsent(key) { AtomicLong(0L) }.incrementAndGet()
    return key to generation
}

private fun isCurrentShaderGeneration(key: ShaderReloadKey, generation: Long): Boolean =
    shaderReloadGenerations[key]?.get() == generation

private inline fun <T> withShaderReloadErrorGate(key: ShaderReloadKey, generation: Long, block: () -> T): T {
    val previous = shaderReloadErrorGate.get()
    shaderReloadErrorGate.set { isCurrentShaderGeneration(key, generation) }
    try {
        return block()
    } finally {
        shaderReloadErrorGate.set(previous)
    }
}

private data class StackAutoShaderUpdateState(
    val inFlight: AtomicBoolean = AtomicBoolean(false),
    val pending: AtomicBoolean = AtomicBoolean(false)
)

private val stackAutoShaderUpdateStates = ConcurrentHashMap<ShaderReloadKey, StackAutoShaderUpdateState>()

private suspend fun runStackAutoShaderUpdate(box: Box, type: ShaderReloadType, key: ShaderReloadKey, generation: Long): Boolean =
    when (type) {
        ShaderReloadType.GRAPHICS -> updateShader(box, key, generation)
        ShaderReloadType.COMPUTE -> updateComputeShader(box, key, generation)
        ShaderReloadType.MESH -> withContext(Runloop.runLoop.main) {
            if (!isCurrentShaderGeneration(key, generation)) return@withContext false
            withShaderReloadErrorGate(key, generation) {
                reloadMeshShader(box)
            }
        }
        ShaderReloadType.RAY_TRACING -> withContext(Runloop.runLoop.main) {
            if (!isCurrentShaderGeneration(key, generation)) return@withContext false
            withShaderReloadErrorGate(key, generation) {
                reloadRayTracingShader(box)
            }
        }
    }

private fun stackAutoShaderTypeLabel(type: ShaderReloadType): String =
    when (type) {
        ShaderReloadType.GRAPHICS -> "shader"
        ShaderReloadType.COMPUTE -> "compute shader"
        ShaderReloadType.MESH -> "mesh shader"
        ShaderReloadType.RAY_TRACING -> "ray tracing shader"
    }

private fun requestStackAutoShaderUpdate(box: Box, type: ShaderReloadType) {
    val updateKey = ShaderReloadKey(System.identityHashCode(box), type)
    val state = stackAutoShaderUpdateStates.computeIfAbsent(updateKey) {
        StackAutoShaderUpdateState()
    }
    state.pending.set(true)

    if (!state.inFlight.compareAndSet(false, true)) {
        nextShaderGeneration(box, type)
        return
    }

    shaderReloadScope.launch {
        try {
            do {
                state.pending.set(false)
                val (key, generation) = nextShaderGeneration(box, type)
                val label = stackAutoShaderTypeLabel(type)
                val success = try {
                    runStackAutoShaderUpdate(box, type, key, generation)
                } catch (t: Throwable) {
                    lprintln(
                        "error,graphics",
                        "unexpected error updating stack.auto $label in '${box.name}' (${box.id}): ${t.message}"
                    )
                    if (isCurrentShaderGeneration(key, generation)) {
                        withContext(Runloop.runLoop.main) {
                            box.error(t.message ?: "stack.auto $label update failed", "code", -1)
                        }
                    }
                    false
                }

                if (isCurrentShaderGeneration(key, generation)) {
                    withContext(Runloop.runLoop.main) {
                        notify(
                            if (success) "Updated stack.auto $label in '${box.name}'"
                            else "Stack.auto $label update failed in '${box.name}'",
                            "shader"
                        )
                    }
                }
            } while (state.pending.get())
        } finally {
            state.inFlight.set(false)
            if (state.pending.get()) requestStackAutoShaderUpdate(box, type)
        }
    }
}

private data class PreparedShaderStage(
    val kind: ShaderKind,
    val baseProperty: String,
    val sourceProperty: String,
    val sourceName: String,
    val source: String,
    val preprocessedSource: String,
    val glslSource: String,
    val usedSlang: Boolean,
    val spirv: ShaderSPIRVUtils.SPIRV? = null,
    val reflectionJson: String? = null
) {
    val logLabel: String
        get() = if (usedSlang) "$baseProperty shader (slang)" else "$baseProperty shader"
}

private fun shaderPropertyValue(box: Box, property: String): String? = when (property.lowercase()) {
    "vertex" -> box.vertex
    "vertex_fragment" -> box.vertex_fragment
    "vertex_s" -> box.vertex_s
    "fragment" -> box.fragment
    "fragment_s" -> box.fragment_s
    "compute" -> box.compute
    "compute_s" -> box.compute_s
    "task" -> box.task
    "task_s" -> box.task_s
    "mesh" -> box.mesh
    "mesh_s" -> box.mesh_s
    "raygen" -> box.raygen
    "raygen_s" -> box.raygen_s
    "miss" -> box.miss
    "miss_s" -> box.miss_s
    "closesthit" -> box.closesthit
    "closesthit_s" -> box.closesthit_s
    "anyhit" -> box.anyhit
    "anyhit_s" -> box.anyhit_s
    "intersection" -> box.intersection
    "intersection_s" -> box.intersection_s
    "callable" -> box.callable
    "callable_s" -> box.callable_s
    else -> box.getPropertyRaw(property) as? String
}

private fun hasShaderSource(box: Box, baseProperty: String): Boolean =
    shaderPropertyVariants(baseProperty).any { property ->
        !shaderPropertyValue(box, property).isNullOrBlank()
    }

private fun sourcePropertyForStage(box: Box, baseProperty: String): String {
    val normalized = baseProperty.lowercase()
    if ((normalized == "vertex" || normalized == "fragment") && !shaderPropertyValue(box, sharedVertexFragmentProperty).isNullOrBlank()) {
        return sharedVertexFragmentProperty
    }
    val slangProperty = slangPropertyFor(normalized)
    val slangSource = slangProperty?.let { shaderPropertyValue(box, it) }
    return if (!slangSource.isNullOrBlank()) slangProperty else normalized
}

private fun assignPreparedShaderStage(box: Box, stage: PreparedShaderStage) {
    when (stage.baseProperty) {
        "vertex" -> box._vertex = stage.glslSource
        "fragment" -> box._fragment = stage.glslSource
        "compute" -> box._compute = stage.glslSource
        "task" -> box._task = stage.glslSource
        "mesh" -> box._mesh = stage.glslSource
        "raygen" -> box._raygen = stage.glslSource
        "miss" -> box._miss = stage.glslSource
        "closesthit" -> box._closesthit = stage.glslSource
        "anyhit" -> box._anyhit = stage.glslSource
        "intersection" -> box._intersection = stage.glslSource
        "callable" -> box._callable = stage.glslSource
    }
}

private fun clearShaderErrorsForBaseProperties(box: Box, vararg baseProperties: String) {
    val properties = baseProperties
        .flatMap(::shaderPropertyVariants)
        .distinct()
        .toTypedArray()
    clearShaderErrors(box, *properties)
}

private fun reportShaderStageError(
    box: Box,
    propertyForUI: String,
    logLabel: String,
    message: String,
    throwable: Throwable? = null
) {
    val trimmed = message.trim().ifBlank { throwable?.message ?: logLabel }
    lprintln("error,graphics", "error during $logLabel $trimmed")
    throwable?.let { lprintln("error,graphics", it.stackTraceToString()) }
    if (currentShaderReloadMayReport()) {
        box.error(trimmed, propertyForUI, numberFromErrorMessage(trimmed) ?: 0)
    }
}

private fun prepareShaderStage(
    box: Box,
    kind: ShaderKind,
    baseProperty: String,
    priorStages: Map<ShaderKind, PreparedShaderStage> = emptyMap()
): PreparedShaderStage? {
    val sourceProperty = sourcePropertyForStage(box, baseProperty)
    val source = shaderPropertyValue(box, sourceProperty)?.takeIf { it.isNotBlank() } ?: run {
        reportShaderStageError(
            box,
            sourceProperty,
            "$baseProperty shader setup",
            "No shader source found for property '$sourceProperty'."
        )
        return null
    }

    val preprocessedSource = try {
        preprocess(box, source, kind, defaultProperty = sourceProperty, priorStages = priorStages)
    } catch (t: Throwable) {
        reportShaderStageError(
            box,
            sourceProperty,
            "$baseProperty shader preprocessing",
            t.message ?: "while preprocessing $baseProperty shader",
            t
        )
        return null
    }

    val usedSlang = isSlangShaderProperty(sourceProperty)
    var spirv: ShaderSPIRVUtils.SPIRV? = null
    var reflectionJson: String? = null
    val glslSource = if (sourceProperty == sharedVertexFragmentProperty) {
        try {
            val compiled = SlangCompiler.compileSharedStage(preprocessedSource, kind, "$sourceProperty.slang")
            spirv = compiled.spirv
            reflectionJson = compiled.reflectionJson
            compiled.glslSource
        } catch (t: Throwable) {
            reportShaderStageError(
                box,
                sourceProperty,
                "$baseProperty shader transpilation",
                t.message ?: "slangc failed for $sourceProperty",
                t
            )
            return null
        }
    } else if (usedSlang) {
        try {
            SlangCompiler.compileToGlsl(preprocessedSource, kind, "$sourceProperty.slang")
        } catch (t: Throwable) {
            reportShaderStageError(
                box,
                sourceProperty,
                "$baseProperty shader transpilation",
                t.message ?: "slangc failed for $sourceProperty",
                t
            )
            return null
        }
    } else {
        preprocessedSource
    }

    return PreparedShaderStage(
        kind = kind,
        baseProperty = baseProperty,
        sourceProperty = sourceProperty,
        sourceName = boxShaderSourceName(box, sourceProperty),
        source = source,
        preprocessedSource = preprocessedSource,
        glslSource = glslSource,
        usedSlang = usedSlang,
        spirv = spirv,
        reflectionJson = reflectionJson
    )
}

private fun compileStage(
    box: Box,
    stage: PreparedShaderStage
): CompileResult {
    var hadError = false
    val includeResolver = shaderIncludeResolverFor(box, stage.sourceProperty)
    val handler = { message: String, throwable: Throwable ->
        val throwableMsg = throwable.message?.let { " $it" } ?: ""
        lprintln("error,graphics", "error during ${stage.logLabel} compilation $message$throwableMsg")
        lprintln("error,graphics", throwable.stackTraceToString())
        if (currentShaderReloadMayReport()) {
            box.error(message, stage.sourceProperty, numberFromErrorMessage(message) ?: 0)
        }
        hadError = true
    }
    val i = if (stage.spirv != null && stage.reflectionJson != null) {
        Introspection.fromSlangReflection(
            source = stage.glslSource,
            kind = stage.kind,
            spirv = stage.spirv,
            reflectionJson = stage.reflectionJson,
            sourceName = stage.sourceName,
            includeResolver = includeResolver,
            errorHandler = handler
        )
    } else {
        Introspection(
            stage.glslSource,
            stage.kind,
            errorHandler = handler,
            sourceName = stage.sourceName,
            includeResolver = includeResolver
        )
    }
    return CompileResult(if (hadError) null else i, hadError)
}

/**
 * Applies an Insert’s preamble(s) into the target box.
 */
private val preamblePrefixRegex = Regex("^\\s*/\\*(.*?)\\*/\\s*")
private const val preambleBlockStartMarker = "/*start*/"
private const val preambleBlockEndMarker = "/*end*/"

private data class PreambleSegment(val lines: List<String>)

private fun preambleLineKey(line: String): String {
    val normalized = line.trimEnd()
    if (normalized.isBlank()) return ""
    val prefix = preamblePrefixRegex.find(normalized)?.groupValues?.getOrNull(1)?.trim()
    return if (prefix.isNullOrEmpty()) normalized else prefix
}

private fun isImportLine(line: String): Boolean {
    val withoutPrefix = line.replaceFirst(preamblePrefixRegex, "")
    return withoutPrefix.trimStart().startsWith("import ")
}

private fun stripPreambleBlockMarkers(line: String): String =
    line.replace(preambleBlockStartMarker, "").replace(preambleBlockEndMarker, "").trim()

private fun splitPreambleSegments(lines: List<String>): List<PreambleSegment> {
    val segments = mutableListOf<PreambleSegment>()
    var currentBlock: MutableList<String>? = null

    fun flushCurrentBlock() {
        val block = currentBlock ?: return
        if (block.isNotEmpty()) segments += PreambleSegment(block.toList())
        currentBlock = null
    }

    lines.forEach { line ->
        val normalized = line.trimEnd()
        if (normalized.isBlank()) return@forEach

        if (currentBlock != null) {
            currentBlock?.add(normalized)
            if (normalized.contains(preambleBlockEndMarker)) {
                flushCurrentBlock()
            }
            return@forEach
        }

        if (normalized.contains(preambleBlockStartMarker)) {
            currentBlock = mutableListOf(normalized)
            if (normalized.contains(preambleBlockEndMarker)) {
                flushCurrentBlock()
            }
        } else {
            segments += PreambleSegment(listOf(normalized))
        }
    }

    flushCurrentBlock()

    return segments
}

private fun preambleSegmentKey(segment: PreambleSegment): String {
    val prefixedLine = segment.lines
        .asSequence()
        .map(::stripPreambleBlockMarkers)
        .firstOrNull { preamblePrefixRegex.containsMatchIn(it) }

    if (prefixedLine != null) return preambleLineKey(prefixedLine)

    val normalized = segment.lines.joinToString(separator = "\n") { it.trimEnd() }.trim()
    return normalized
}

private fun isImportSegment(segment: PreambleSegment): Boolean {
    val firstMeaningfulLine = segment.lines
        .asSequence()
        .map(::stripPreambleBlockMarkers)
        .firstOrNull { it.isNotBlank() }
        ?: return false

    return isImportLine(firstMeaningfulLine)
}

private fun generatedPreambleGroup(key: String): String? {
    val prefix = key.substringBefore(":", key)
    return when {
        prefix.startsWith("graphics-stack-auto") -> "stack-auto"
        prefix.startsWith("stack-auto") -> "stack-auto"
        prefix.startsWith("graphics-") -> "graphics"
        prefix.startsWith("compute-") -> "compute"
        prefix.startsWith("mesh-") -> "mesh"
        prefix.startsWith("ray-") -> "ray"
        else -> null
    }
}

fun normalizePreambleText(preamble: String): String {
    val merged = LinkedHashMap<String, PreambleSegment>()
    splitPreambleSegments(preamble.lines()).forEach { segment ->
        val key = preambleSegmentKey(segment)
        if (key.isNotEmpty() && !merged.containsKey(key)) {
            merged[key] = segment
        }
    }
    val imports = merged.values.filter(::isImportSegment)
    val nonImports = merged.values.filterNot(::isImportSegment)
    return (imports + nonImports).flatMap { it.lines }.joinToString(separator = "\n")
}

private fun prefixed(prefix: String, code: String): String = "/*$prefix*/ $code"

private fun prefixedBlock(prefix: String, code: String): String =
    "$preambleBlockStartMarker\n/*$prefix*/\n${code.trim()}\n$preambleBlockEndMarker"

private val kotlinIdentifierRegex = Regex("[A-Za-z_][A-Za-z0-9_]*")
private val kotlinHardKeywords = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
    "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
    "typeof", "val", "var", "when", "while"
)

private fun kotlinMemberName(name: String): String =
    if (kotlinIdentifierRegex.matches(name) && name !in kotlinHardKeywords) name else "`$name`"

private data class GeneratedShaderStructure(
    val glslName: String,
    val className: String,
    val members: List<Introspected.Member>,
    val byteSize: Int,
    val signature: String,
    val bufferReference: Boolean
)

private fun shaderMemberLayoutSize(member: Introspected.Member): Int =
    if (member.physicalPointer) PrimitiveType.deviceptr.sizeInBytes.toInt()
    else member.type.fold(
        { it.sizeInBytes.toInt() },
        { nested -> shaderMembersLayoutSize(nested) }
    )

private fun shaderMembersLayoutSize(members: List<Introspected.Member>): Int =
    if (members.isEmpty()) 0 else members.maxOf { it.offset + shaderMemberLayoutSize(it) }

private fun shaderMemberSignature(member: Introspected.Member): String =
    buildString {
        append(member.name)
        append("@")
        append(member.offset)
        append(":")
        append(member.arrayStride)
        append(":")
        append(member.physicalPointer)
        append(":")
        append(member.type.fold({ it.name }, { nested -> nested.joinToString(prefix = "{", postfix = "}", separator = ",", transform = ::shaderMemberSignature) }))
    }

private fun shaderMembersSignature(members: List<Introspected.Member>): String =
    members.joinToString(separator = ";", transform = ::shaderMemberSignature)

private fun sanitizeKotlinTypeName(name: String): String {
    val parts = name.split(Regex("[^A-Za-z0-9_]+")).filter { it.isNotBlank() }
    val joined = parts.joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }.ifBlank { "ShaderStructure" }
    val prefixed = if (joined.first().isDigit()) "Shader$joined" else joined
    return prefixed
}

private fun sanitizeKotlinFunctionSuffix(name: String): String =
    sanitizeKotlinTypeName(name)

private fun collectGeneratedShaderStructures(introspections: List<Introspection>): List<GeneratedShaderStructure> {
    data class Candidate(
        val name: String,
        val members: List<Introspected.Member>,
        val size: Int,
        val bufferReference: Boolean
    )

    val candidates = mutableListOf<Candidate>()
    introspections.forEach { i ->
        i.structureTypes.forEach { s ->
            candidates += Candidate(s.name, s.members, s.size, s.bufferReference)
        }
        i.ubos.forEach { ubo ->
            candidates += Candidate(ubo.name, ubo.members, ubo.size, bufferReference = false)
        }
        i.ssbo.forEach { ssbo ->
            candidates += Candidate(ssbo.name, ssbo.members, ssbo.size, bufferReference = false)
        }
    }

    val out = mutableListOf<GeneratedShaderStructure>()
    val signatureByClassName = mutableMapOf<String, String>()
    val seenByNameAndSignature = mutableSetOf<Pair<String, String>>()

    candidates.forEach { candidate ->
        val signature = shaderMembersSignature(candidate.members)
        if (!seenByNameAndSignature.add(candidate.name to signature)) return@forEach

        val baseClassName = sanitizeKotlinTypeName(candidate.name)
        var className = baseClassName
        var suffix = 2
        while (signatureByClassName[className] != null && signatureByClassName[className] != signature) {
            className = "$baseClassName$suffix"
            suffix += 1
        }
        signatureByClassName[className] = signature

        out += GeneratedShaderStructure(
            glslName = candidate.name,
            className = className,
            members = candidate.members,
            byteSize = maxOf(candidate.size, shaderMembersLayoutSize(candidate.members)),
            signature = signature,
            bufferReference = candidate.bufferReference
        )
    }

    return out
}

private fun buildShaderStructurePreamble(prefix: String, introspections: List<Introspection>): String {
    val structures = collectGeneratedShaderStructures(introspections)
    if (structures.isEmpty()) return ""

    val bySignature = structures.associateBy { it.signature }
    val emitted = mutableSetOf<String>()
    val chunks = mutableListOf<String>()

    fun nestedLayoutFor(member: Introspected.Member): GeneratedShaderStructure? {
        if (member.physicalPointer) return null
        val nested = member.type.fold({ null }, { it }) ?: return null
        return bySignature[shaderMembersSignature(nested)]
    }

    lateinit var memberListExpression: (List<Introspected.Member>, String) -> String

    fun memberTypeExpression(member: Introspected.Member): String {
        if (member.physicalPointer) {
            return "PrimitiveType.deviceptr.left()"
        }
        return member.type.fold(
            { "PrimitiveType.${it.name}.left()" },
            { nested ->
                val nestedLayout = bySignature[shaderMembersSignature(nested)]
                if (nestedLayout != null) "${nestedLayout.className}LayoutMembers().right()"
                else memberListExpression(nested, "                    ").trim().removeSuffix(",").let { "$it.right()" }
            }
        )
    }

    memberListExpression = fun(members: List<Introspected.Member>, indent: String): String {
        if (members.isEmpty()) return "emptyList()"
        val nextIndent = "$indent    "
        return buildString {
            appendLine("listOf(")
            members.forEach { member ->
                append(nextIndent)
                append("Introspected.Member(")
                append("\"${member.name}\", ")
                append("${member.offset}, ")
                append(memberTypeExpression(member))
                append(", ")
                append(member.arrayStride)
                append(", ")
                append(member.physicalPointer)
                appendLine("),")
            }
            append("$indent)")
        }
    }

    fun emit(structure: GeneratedShaderStructure) {
        if (!emitted.add(structure.className)) return

        structure.members.mapNotNull(::nestedLayoutFor).forEach(::emit)

        val nestedProperties = structure.members.mapNotNull { member ->
            nestedLayoutFor(member)?.let { nested ->
                val memberName = kotlinMemberName(member.name)
                """    val $memberName: ${nested.className}
        get() = childStructure("${member.name}") { members, baseOffset, pathPrefix, root ->
            ${nested.className}(members, baseOffset, pathPrefix, root)
        }"""
            }
        }

        val primitiveProperties = structure.members.filter { nestedLayoutFor(it) == null }.map { member ->
            val ty = kotlinBindingTypeName(member)
            "    var ${kotlinMemberName(member.name)} by Structure.FromStructure<$ty>()"
        }

        val helperSuffix = sanitizeKotlinFunctionSuffix(structure.className)
        val layoutFunctionName = "${structure.className}LayoutMembers"
        val byteSizeFunctionName = "${structure.className}ByteSize"
        val body = buildString {
            appendLine("class ${structure.className}(")
            appendLine("    members: List<Introspected.Member> = $layoutFunctionName(),")
            appendLine("    baseOffset: Int = 0,")
            appendLine("    pathPrefix: String = \"\",")
            appendLine("    root: Structure? = null")
            appendLine(") : Structure(members, baseOffset, pathPrefix, root) {")
            appendLine("    init { setMembers(members) }")
            (nestedProperties + primitiveProperties).forEach {
                appendLine(it)
            }
            appendLine()
            appendLine("}")
            appendLine()
            appendLine("fun $byteSizeFunctionName(): Long = ${structure.byteSize}L")
            appendLine()
            appendLine("fun $layoutFunctionName(): List<Introspected.Member> =")
            appendLine("    ${memberListExpression(structure.members, "    ")}")
            appendLine()
            appendLine("fun HasBuffer.as$helperSuffix(): ${structure.className} =")
            appendLine("    asStructure(${structure.className}(), $layoutFunctionName())")
            appendLine()
            appendLine("fun BufferParameters.as$helperSuffix(): ${structure.className} =")
            appendLine("    buffer.as$helperSuffix()")
        }

        chunks += prefixedBlock("$prefix-structure-type:${structure.glslName}:${structure.className}", body)
    }

    structures.forEach(::emit)
    return chunks.joinToString(separator = "\n") + "\n"
}

private fun mergePreambleSegments(existing: List<String>, incoming: List<String>): List<PreambleSegment> {
    val merged = LinkedHashMap<String, PreambleSegment>()
    val incomingSegments = splitPreambleSegments(incoming)
    val replacementGroups = incomingSegments
        .mapNotNull { generatedPreambleGroup(preambleSegmentKey(it)) }
        .toSet()

    splitPreambleSegments(existing).forEach { segment ->
        val key = preambleSegmentKey(segment)
        if (generatedPreambleGroup(key) in replacementGroups) return@forEach
        if (key.isNotEmpty() && !merged.containsKey(key)) {
            merged[key] = segment
        }
    }

    incomingSegments.forEach { segment ->
        val key = preambleSegmentKey(segment)
        if (key.isNotEmpty()) {
            // New insertions should win so reloads replace generated bindings in-place.
            merged[key] = segment
        }
    }

    return merged.values.toList()
}

fun applyInsertToPreamble(box: Box, e: Insert) {
    val b = e.target ?: box
    val incomingLines = buildList {
        if (e.preamble.isNotBlank()) addAll(e.preamble.trim().lines())
        if (e.preambleAfterImports.isNotBlank()) addAll(e.preambleAfterImports.trim().lines())
    }.map { it.trimEnd() }.filter { it.isNotBlank() }

    if (incomingLines.isEmpty()) return

    val existingLines = b.preamble.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
    val merged = mergePreambleSegments(existingLines, incomingLines)
    val imports = merged.filter(::isImportSegment)
    val nonImports = merged.filterNot(::isImportSegment)
    b.preamble = normalizePreambleText((imports + nonImports).flatMap { it.lines }.joinToString(separator = "\n"))

}

private fun clearShaderErrors(box: Box, vararg properties: String) {
    if (!currentShaderReloadMayReport()) return
    properties.forEach { property ->
        box.error(null, property, -1)
    }
}

private fun runShaderReloadAction(
    boxes: Iterable<Box>,
    reloadingMessage: String,
    reloadedMessage: String,
    reload: (Box) -> Boolean
) {
    notify(reloadingMessage, "shader")
    var ok = 0
    var failed = 0
    boxes.forEach { selected ->
        val success = try {
            reload(selected)
        } catch (t: Throwable) {
            lprintln(
                "error,graphics",
                "unexpected error reloading shader in '${selected.name}' (${selected.id}): ${t.message}"
            )
            selected.error(t.message ?: "shader reload failed", "code", -1)
            false
        }
        if (success) ok++ else failed++
    }
    val summary = if (failed == 0) "$reloadedMessage ($ok)" else "$reloadedMessage ($ok ok, $failed failed)"
    notify(summary, "shader")
}

private fun runShaderUpdateAction(
    boxes: Iterable<Box>,
    reloadingMessage: String,
    update: suspend (Box, ShaderReloadKey, Long) -> Boolean
) {
    val selected = boxes.toList()
    if (selected.isEmpty()) return

    notify(reloadingMessage, "shader")
    selected.forEach { box ->
        val (key, generation) = nextShaderGeneration(box, ShaderReloadType.GRAPHICS)
        shaderReloadScope.launch {
            val success = try {
                update(box, key, generation)
            } catch (t: Throwable) {
                lprintln(
                    "error,graphics",
                    "unexpected error updating shader in '${box.name}' (${box.id}): ${t.message}"
                )
                if (isCurrentShaderGeneration(key, generation)) {
                    withContext(Runloop.runLoop.main) {
                        box.error(t.message ?: "shader update failed", "code", -1)
                    }
                }
                false
            }

            if (isCurrentShaderGeneration(key, generation)) {
                withContext(Runloop.runLoop.main) {
                    notify(
                        if (success) "Updated shader in '${box.name}'" else "Shader update failed in '${box.name}'",
                        "shader"
                    )
                }
            }
        }
    }
}

private fun runComputeShaderUpdateAction(
    boxes: Iterable<Box>,
    reloadingMessage: String,
    update: suspend (Box, ShaderReloadKey, Long) -> Boolean
) {
    val selected = boxes.toList()
    if (selected.isEmpty()) return

    notify(reloadingMessage, "shader")
    selected.forEach { box ->
        val (key, generation) = nextShaderGeneration(box, ShaderReloadType.COMPUTE)
        shaderReloadScope.launch {
            val success = try {
                update(box, key, generation)
            } catch (t: Throwable) {
                lprintln(
                    "error,graphics",
                    "unexpected error updating compute shader in '${box.name}' (${box.id}): ${t.message}"
                )
                if (isCurrentShaderGeneration(key, generation)) {
                    withContext(Runloop.runLoop.main) {
                        box.error(t.message ?: "compute shader update failed", "code", -1)
                    }
                }
                false
            }

            if (isCurrentShaderGeneration(key, generation)) {
                withContext(Runloop.runLoop.main) {
                    notify(
                        if (success) "Updated compute shader in '${box.name}'" else "Compute shader update failed in '${box.name}'",
                        "shader"
                    )
                }
            }
        }
    }
}

@OptIn(DelicateCoroutinesApi::class)
private fun Box.installGraphicsShaderReloadAction() {
    this.actions += "moduleHere_reload" to Action("Reload Shader|alt r", "Reloads the graphics shader in this box") {
        runBlocking {
            GlobalScope.launch(Runloop.runLoop.main) {
                runShaderReloadAction(
                    boxes = it,
                    reloadingMessage = "Reloading shader…",
                    reloadedMessage = "Reloaded shader"
                ) { selected ->
                    reloadShader(selected)
                }
            }
        }
    }
}

private fun Box.installGraphicsShaderUpdateAction() {
    this.actions += "moduleHere_update" to Action(
        "Update Shader|alt shift enter",
        "Updates the graphics shader only if its reflected ABI is unchanged"
    ) {
        runShaderUpdateAction(
            boxes = it,
            reloadingMessage = "Updating shader…"
        ) { selected, key, generation ->
            updateShader(selected, key, generation)
        }
    }
}

@OptIn(DelicateCoroutinesApi::class)
private fun Box.installComputeShaderReloadAction() {
    this.actions += "computeModuleHere_reload" to Action(
        "Reload Compute Shader|alt r",
        "reloads the compute shader in this box"
    ) {
        runBlocking {
            GlobalScope.launch(Runloop.runLoop.main) {
                runShaderReloadAction(
                    boxes = it,
                    reloadingMessage = "Reloading shader…",
                    reloadedMessage = "Reloaded shader"
                ) { selected ->
                    reloadComputeShader(selected)
                }
            }
        }
    }
}

private fun Box.installComputeShaderUpdateAction() {
    this.actions += "computeModuleHere_update" to Action(
        "Update Compute Shader|alt shift enter",
        "updates the compute shader only if its reflected ABI is unchanged"
    ) {
        runComputeShaderUpdateAction(
            boxes = it,
            reloadingMessage = "Updating compute shader…"
        ) { selected, key, generation ->
            updateComputeShader(selected, key, generation)
        }
    }
}

@OptIn(DelicateCoroutinesApi::class)
private fun Box.installMeshShaderReloadAction() {
    this.actions += "meshModuleHere_reload" to Action(
        "Reload Mesh Shader|alt r",
        "reloads the mesh shader in this box"
    ) {
        runBlocking {
            GlobalScope.launch(Runloop.runLoop.main) {
                runShaderReloadAction(
                    boxes = it,
                    reloadingMessage = "Reloading shader…",
                    reloadedMessage = "Reloaded shader"
                ) { selected ->
                    reloadMeshShader(selected)
                }
            }
        }
    }
}

@OptIn(DelicateCoroutinesApi::class)
private fun Box.installRayTracingShaderReloadAction() {
    this.actions += "rayTracingModuleHere_reload" to Action(
        "Reload Ray Tracing Shader|alt r",
        "reloads the ray tracing shader pipeline in this box"
    ) {
        runBlocking {
            GlobalScope.launch(Runloop.runLoop.main) {
                runShaderReloadAction(
                    boxes = it,
                    reloadingMessage = "Reloading shader…",
                    reloadedMessage = "Reloaded shader"
                ) { selected ->
                    reloadRayTracingShader(selected)
                }
            }
        }
    }
}

// ------------------------------
// Graphics (vertex/fragment) reload path
// ------------------------------
private suspend fun updateShader(box: Box, key: ShaderReloadKey, generation: Long): Boolean {
    withContext(Runloop.runLoop.main) {
        if (isCurrentShaderGeneration(key, generation)) clearShaderErrorsForBaseProperties(box, "vertex", "fragment")
    }

    val prepared = withShaderReloadErrorGate(key, generation) {
        val vertexStage = prepareShaderStage(box, ShaderKind.VERTEX_SHADER, "vertex") ?: return false
        val fragmentStage = prepareShaderStage(
            box,
            ShaderKind.FRAGMENT_SHADER,
            "fragment",
            priorStages = mapOf(ShaderKind.VERTEX_SHADER to vertexStage)
        ) ?: return false
        val vertexCompile = compileStage(box, vertexStage)
        val fragmentCompile = compileStage(box, fragmentStage)
        if (vertexCompile.hadError || fragmentCompile.hadError) {
            lprintln("error,graphics", "shader update compilation failed; keeping previous module.")
            return false
        }
        val vi = vertexCompile.introspection ?: return false
        val fi = fragmentCompile.introspection ?: return false
        Pair(vertexStage to vi, fragmentStage to fi)
    }

    val (vertexPrepared, fragmentPrepared) = prepared
    val (vertexStage, vi) = vertexPrepared
    val (fragmentStage, fi) = fragmentPrepared
    val pointer = box._shader ?: run {
        reportShaderStageError(box, "code", "shader update", "No graphics shader is attached to this box.")
        return false
    }
    val current = pointer.current

    val abiCheck = graphicsShaderAbiCheck(
        current.parameters.vertexIntrospection ?: current.vi,
        current.parameters.fragmentIntrospection ?: current.fi,
        vi,
        fi
    )
    if (!abiCheck.compatible) {
        reportShaderStageError(box, "code", "shader update", abiCheck.message)
        return false
    }

    val pipelineUpdate = try {
        current.buildPipelineUpdate(vi, fi)
    } catch (t: Throwable) {
        reportShaderStageError(
            box,
            "code",
            "shader update pipeline creation",
            t.message ?: "Unable to create graphics pipeline update.",
            t
        )
        return false
    }

    return withContext(Runloop.runLoop.main) {
        if (!isCurrentShaderGeneration(key, generation) || box._shader !== pointer || pointer.current !== current) {
            current.destroyUnusedPipelineUpdate(pipelineUpdate)
            return@withContext false
        }

        assignPreparedShaderStage(box, vertexStage)
        assignPreparedShaderStage(box, fragmentStage)

        current.parameters.vertex = vertexStage.glslSource
        current.parameters.fragment = fragmentStage.glslSource
        current.parameters.vertexSourceName = vertexStage.sourceName
        current.parameters.fragmentSourceName = fragmentStage.sourceName
        current.parameters.vertexIncludeResolver = shaderIncludeResolverFor(box, vertexStage.sourceProperty)
        current.parameters.fragmentIncludeResolver = shaderIncludeResolverFor(box, fragmentStage.sourceProperty)

        FramePresentationLog.mark("graphics shader update install begin box=${box.name} module=${current.parameters.name}")
        current.installPipelineUpdate(pipelineUpdate)
        FramePresentationLog.mark("graphics shader update install end box=${box.name} module=${current.parameters.name}")
        clearShaderErrorsForBaseProperties(box, "vertex", "fragment")
        true
    }
}

fun reloadShader(box: Box): Boolean {
    clearShaderErrorsForBaseProperties(box, "vertex", "fragment")
    val vertexStage = prepareShaderStage(box, ShaderKind.VERTEX_SHADER, "vertex") ?: return false
    val fragmentStage = prepareShaderStage(
        box,
        ShaderKind.FRAGMENT_SHADER,
        "fragment",
        priorStages = mapOf(ShaderKind.VERTEX_SHADER to vertexStage)
    ) ?: return false

    assignPreparedShaderStage(box, vertexStage)
    assignPreparedShaderStage(box, fragmentStage)

    val (vi, vErr) = compileStage(box, vertexStage)
    val (fi, fErr) = compileStage(box, fragmentStage)

    if (vErr || fErr) {
        lprintln("error,graphics", "shader compilation failed; skipping module reload.")
        return false
    }

    // keep consistent with the Module path: build Insert and apply to preamble
    val insert = insertForShader(vi!!, fi!!, "_moduleHere", listOf("()"), box)
    applyInsertToPreamble(box, insert)

    val p = box._shader!!.current.parameters.copy().apply {
        vertex = box._vertex!!
        fragment = box._fragment!!
        vertexIntrospection = vi
        fragmentIntrospection = fi
    }
    attachFieldProbe(box, p)

    // todo: patch error handler if you need finer-grained feedback
    box._shader!!.reload(p, forceReresolve = true)
    clearShaderErrorsForBaseProperties(box, "vertex", "fragment")
    return true
}

fun Box.attachShader(module: ModulePointer, overwriteProperties: Boolean = false): ModulePointer {
    installGraphicsShaderReloadAction()
    installGraphicsShaderUpdateAction()

    val parameters = module.current.parameters
    require(parameters.vertex.isNotBlank()) { "Attached graphics module must have a vertex shader source." }
    require(parameters.fragment.isNotBlank()) { "Attached graphics module must have a fragment shader source." }

    if (overwriteProperties || !hasShaderSource(this, "vertex")) setPropertyRaw("vertex", parameters.vertex)
    if (overwriteProperties || !hasShaderSource(this, "fragment")) setPropertyRaw("fragment", parameters.fragment)

    _vertex = parameters.vertex
    _fragment = parameters.fragment
    _shader = module
    registerStackAutoShaderOwner(this, module)

    reloadShader(this)
    return module
}

// ------------------------------
// Compute helpers (DRY the previously duplicated postImport construction)
// ------------------------------
private fun kotlinBindingTypeName(member: Introspected.Member): String =
    if (member.physicalPointer) "afield.four.buffers.HasBuffer"
    else member.type.fold({ it.kotlinTypeName }, { "Any" })

private fun buildComputeInsert(ci: Introspection, box: Box? = null): Insert {
    var postImport = ""
    postImport += buildShaderStructurePreamble("compute", listOf(ci))
    buildStackAutoShaderPreamble(box).takeIf { it.isNotBlank() }?.let {
        postImport += it + "\n"
    }

    // Push constants
    ci.pushConstants.flatMap { it.members }.distinctBy { it.name }.forEach { member ->
            postImport += prefixed(
                "compute-pc-structure:${member.name}",
                "var Structure.${member.name} by Structure.FromStructure<Any>()"
            ) + "\n"
            val ty = kotlinBindingTypeName(member)
            postImport += prefixed(
                "compute-pc-binding:${member.name}",
                "var ComputeMesh.${member.name} by ComputeMesh.BoundFromStructure<$ty>()"
            ) + "\n"
            postImport += prefixed(
                "compute-pc-binding-group:${member.name}",
                "var ComputeMeshGroup.${member.name} by ComputeMeshGroup.BoundFromStructure<$ty>()"
            ) + "\n"
    }

    // UBOs
    ci.ubos.distinctBy { it.name }.forEach { ubo ->
        postImport += prefixed(
            "compute-ubo:${ubo.name}",
            "val ModulePointer.${ubo.name} by FromUniform()"
        ) + "\n"
        postImport += prefixed(
            "compute-ubo-mesh:${ubo.name}",
            "var Mesh.${ubo.name} by Mesh.FromStructure<Structure>()"
        ) + "\n"
        ubo.members.forEach { m ->
            val ty = kotlinBindingTypeName(m)
            postImport += prefixed(
                "compute-ubo-member:${ubo.name}:${m.name}",
                "var Structure.${m.name} by Structure.FromStructure<$ty>()"
            ) + "\n"
        }
    }

    // SSBOs
    ci.ssbo.distinctBy { it.name }.forEach { ssbo ->
        postImport += prefixed(
            "compute-ssbo:${ssbo.name}",
            "val ComputeModulePointer.${ssbo.name} by ComputeMesh.FromUniform()"
        ) + "\n"
        // Arrays / headers would need special handling if you decide to expose members
    }

    return Insert(
        preamble = """
            import afield.four.buffers.*
            import afield.four.modules.*
            import arrow.core.left
            import arrow.core.right
        """.trimIndent(),
        preambleAfterImports = postImport
    )
}

fun reloadComputeShader(box: Box): Boolean {
    clearShaderErrorsForBaseProperties(box, "compute")
    val computeStage = prepareShaderStage(box, ShaderKind.COMPUTE_SHADER, "compute") ?: return false
    assignPreparedShaderStage(box, computeStage)

    val (ci, cErr) = compileStage(box, computeStage)
    if (cErr) {
        lprintln("error,graphics", "compute shader compilation failed; skipping compute module reload.")
        return false
    }

    // Rebuild/import bindings like graphics path
    val insert = buildComputeInsert(ci!!, box)
    applyInsertToPreamble(box, insert)

    val p = box._computeShader!!.current.parameters.copy().apply {
        compute = box._compute!!
        computeIntrospection = ci
    }
    attachFieldProbe(box, p)
    // todo: patch error handler if you need finer-grained feedback
    box._computeShader!!.reload(p)
    clearShaderErrorsForBaseProperties(box, "compute")
    return true
}

private suspend fun updateComputeShader(box: Box, key: ShaderReloadKey, generation: Long): Boolean {
    withContext(Runloop.runLoop.main) {
        if (isCurrentShaderGeneration(key, generation)) clearShaderErrorsForBaseProperties(box, "compute")
    }

    val prepared = withShaderReloadErrorGate(key, generation) {
        val computeStage = prepareShaderStage(box, ShaderKind.COMPUTE_SHADER, "compute") ?: return false
        val computeCompile = compileStage(box, computeStage)
        if (computeCompile.hadError) {
            lprintln("error,graphics", "compute shader update compilation failed; keeping previous module.")
            return false
        }
        val ci = computeCompile.introspection ?: return false
        computeStage to ci
    }

    val (computeStage, ci) = prepared
    val pointer = box._computeShader ?: run {
        reportShaderStageError(box, "code", "compute shader update", "No compute shader is attached to this box.")
        return false
    }
    val current = pointer.current

    val abiCheck = computeShaderAbiCheck(
        current.parameters.computeIntrospection ?: current.ci,
        ci
    )
    if (!abiCheck.compatible) {
        reportShaderStageError(box, "code", "compute shader update", abiCheck.message)
        return false
    }

    val pipelineUpdate = try {
        current.buildPipelineUpdate(ci)
    } catch (t: Throwable) {
        reportShaderStageError(
            box,
            "code",
            "compute shader update pipeline creation",
            t.message ?: "Unable to create compute pipeline update.",
            t
        )
        return false
    }

    return withContext(Runloop.runLoop.main) {
        if (!isCurrentShaderGeneration(key, generation) || box._computeShader !== pointer || pointer.current !== current) {
            current.destroyUnusedPipelineUpdate(pipelineUpdate)
            return@withContext false
        }

        assignPreparedShaderStage(box, computeStage)

        current.parameters.compute = computeStage.glslSource
        current.parameters.computeSourceName = computeStage.sourceName
        current.parameters.computeIncludeResolver = shaderIncludeResolverFor(box, computeStage.sourceProperty)

        FramePresentationLog.mark("compute shader update install begin box=${box.name} module=${current.parameters.name}")
        current.installPipelineUpdate(pipelineUpdate)
        FramePresentationLog.mark("compute shader update install end box=${box.name} module=${current.parameters.name}")
        clearShaderErrorsForBaseProperties(box, "compute")
        true
    }
}

fun Box.attachShader(module: ComputeModulePointer, overwriteProperties: Boolean = false): ComputeModulePointer {
    installComputeShaderReloadAction()
    installComputeShaderUpdateAction()

    val parameters = module.current.parameters
    require(parameters.compute.isNotBlank()) { "Attached compute module must have a compute shader source." }

    if (overwriteProperties || !hasShaderSource(this, "compute")) setPropertyRaw("compute", parameters.compute)

    _compute = parameters.compute
    _computeShader = module
    registerStackAutoShaderOwner(this, module)

    reloadComputeShader(this)
    return module
}

// ------------------------------
// Mesh helpers (DRY the previously duplicated postImport construction)
// ------------------------------
private fun buildMeshInsert(ti: Introspection, mi: Introspection, fi: Introspection, box: Box? = null): Insert {
    var postImport = ""
    postImport += buildShaderStructurePreamble("mesh", listOf(ti, mi, fi))
    buildStackAutoShaderPreamble(box).takeIf { it.isNotBlank() }?.let {
        postImport += it + "\n"
    }

    // Push constants across task+mesh+fragment
    (ti.pushConstants + mi.pushConstants + fi.pushConstants).flatMap { it.members }.distinctBy { it.name }.forEach { member ->
            postImport += prefixed(
                "mesh-pc-structure:${member.name}",
                "var Structure.${member.name} by Structure.FromStructure<Any>()"
            ) + "\n"
            val ty = kotlinBindingTypeName(member)
            postImport += prefixed(
                "mesh-pc-binding:${member.name}",
                "var MeshMesh.${member.name} by MeshMesh.BoundFromStructure<$ty>()"
            ) + "\n"
            postImport += prefixed(
                "mesh-pc-binding-group:${member.name}",
                "var MeshMeshGroup.${member.name} by MeshMeshGroup.BoundFromStructure<$ty>()"
            ) + "\n"
    }

    // UBOs across task+mesh+fragment
    (ti.ubos + mi.ubos + fi.ubos).distinctBy { it.name }.forEach { ubo ->
        postImport += prefixed(
            "mesh-ubo:${ubo.name}",
            "val MeshModulePointer.${ubo.name} by FromUniform()"
        ) + "\n"
        postImport += prefixed(
            "mesh-ubo-mesh:${ubo.name}",
            "var MeshMesh.${ubo.name} by MeshMesh.FromStructure<Structure>()"
        ) + "\n"
        ubo.members.forEach { m ->
            val ty = kotlinBindingTypeName(m)
            postImport += prefixed(
                "mesh-ubo-member:${ubo.name}:${m.name}",
                "var Structure.${m.name} by Structure.FromStructure<$ty>()"
            ) + "\n"
        }
    }

    // SSBOs across task+mesh+fragment
    (ti.ssbo + mi.ssbo + fi.ssbo).distinctBy { it.name }.forEach { ssbo ->
        postImport += prefixed(
            "mesh-ssbo:${ssbo.name}",
            "val MeshModulePointer.${ssbo.name} by MeshMesh.FromUniform()"
        ) + "\n"
    }

    return Insert(
        preamble = """
            import afield.four.buffers.*
            import afield.four.modules.*
            import arrow.core.left
            import arrow.core.right
        """.trimIndent(),
        preambleAfterImports = postImport
    )
}

fun reloadMeshShader(box: Box): Boolean {
    clearShaderErrorsForBaseProperties(box, "task", "mesh", "fragment")
    val taskStage = prepareShaderStage(box, ShaderKind.TASK_SHADER, "task") ?: return false
    val meshStage = prepareShaderStage(
        box,
        ShaderKind.MESH_SHADER,
        "mesh",
        priorStages = mapOf(ShaderKind.TASK_SHADER to taskStage)
    ) ?: return false
    val fragmentStage = prepareShaderStage(
        box,
        ShaderKind.FRAGMENT_SHADER,
        "fragment",
        priorStages = mapOf(
            ShaderKind.TASK_SHADER to taskStage,
            ShaderKind.MESH_SHADER to meshStage
        )
    ) ?: return false

    assignPreparedShaderStage(box, taskStage)
    assignPreparedShaderStage(box, meshStage)
    assignPreparedShaderStage(box, fragmentStage)

    val (ti, tErr) = compileStage(box, taskStage)
    val (mi, mErr) = compileStage(box, meshStage)
    val (fi, fErr) = compileStage(box, fragmentStage)

    if (tErr || mErr || fErr) {
        lprintln("error,graphics", "mesh pipeline compilation failed; skipping mesh module reload.")
        return false
    }

    // Rebuild/import bindings like graphics path
    val insert = buildMeshInsert(ti!!, mi!!, fi!!, box)
    applyInsertToPreamble(box, insert)

    val p = box._meshShader!!.current.parameters.copy().apply {
        mesh = box._mesh!!
        task = box._task!!
        fragment = box._fragment!!
        taskIntrospection = ti
        meshIntrospection = mi
        fragmentIntrospection = fi
    }
    attachFieldProbe(box, p)
    // todo: patch error handler if you need finer-grained feedback
    box._meshShader!!.reload(p)
    clearShaderErrorsForBaseProperties(box, "task", "mesh", "fragment")
    return true
}

fun Box.attachShader(module: MeshModulePointer, overwriteProperties: Boolean = false): MeshModulePointer {
    installMeshShaderReloadAction()

    val parameters = module.current.parameters
    require(parameters.task.isNotBlank()) { "Attached mesh module must have a task shader source." }
    require(parameters.mesh.isNotBlank()) { "Attached mesh module must have a mesh shader source." }
    require(parameters.fragment.isNotBlank()) { "Attached mesh module must have a fragment shader source." }

    if (overwriteProperties || !hasShaderSource(this, "task")) setPropertyRaw("task", parameters.task)
    if (overwriteProperties || !hasShaderSource(this, "mesh")) setPropertyRaw("mesh", parameters.mesh)
    if (overwriteProperties || !hasShaderSource(this, "fragment")) setPropertyRaw("fragment", parameters.fragment)

    _task = parameters.task
    _mesh = parameters.mesh
    _fragment = parameters.fragment
    _meshShader = module
    registerStackAutoShaderOwner(this, module)

    reloadMeshShader(this)
    return module
}

// ------------------------------
// Ray tracing helpers
// ------------------------------
private data class RayTracingStageSpec(
    val baseProperty: String,
    val kind: ShaderKind,
    val required: Boolean = false
)

private val rayTracingStageSpecs = listOf(
    RayTracingStageSpec("raygen", ShaderKind.RAYGEN_SHADER, required = true),
    RayTracingStageSpec("miss", ShaderKind.MISS_SHADER),
    RayTracingStageSpec("closesthit", ShaderKind.CLOSEST_HIT_SHADER),
    RayTracingStageSpec("anyhit", ShaderKind.ANY_HIT_SHADER),
    RayTracingStageSpec("intersection", ShaderKind.INTERSECTION_SHADER),
    RayTracingStageSpec("callable", ShaderKind.CALLABLE_SHADER)
)

private fun prepareRayTracingStages(box: Box): List<PreparedShaderStage>? {
    val prepared = mutableListOf<PreparedShaderStage>()
    val prior = mutableMapOf<ShaderKind, PreparedShaderStage>()
    for (spec in rayTracingStageSpecs) {
        if (!hasShaderSource(box, spec.baseProperty)) {
            if (spec.required) {
                reportShaderStageError(
                    box,
                    sourcePropertyForStage(box, spec.baseProperty),
                    "${spec.baseProperty} shader setup",
                    "No shader source found for property '${spec.baseProperty}'."
                )
                return null
            }
            continue
        }
        val stage = prepareShaderStage(box, spec.kind, spec.baseProperty, priorStages = prior) ?: return null
        prepared += stage
        prior[spec.kind] = stage
    }
    return prepared
}

private fun buildRayTracingInsert(introspections: List<Introspection>): Insert {
    var postImport = ""
    postImport += buildShaderStructurePreamble("ray", introspections)

    introspections.flatMap { it.pushConstants }.flatMap { it.members }.distinctBy { it.name }.forEach { member ->
            postImport += prefixed(
                "ray-pc-structure:${member.name}",
                "var Structure.${member.name} by Structure.FromStructure<Any>()"
            ) + "\n"
            val ty = kotlinBindingTypeName(member)
            postImport += prefixed(
                "ray-pc-binding:${member.name}",
                "var RayTracingDispatch.${member.name} by RayTracingDispatch.BoundFromStructure<$ty>()"
            ) + "\n"
    }

    introspections.flatMap { it.ubos }.distinctBy { it.name }.forEach { ubo ->
        postImport += prefixed(
            "ray-ubo:${ubo.name}",
            "val RayTracingModulePointer.${ubo.name} by RayTracingFromUniform()"
        ) + "\n"
        ubo.members.forEach { member ->
            val ty = kotlinBindingTypeName(member)
            postImport += prefixed(
                "ray-ubo-member:${ubo.name}:${member.name}",
                "var Structure.${member.name} by Structure.FromStructure<$ty>()"
            ) + "\n"
        }
    }

    introspections.flatMap { it.ssbo }.distinctBy { it.name }.forEach { ssbo ->
        postImport += prefixed(
            "ray-ssbo:${ssbo.name}",
            "val RayTracingModulePointer.${ssbo.name} by RayTracingFromUniform()"
        ) + "\n"
    }

    return Insert(
        preamble = """
            import afield.four.buffers.*
            import afield.four.modules.*
            import arrow.core.left
            import arrow.core.right
        """.trimIndent(),
        preambleAfterImports = postImport
    )
}

fun reloadRayTracingShader(box: Box): Boolean {
    clearShaderErrorsForBaseProperties(box, "raygen", "miss", "closesthit", "anyhit", "intersection", "callable")
    val stages = prepareRayTracingStages(box) ?: return false
    stages.forEach { assignPreparedShaderStage(box, it) }

    val compiled = stages.map { stage -> stage to compileStage(box, stage) }
    if (compiled.any { it.second.hadError || it.second.introspection == null }) {
        lprintln("error,graphics", "ray tracing pipeline compilation failed; skipping ray tracing module reload.")
        return false
    }

    val introspections = compiled.map { it.second.introspection!! }
    applyInsertToPreamble(box, buildRayTracingInsert(introspections))

    val p = box._rayTracingShader!!.current.parameters.copy().apply {
        raygen = box._raygen ?: ""
        miss = box._miss ?: ""
        closesthit = box._closesthit ?: ""
        anyhit = box._anyhit ?: ""
        intersection = box._intersection ?: ""
        callable = box._callable ?: ""
        raygenIntrospection = compiled.introspectionFor(ShaderKind.RAYGEN_SHADER)
        missIntrospection = compiled.introspectionFor(ShaderKind.MISS_SHADER)
        closesthitIntrospection = compiled.introspectionFor(ShaderKind.CLOSEST_HIT_SHADER)
        anyhitIntrospection = compiled.introspectionFor(ShaderKind.ANY_HIT_SHADER)
        intersectionIntrospection = compiled.introspectionFor(ShaderKind.INTERSECTION_SHADER)
        callableIntrospection = compiled.introspectionFor(ShaderKind.CALLABLE_SHADER)
        rayTracingGroups = null
    }
    attachFieldProbe(box, p)
    box._rayTracingShader!!.reload(p)
    clearShaderErrorsForBaseProperties(box, "raygen", "miss", "closesthit", "anyhit", "intersection", "callable")
    return true
}

private fun List<Pair<PreparedShaderStage, CompileResult>>.introspectionFor(kind: ShaderKind): Introspection? =
    firstOrNull { it.first.kind == kind }?.second?.introspection

fun Box.attachShader(module: RayTracingModulePointer, overwriteProperties: Boolean = false): RayTracingModulePointer {
    installRayTracingShaderReloadAction()

    val parameters = module.current.parameters
    require(parameters.raygen.isNotBlank()) { "Attached ray tracing module must have a raygen shader source." }

    if (overwriteProperties || !hasShaderSource(this, "raygen")) setPropertyRaw("raygen", parameters.raygen)
    if (overwriteProperties || !hasShaderSource(this, "miss")) parameters.miss.takeIf { it.isNotBlank() }?.let { setPropertyRaw("miss", it) }
    if (overwriteProperties || !hasShaderSource(this, "closesthit")) parameters.closesthit.takeIf { it.isNotBlank() }?.let { setPropertyRaw("closesthit", it) }
    if (overwriteProperties || !hasShaderSource(this, "anyhit")) parameters.anyhit.takeIf { it.isNotBlank() }?.let { setPropertyRaw("anyhit", it) }
    if (overwriteProperties || !hasShaderSource(this, "intersection")) parameters.intersection.takeIf { it.isNotBlank() }?.let { setPropertyRaw("intersection", it) }
    if (overwriteProperties || !hasShaderSource(this, "callable")) parameters.callable.takeIf { it.isNotBlank() }?.let { setPropertyRaw("callable", it) }

    _raygen = parameters.raygen
    _miss = parameters.miss.takeIf { it.isNotBlank() }
    _closesthit = parameters.closesthit.takeIf { it.isNotBlank() }
    _anyhit = parameters.anyhit.takeIf { it.isNotBlank() }
    _intersection = parameters.intersection.takeIf { it.isNotBlank() }
    _callable = parameters.callable.takeIf { it.isNotBlank() }
    _rayTracingShader = module

    reloadRayTracingShader(this)
    return module
}

// ------------------------------
// Graphics macro
// ------------------------------
@OptIn(DelicateCoroutinesApi::class)
fun Box.macro_moduleHere(args: List<String>): Insert? {
    return macro_moduleHereWithDefaultShaders(args)
}


val defaultVertexShader = """
    #version 450
    layout (location = 0) in vec3 pos;

    layout(push_constant) uniform matrices {
        mat4 p;
        mat4 m;
    } pc;

    void main()
    {
        gl_Position = pc.p * pc.m * vec4(pos, 1);
    }
""".trimIndent()

val defaultFragmentShader = """
    #version 450
    layout(location = 0) out vec4 uFragColor;
    void main()
    {
        uFragColor = vec4(1.0, 0.8, 0.4, 1.0);
    }
""".trimIndent()

private const val SHADERTOY_VERTEX_TEMPLATE_KEY = "shadertoy.vertexPreamble"
private const val SHADERTOY_FRAGMENT_TEMPLATE_KEY = "shadertoy.fragmentPreamble"

private val shadertoyVertexPreamble = """
    #version 450
""".trimIndent()

private val shadertoyFragmentPreamble = """
    #version 450

    layout(location = 0) out vec4 uFragColor;

    layout(set = 0, binding = 0) uniform ShadertoyUniforms {
        vec3 iResolution;
        float iTime;
        float iTimeDelta;
        float iFrameRate;
        int iFrame;
        vec4 iMouse;
        vec4 iDate;
        vec3 iChannelResolution0;
        float iChannelTime0;
        vec3 iChannelResolution1;
        float iChannelTime1;
        vec3 iChannelResolution2;
        float iChannelTime2;
        vec3 iChannelResolution3;
        float iChannelTime3;
    } st;

    layout(set = 0, binding = 1) uniform sampler2D iChannel0;
    layout(set = 0, binding = 2) uniform sampler2D iChannel1;
    layout(set = 0, binding = 3) uniform sampler2D iChannel2;
    layout(set = 0, binding = 4) uniform sampler2D iChannel3;

    #define iResolution st.iResolution
    #define iTime st.iTime
    #define iTimeDelta st.iTimeDelta
    #define iFrameRate st.iFrameRate
    #define iFrame st.iFrame
    #define iMouse st.iMouse
    #define iDate st.iDate
    #define iChannelTime float[4](st.iChannelTime0, st.iChannelTime1, st.iChannelTime2, st.iChannelTime3)
    #define iChannelResolution vec3[4](st.iChannelResolution0, st.iChannelResolution1, st.iChannelResolution2, st.iChannelResolution3)
""".trimIndent()

private fun Box.ensureShadertoyTemplates() {
    shaderTemplate.putIfAbsent(SHADERTOY_VERTEX_TEMPLATE_KEY, shadertoyVertexPreamble)
    shaderTemplate.putIfAbsent(SHADERTOY_FRAGMENT_TEMPLATE_KEY, shadertoyFragmentPreamble)
}

val shadertoyVertexShader = """
    {{ shadertoy.vertexPreamble }}

    void main() {
        vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
        gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
    }
""".trimIndent()

val shadertoyFragmentShader = """
    {{ shadertoy.fragmentPreamble }}

    void mainImage(out vec4 fragColor, in vec2 fragCoord);

    void main() {
        vec4 color = vec4(0.0, 0.0, 0.0, 1.0);
        mainImage(color, gl_FragCoord.xy);
        uFragColor = color;
    }

    void mainImage(out vec4 fragColor, in vec2 fragCoord) {
        vec2 uv = fragCoord / max(iResolution.xy, vec2(1.0));
        fragColor = vec4(uv, 0.5 + 0.5 * sin(iTime), 1.0);
    }
""".trimIndent()

private data class ShadertoyTimeState(
    val startedAtNs: Long = System.nanoTime(),
    var lastFrameSampleNs: Long = startedAtNs,
    var lastDeltaSeconds: Float = 1f / 60f,
    var clickOrigin: Vector2Like? = null
)

private data class Vector2Like(val x: Float, val y: Float)

private object ShadertoyTextures {
    private val cache = java.util.WeakHashMap<Renderer, Map<String, LoadedTexture>>()
    private val colors = listOf(
        "iChannel0" to byteArrayOf(255.toByte(), 255.toByte(), 255.toByte(), 255.toByte()),
        "iChannel1" to byteArrayOf(255.toByte(), 0, 0, 255.toByte()),
        "iChannel2" to byteArrayOf(0, 255.toByte(), 0, 255.toByte()),
        "iChannel3" to byteArrayOf(0, 0, 255.toByte(), 255.toByte())
    )

    fun forRenderer(renderer: Renderer): Map<String, LoadedTexture> = cache.getOrPut(renderer) {
        colors.associate { (name, rgba) ->
            val data = MemoryUtil.memAlloc(4)
            data.put(rgba)
            data.flip()
            name to LoadedTexture.fromRawRGBA(name, 1, 1, data, renderer, VK_FORMAT_R8G8B8A8_SRGB)
        }
    }
}

private fun bindShadertoyUniforms(module: ModulePointer, renderer: Renderer) {
    val uniforms = module.current.liveUniformStructures["ShadertoyUniforms"] ?: return
    val timeState = ShadertoyTimeState()
    val window = renderer.window

    fun cursorNow(): Vector2Like {
        val c = window.cursor
        return Vector2Like(c.x, c.y)
    }

    uniforms.bind("iResolution") {
        Vector3f(window.width.toFloat(), window.height.toFloat(), 1f)
    }
    uniforms.bind("iTime") {
        ((System.nanoTime() - timeState.startedAtNs) / 1_000_000_000.0).toFloat()
    }
    uniforms.bind("iTimeDelta") {
        val now = System.nanoTime()
        val delta = ((now - timeState.lastFrameSampleNs) / 1_000_000_000.0).toFloat().coerceAtLeast(0f)
        timeState.lastFrameSampleNs = now
        timeState.lastDeltaSeconds = if (delta > 0f) delta else timeState.lastDeltaSeconds
        timeState.lastDeltaSeconds
    }
    uniforms.bind("iFrameRate") {
        (1f / timeState.lastDeltaSeconds.coerceAtLeast(1f / 1000f))
    }
    uniforms.bind("iFrame") {
        ((System.nanoTime() - timeState.startedAtNs) / 16_666_667L).toInt()
    }
    uniforms.bind("iMouse") {
        val leftDown = window.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT)
        val cursor = cursorNow()
        if (leftDown && timeState.clickOrigin == null) {
            timeState.clickOrigin = cursor
        } else if (!leftDown) {
            timeState.clickOrigin = null
        }
        val origin = timeState.clickOrigin ?: cursor
        val shaderY = window.height.toFloat() - cursor.y
        val originShaderY = window.height.toFloat() - origin.y
        Vector4f(
            cursor.x,
            shaderY,
            if (leftDown) origin.x else -origin.x,
            if (leftDown) originShaderY else -originShaderY
        )
    }
    uniforms.bind("iDate") {
        val now = LocalDateTime.now()
        val seconds =
            now.hour * 3600f + now.minute * 60f + now.second.toFloat() + now.nano / 1_000_000_000f
        Vector4f(now.year.toFloat(), now.monthValue.toFloat(), now.dayOfMonth.toFloat(), seconds)
    }
    for (index in 0..3) {
        uniforms.bind("iChannelResolution$index") { Vector3f(1f, 1f, 1f) }
        uniforms.bind("iChannelTime$index") {
            ((System.nanoTime() - timeState.startedAtNs) / 1_000_000_000.0).toFloat()
        }
    }
}

private fun installShadertoyMesh(module: ModulePointer, renderer: Renderer, name: String = "shadertoy"): Mesh {
    val existing = module.current.meshes[name]
    if (existing != null) return existing

    val mesh = Mesh(renderer, module)
    mesh.resize(3, 0, module)
    module.current.meshes[name] = mesh
    return mesh
}

fun Box.macro_moduleHereWithDefaultShaders(
    args: List<String>,
    vertex: String = defaultVertexShader,
    fragment: String = defaultFragmentShader,
    resolvesToCall: String = "_moduleHere"
): Insert? {
    installGraphicsShaderReloadAction()
    installGraphicsShaderUpdateAction()

    if (!hasShaderSource(this, "fragment") || !hasShaderSource(this, "vertex")) {
        this.setPropertyRaw(
            "fragment",
            shaderPropertyValue(this, "fragment")?.takeIf { it.isNotBlank() } ?: fragment
        )
        this.setPropertyRaw(
            "vertex",
            shaderPropertyValue(this, "vertex")?.takeIf { it.isNotBlank() } ?: vertex
        )
    }

    // Prepare code & introspect
    clearShaderErrorsForBaseProperties(this, "vertex", "fragment")
    val vertexStage = prepareShaderStage(this, ShaderKind.VERTEX_SHADER, "vertex")
    val fragmentStage = vertexStage?.let {
        prepareShaderStage(
            this,
            ShaderKind.FRAGMENT_SHADER,
            "fragment",
            priorStages = mapOf(ShaderKind.VERTEX_SHADER to it)
        )
    }
    val vi = vertexStage?.let {
        assignPreparedShaderStage(this, it)
        compileStage(this, it).introspection
    }
    val fi = fragmentStage?.let {
        assignPreparedShaderStage(this, it)
        compileStage(this, it).introspection
    }

    if (vi == null || fi == null) {
        var here = args[0]
        if (args.size == 2) here += args[1]
        return Insert(here = "$resolvesToCall$here")
    } else {
        clearShaderErrorsForBaseProperties(this, "vertex", "fragment")
        return insertForShader(vi, fi, resolvesToCall, args, this)
    }
}

fun Box.macro_shadertoyHere(args: List<String>): Insert? {
    ensureShadertoyTemplates()
    return macro_moduleHereWithDefaultShaders(
        args,
        vertex = shadertoyVertexShader,
        fragment = shadertoyFragmentShader,
        resolvesToCall = "_shadertoyHere"
    )
}


// ------------------------------
// Graphics helpers for Insert
// ------------------------------
private fun buildStackAutoShaderPreamble(box: Box?): String {
    val names = box?.stackAutoShaderSources
        ?.keys
        ?.filter { stackAutoShaderNameRegex.matches(it) }
        ?.sorted()
        ?: return ""

    /*
     * How to drive stack.auto templates:
     *
     *   1. Put a StackSL auto slot in GLSL with `{{ stack.auto.foo }}`,
     *      `{{ stack.auto.foo:word }}`, `{{ stack.auto.foo@samplerName }}`, or
     *      `{{ stack.auto.foo:word@samplerName }}`. Template expansion emits a GLSL function
     *      named `foo` exactly once per expanded shader stage, so later GLSL can
     *      call `foo(vec4(...))`. The optional `:word` forces a StackSL definition
     *      in `module.foo` to be used as the generated function body. The optional
     *      `@samplerName` makes sample words read that GLSL sampler instead of `source`.
     *   2. If no Kotlin code has bound `foo`, the emitted function is the stable
     *      fallback `vec4 foo(vec4 foo_in) { return vec4(0.0); }`. The argument
     *      name deliberately avoids `input`, which is reserved in GLSL 450.
     *   3. During shader macro/reload preamble generation, every discovered
     *      `stack.auto.*` name becomes a Kotlin extension property on shader module pointers:
     *
     *          module.foo = "p length ]2s2.0"
     *
     *      The setter first compiles the StackSL string. Failed compiles leave the
     *      previous binding and current shader pipeline untouched.
     *   4. Successful setter calls queue a shader-family-specific update. Graphics
     *      and compute use the ABI-preserving background path; mesh currently uses
     *      the mesh reload path. Rapid writes are coalesced into one pending follow-up.
     */
    return names.joinToString(separator = "\n") { name ->
        prefixedBlock(
            "stack-auto:$name",
            """
                var ModulePointer.${kotlinMemberName(name)}: String
                    get() = afield.stdlib.stackAutoShaderSource(this, "$name")
                    set(value) { afield.stdlib.stackAutoShaderSource(this, "$name", value) }

                var ComputeModulePointer.${kotlinMemberName(name)}: String
                    get() = afield.stdlib.stackAutoShaderSource(this, "$name")
                    set(value) { afield.stdlib.stackAutoShaderSource(this, "$name", value) }

                var MeshModulePointer.${kotlinMemberName(name)}: String
                    get() = afield.stdlib.stackAutoShaderSource(this, "$name")
                    set(value) { afield.stdlib.stackAutoShaderSource(this, "$name", value) }
            """.trimIndent()
        )
    }
}

fun insertForShader(
    vi: Introspection,
    fi: Introspection,
    resolvesToCall: String = "_moduleHere",
    args: List<String>,
    box: Box? = null
): Insert {
    var postImport = ""
    vi.inputs.forEach {
        postImport += prefixed(
            "graphics-input:${it.name}",
            "val Mesh.${it.name} by FromInput()"
        ) + "\n"
    }

    postImport += writeSuperMap(vi.inputs) + "\n"
    postImport += buildShaderStructurePreamble("graphics", listOf(vi, fi))
    buildStackAutoShaderPreamble(box).takeIf { it.isNotBlank() }?.let {
        postImport += it + "\n"
    }

    (vi.pushConstants + fi.pushConstants).flatMap { it.members }.distinctBy { it.name }.forEach { member ->
            postImport += prefixed(
                "graphics-pc-structure:${member.name}",
                "var Structure.${member.name} by Structure.FromStructure<Any>()"
            ) + "\n"
            val ty = kotlinBindingTypeName(member)
            postImport += prefixed(
                "graphics-pc-binding:${member.name}",
                "var Mesh.${member.name} by Mesh.BoundFromStructure<$ty>()"
            ) + "\n"

            postImport += prefixed(
                "graphics-pc-binding-group:${member.name}",
                "var MeshGroup.${member.name} by MeshGroup.BoundFromStructure<$ty>()"
            ) + "\n"
    }

    (fi.ubos + vi.ubos).distinctBy { it.name }.forEach {
        postImport += prefixed(
            "graphics-ubo:${it.name}",
            "val ModulePointer.${it.name} by FromUniform()"
        ) + "\n"
        it.members.forEach { m ->
            val ty = kotlinBindingTypeName(m)
            postImport += prefixed(
                "graphics-ubo-member:${it.name}:${m.name}",
                "var Structure.${m.name} by Structure.FromStructure<$ty>()"
            ) + "\n"
        }
    }

    (fi.ssbo + vi.ssbo).distinctBy { it.name }.forEach {
        postImport += prefixed(
            "graphics-ssbo:${it.name}",
            "val ModulePointer.${it.name} by FromUniform()"
        ) + "\n"
    }

    var here = "$resolvesToCall${args[0]}"
    if (args.size == 2) here += args[1]
    if (args.size == 3) here += args[2]

    return Insert(
        preamble = """
            import afield.four.buffers.*
            import afield.four.modules.*
            import arrow.core.left
            import arrow.core.right
            import java.nio.*
        """.trimIndent(),
        preambleAfterImports = postImport,
        here = here
    )
}

// ------------------------------
// Expanded graphics module creation
// ------------------------------
fun Box._moduleHere(p: Parameters.() -> Unit): ModulePointer {
    return _moduleHere(parameters(renderer = fromContext(), p))
}

fun Box._moduleHere(p: Parameters = parameters {}): ModulePointer {
    val vertexSourceProperty = sourcePropertyForStage(this, "vertex")
    val fragmentSourceProperty = sourcePropertyForStage(this, "fragment")
    if (!shaderPropertyValue(this, sharedVertexFragmentProperty).isNullOrBlank()) {
        clearShaderErrorsForBaseProperties(this, "vertex", "fragment")
        val vertexStage = prepareShaderStage(this, ShaderKind.VERTEX_SHADER, "vertex")
            ?: throw IllegalStateException("Unable to prepare shared vertex shader")
        val fragmentStage = prepareShaderStage(
            this,
            ShaderKind.FRAGMENT_SHADER,
            "fragment",
            priorStages = mapOf(ShaderKind.VERTEX_SHADER to vertexStage)
        )
            ?: throw IllegalStateException("Unable to prepare shared fragment shader")

        assignPreparedShaderStage(this, vertexStage)
        assignPreparedShaderStage(this, fragmentStage)

        val vertexCompile = compileStage(this, vertexStage)
        val fragmentCompile = compileStage(this, fragmentStage)
        val vi = vertexCompile.introspection
            ?: throw IllegalStateException("Unable to compile shared vertex shader")
        val fi = fragmentCompile.introspection
            ?: throw IllegalStateException("Unable to compile shared fragment shader")

        p.vertex = this._vertex!!
        p.fragment = this._fragment!!
        p.vertexIntrospection = vi
        p.fragmentIntrospection = fi
    } else {
        p.vertex = this._vertex!!
        p.fragment = this._fragment!!
    }

    p.vertexSourceName = boxShaderSourceName(this, vertexSourceProperty)
    p.fragmentSourceName = boxShaderSourceName(this, fragmentSourceProperty)
    p.vertexIncludeResolver = shaderIncludeResolverFor(this, vertexSourceProperty)
    p.fragmentIncludeResolver = shaderIncludeResolverFor(this, fragmentSourceProperty)

    if (p.name == "unnamed module") p.name = "module in box '${name}' (${id})"

    val previousResolver = p.textureResolver
    p.textureResolver = textureResolverFor(previousResolver)

    globalAsyncDebugHandler = { message ->
        this.error(message, "code", numberFromErrorMessage(message) ?: 0)
    }

    p.errorHandler = { message, throwable ->
        this.error(message, "code", numberFromErrorMessage(message) ?: 0)
        throwable?.let { lprintln("error,graphics", it.stackTraceToString()) }
    }

    attachFieldProbe(this, p)
    this._shader = Module(p).pointer()
    registerStackAutoShaderOwner(this, this._shader!!)
    return this._shader!!
}

fun Box._shadertoyHere(p: Parameters.() -> Unit): ModulePointer {
    return _shadertoyHere(parameters(renderer = fromContext(), p))
}

fun Box._shadertoyHere(p: Parameters = parameters {}): ModulePointer {
    val renderer: Renderer = p.renderer
    val dummyTextures = ShadertoyTextures.forRenderer(renderer)
    val previousResolver = p.textureResolver

    p.depthTest = false
    p.textureResolver = { textureSpec ->
        dummyTextures[textureSpec.name]?.let { tex -> fromViews(listOf(tex), textureSpec.name) } ?: previousResolver(
            textureSpec
        )
    }

    val module = _moduleHere(p)
    bindShadertoyUniforms(module, renderer)
    installShadertoyMesh(module, renderer)
    return module
}

var Box.customTextureResolver by Inheritance<((Box, String) -> HasImageViewArray?)?>()

fun fromViews(
    views: List<HasImageView>,
    name: String = "",
    sampler: TextureSampler? = null
): Pair<HasImageViewArray, TextureSampler> {
    require(views.isNotEmpty()) { "No image views found for texture $name" }
    val provider = object : HasImageViewArray {
        override val imageViewArray = views
    }
    val defaultSampler = views.asSequence()
        .mapNotNull { (it as? HasDefaultTextureSampler)?.defaultSampler }
        .firstOrNull()
    val sampler0 = sampler ?: defaultSampler ?: TextureSampler(
        renderer = fromContext(),
        views.first().imageView.imageViewData.mipLevels,
        true
    )
    return provider to sampler0
}

fun fromViews(
    provider: HasImageViewArray,
    name: String = "",
    sampler: TextureSampler? = null
): Pair<HasImageViewArray, TextureSampler> {
    val providerSampler = (provider as? HasDefaultTextureSampler)?.defaultSampler
    return fromViews(provider.imageViewArray, name, sampler ?: providerSampler)
}

private fun Box.textureResolverFor(previousResolver: (Introspected.Texture) -> Pair<HasImageViewArray, TextureSampler>?): (Introspected.Texture) -> Pair<HasImageViewArray, TextureSampler> =
    { textureSpec ->

        Box.context.set(this) // todo, should be a 'with'

        val cached: Pair<HasImageViewArray, TextureSampler>? = previousResolver.invoke(textureSpec)
        if (cached != null) cached else {
            lprintln("debug,graphics", "searching for texture ${textureSpec.name}")
            val name = textureSpec.name

            val c = customTextureResolver
            var found : Pair<HasImageViewArray, TextureSampler>? = null

            if (c != null) {
                val r = c(this, name)
                if (r != null) {
                    found = fromViews(r, name)
                }
            }

            val r = found ?:
                findWithNameOrNull<HasImageViewArray>(this, name)?.let { provider -> fromViews(provider, name) }
                    ?: findWithNameOrNull<HasImageView>(this, name)?.let { hv -> fromViews(listOf(hv), name) }
                    ?: findWithNameOrNull<ImageView>(this, name)?.let { view -> fromViews(listOf(view), name) }
                    ?: findWithNameOrNull<Array<HasImageView>>(this, name)?.let { arr -> fromViews(arr.toList(), name) }
                    ?: findWithNameOrNull<Array<ImageView>>(
                        this,
                        name
                    )?.let { arr -> fromViews(arr.map { it as HasImageView }, name) }
                    ?: findWithNameOrNull<LoadedTexture>(this, name)?.let { tex -> fromViews(listOf(tex), name) }
                    ?: findWithNameOrNull<RenderToTexture>(this, name)?.let { rtt -> fromViews(listOf(rtt), name) }
                    ?: throw Exception(" can't find anything to build a texture out of from this box to ${textureSpec.name}")
            lprintln("debug,graphics", "found $r")
            r
        }
    }

// ------------------------------
// Compute macro + module creation
// ------------------------------
@OptIn(DelicateCoroutinesApi::class)
fun Box.macro_computeModuleHere(args: List<String>): Insert {
    installComputeShaderReloadAction()
    installComputeShaderUpdateAction()

    if (!hasShaderSource(this, "compute")) {
        this.setPropertyRaw(
            "compute", """
#version 450

layout(local_size_x = 64) in;

layout(binding = 0) buffer InputBuffer {
    uint inData[];
};

layout(binding = 1) buffer OutputBuffer {
    uint outData[];
};

void main() {
    uint idx = gl_GlobalInvocationID.x;
    outData[idx] = inData[idx] + 1;
}
        """.trimIndent()
        )
    }

    clearShaderErrorsForBaseProperties(this, "compute")
    val computeStage = prepareShaderStage(this, ShaderKind.COMPUTE_SHADER, "compute")
    val ci = computeStage?.let {
        assignPreparedShaderStage(this, it)
        compileStage(this, it).introspection
    }

    if (ci == null) {
        return Insert(here = "_computeModuleHere${args[0]}")
    } else {
        clearShaderErrorsForBaseProperties(this, "compute")
        val insert = buildComputeInsert(ci, this)
        return insert.copy(here = run {
            var here = "_computeModuleHere${args[0]}"
            if (args.size == 2) here += args[1]
            here
        })
    }
}

fun Box._computeModuleHere(p: Parameters.() -> Unit): ComputeModulePointer {
    return _computeModuleHere(parameters(renderer = fromContext(), p))
}

fun Box._computeModuleHere(p: Parameters): ComputeModulePointer {
    p.compute = this._compute!!
    val computeSourceProperty = sourcePropertyForStage(this, "compute")
    p.computeSourceName = boxShaderSourceName(this, computeSourceProperty)
    p.computeIncludeResolver = shaderIncludeResolverFor(this, computeSourceProperty)

    p.textureResolver = textureResolverFor(p.textureResolver)

    p.errorHandler = { message, throwable ->
        this.error(message, "code", numberFromErrorMessage(message) ?: 0)
        throwable?.let { lprintln("error,graphics", it.stackTraceToString()) }
    }

    if (p.name == "unnamed module") p.name = "module in box '${name}' (${id})"

    attachFieldProbe(this, p)
    val pp = ComputeModule(p).pointer()
    this._computeShader = pp
    registerStackAutoShaderOwner(this, pp)
    return pp
}

// ------------------------------
// Mesh macro + module creation
// ------------------------------
@OptIn(DelicateCoroutinesApi::class)
fun Box.macro_meshModuleHere(args: List<String>): Insert {
    installMeshShaderReloadAction()

    if (!hasShaderSource(this, "task")) {
        this.setPropertyRaw(
            "task", """
#version 450
#extension GL_EXT_mesh_shader : enable

layout(local_size_x = 1, local_size_y = 1, local_size_z = 1) in;

struct PerTask { uvec3 taskGlobalID; };
taskPayloadSharedEXT PerTask perTask;

void main ()
{
    uvec3 globalID = gl_WorkGroupID * gl_WorkGroupSize + gl_LocalInvocationID;
    perTask.taskGlobalID = globalID;

    EmitMeshTasksEXT(3,1,1);
}        """.trimIndent()
        )
    }

    if (!hasShaderSource(this, "mesh")) {
        this.setPropertyRaw(
            "mesh", """
#version 450
#extension GL_EXT_mesh_shader : enable

layout(local_size_x = 1, local_size_y = 1, local_size_z = 1) in;
layout (triangles) out; 
layout (max_vertices=3, max_primitives=1) out;

struct PerTask { uvec3 taskGlobalID; };
taskPayloadSharedEXT PerTask perTask;

void main ()
{
    SetMeshOutputsEXT(3, 1);
    uvec3 globalID = gl_WorkGroupID * gl_WorkGroupSize + gl_LocalInvocationID;
    uvec3 parentTaskGlobalID = perTask.taskGlobalID;

    float y = globalID.x/9.0;
    float x = parentTaskGlobalID.x/9.0;
    gl_MeshVerticesEXT[0].gl_Position = vec4(-x,y,0,1);
    gl_MeshVerticesEXT[1].gl_Position = vec4(-x+0.25,y,0,1);
    gl_MeshVerticesEXT[2].gl_Position = vec4(-x+0,0.5+y,0,1);
    gl_PrimitiveTriangleIndicesEXT[0] = uvec3(0,1,2);
}
        """.trimIndent()
        )
    }

    if (!hasShaderSource(this, "fragment")) {
        this.setPropertyRaw(
            "fragment", """
 #version 450
layout(location = 0) out vec4 uFragColor;
void main()
{
    uFragColor = vec4(1.0, 0.8, 0.4, 1.0);
}
        """.trimIndent()
        )
    }

    clearShaderErrorsForBaseProperties(this, "task", "mesh", "fragment")
    val taskStage = prepareShaderStage(this, ShaderKind.TASK_SHADER, "task")
    val meshStage = taskStage?.let {
        prepareShaderStage(
            this,
            ShaderKind.MESH_SHADER,
            "mesh",
            priorStages = mapOf(ShaderKind.TASK_SHADER to it)
        )
    }
    val fragmentStage = if (taskStage != null && meshStage != null) {
        prepareShaderStage(
            this,
            ShaderKind.FRAGMENT_SHADER,
            "fragment",
            priorStages = mapOf(
                ShaderKind.TASK_SHADER to taskStage,
                ShaderKind.MESH_SHADER to meshStage
            )
        )
    } else {
        null
    }

    val ti = taskStage?.let {
        assignPreparedShaderStage(this, it)
        compileStage(this, it).introspection
    }
    val mi = meshStage?.let {
        assignPreparedShaderStage(this, it)
        compileStage(this, it).introspection
    }
    val fi = fragmentStage?.let {
        assignPreparedShaderStage(this, it)
        compileStage(this, it).introspection
    }

    if (ti == null || mi == null || fi == null) {
        return Insert(here = "_meshModuleHere${args[0]}")
    } else {
        clearShaderErrorsForBaseProperties(this, "task", "mesh", "fragment")
        val insert = buildMeshInsert(ti, mi, fi, this)
        return insert.copy(here = run {
            var here = "_meshModuleHere${args[0]}"
            if (args.size == 2) here += args[1]
            here
        })
    }
}

fun Box._meshModuleHere(p: Parameters.() -> Unit): MeshModulePointer {
    return _meshModuleHere(parameters(renderer = fromContext(), p))
}

fun Box._meshModuleHere(p: Parameters = parameters {}): MeshModulePointer {

    p.mesh = this._mesh!!
    p.task = this._task!!
    p.fragment = this._fragment!!
    val taskSourceProperty = sourcePropertyForStage(this, "task")
    val meshSourceProperty = sourcePropertyForStage(this, "mesh")
    val fragmentSourceProperty = sourcePropertyForStage(this, "fragment")
    p.taskSourceName = boxShaderSourceName(this, taskSourceProperty)
    p.meshSourceName = boxShaderSourceName(this, meshSourceProperty)
    p.fragmentSourceName = boxShaderSourceName(this, fragmentSourceProperty)
    p.taskIncludeResolver = shaderIncludeResolverFor(this, taskSourceProperty)
    p.meshIncludeResolver = shaderIncludeResolverFor(this, meshSourceProperty)
    p.fragmentIncludeResolver = shaderIncludeResolverFor(this, fragmentSourceProperty)

    p.errorHandler = { message, throwable ->
        this.error(message, "code", numberFromErrorMessage(message) ?: 0)
        throwable?.let { lprintln("error,graphics", it.stackTraceToString()) }
    }

    if (p.name == "unnamed module") p.name = "mesh module in box '${name}' (${id})"

    attachFieldProbe(this, p)
    val pp = MeshModule(p).pointer()
    this._meshShader = pp
    registerStackAutoShaderOwner(this, pp)
    return pp
}

// ------------------------------
// Ray tracing macro + module creation
// ------------------------------
val defaultRaygenShader = """
#version 460
#extension GL_EXT_ray_tracing : require

layout(location = 0) rayPayloadEXT vec4 payload;

void main()
{
    vec2 uv = (vec2(gl_LaunchIDEXT.xy) + vec2(0.5)) / vec2(max(gl_LaunchSizeEXT.xy, uvec2(1)));
    payload = vec4(uv, 0.0, 1.0);
}
""".trimIndent()

@OptIn(DelicateCoroutinesApi::class)
fun Box.macro_rayTracingModuleHere(args: List<String>): Insert {
    installRayTracingShaderReloadAction()

    if (!hasShaderSource(this, "raygen")) {
        setPropertyRaw("raygen", defaultRaygenShader)
    }

    clearShaderErrorsForBaseProperties(this, "raygen", "miss", "closesthit", "anyhit", "intersection", "callable")
    val stages = prepareRayTracingStages(this)
    val introspections = stages?.mapNotNull {
        assignPreparedShaderStage(this, it)
        compileStage(this, it).introspection
    }

    val here = run {
        var h = "_rayTracingModuleHere${args[0]}"
        if (args.size == 2) h += args[1]
        h
    }

    if (stages == null || introspections == null || introspections.size != stages.size) {
        return Insert(here = here)
    }

    clearShaderErrorsForBaseProperties(this, "raygen", "miss", "closesthit", "anyhit", "intersection", "callable")
    return buildRayTracingInsert(introspections).copy(here = here)
}

fun Box._rayTracingModuleHere(p: Parameters.() -> Unit): RayTracingModulePointer {
    return _rayTracingModuleHere(parameters(renderer = fromContext(), p))
}

fun Box._rayTracingModuleHere(p: Parameters = parameters {}): RayTracingModulePointer {
    p.raygen = this._raygen!!
    p.miss = this._miss ?: ""
    p.closesthit = this._closesthit ?: ""
    p.anyhit = this._anyhit ?: ""
    p.intersection = this._intersection ?: ""
    p.callable = this._callable ?: ""

    val raygenSourceProperty = sourcePropertyForStage(this, "raygen")
    p.raygenSourceName = boxShaderSourceName(this, raygenSourceProperty)
    p.raygenIncludeResolver = shaderIncludeResolverFor(this, raygenSourceProperty)

    fun configureOptional(base: String, assign: (String, ShaderIncludeResolver?) -> Unit) {
        if (!hasShaderSource(this, base)) return
        val sourceProperty = sourcePropertyForStage(this, base)
        assign(boxShaderSourceName(this, sourceProperty), shaderIncludeResolverFor(this, sourceProperty))
    }

    configureOptional("miss") { sourceName, resolver ->
        p.missSourceName = sourceName
        p.missIncludeResolver = resolver
    }
    configureOptional("closesthit") { sourceName, resolver ->
        p.closesthitSourceName = sourceName
        p.closesthitIncludeResolver = resolver
    }
    configureOptional("anyhit") { sourceName, resolver ->
        p.anyhitSourceName = sourceName
        p.anyhitIncludeResolver = resolver
    }
    configureOptional("intersection") { sourceName, resolver ->
        p.intersectionSourceName = sourceName
        p.intersectionIncludeResolver = resolver
    }
    configureOptional("callable") { sourceName, resolver ->
        p.callableSourceName = sourceName
        p.callableIncludeResolver = resolver
    }

    p.textureResolver = textureResolverFor(p.textureResolver)

    p.errorHandler = { message, throwable ->
        this.error(message, "code", numberFromErrorMessage(message) ?: 0)
        throwable?.let { lprintln("error,graphics", it.stackTraceToString()) }
    }

    if (p.name == "unnamed module") p.name = "ray tracing module in box '${name}' (${id})"

    attachFieldProbe(this, p)
    val pp = RayTracingModule(p).pointer()
    this._rayTracingShader = pp
    return pp
}
