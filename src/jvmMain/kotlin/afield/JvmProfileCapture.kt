package afield

import afield.ai.KotlinSourceIndex
import jdk.jfr.Recording
import jdk.jfr.RecordingState
import jdk.jfr.consumer.RecordingFile
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors

/** Optional, bounded JFR sampling. Parsing and source lookup never run on the game thread. */
internal class JvmProfileCapture {
    private val recording = Recording().apply {
        name = "Field profiling timeline"
        maxSize = 64L * 1024 * 1024
        duration = Duration.ofSeconds(35) // safety ceiling even if the Runloop never returns
        enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10)).withStackTrace()
        enable("jdk.ObjectAllocationSample").with("throttle", "150/s").withStackTrace()
        enable("jdk.GCPhasePause").withThreshold(Duration.ZERO)
        start()
    }
    private var start = Instant.now()
    private var startNs = 0L
    private var finishing = false
    private val scripts = linkedMapOf<String, Script>()
    @Volatile private var state = "recording"
    @Volatile private var error = ""
    @Volatile private var result: Result? = null

    private data class Script(val name: String, val source: ProfileStackSource)
    internal data class Result(val frames: List<ProfileStackFrame>, val sources: List<ProfileStackSource>,
        val samples: List<ProfileStackSample>, val dropped: Int, val pauses: List<ProfileGcPause>)

    fun align(nanos: Long) {
        val clock = Instant.now()
        val mono = System.nanoTime()
        start = clock.minusNanos(mono - nanos)
        startNs = nanos
    }

    // Copy strings/identifiers only: no live box, classloader or coroutine retained by the latch.
    @Synchronized fun register(origin: ProfileOrigin) {
        if (finishing || scripts.size >= 2048) return
        for (snapshot in origin.box.liveKi?.context?.debugScripts?.all().orEmpty()) {
            val name = snapshot.className ?: continue
            if (scripts.containsKey(name) || snapshot.sourceText.length > 262144) continue
            val original = origin.source.takeIf { it.length <= 262144 }.orEmpty()
            if (scripts.values.sumOf { it.source.code.length + it.source.boxCode.length } + snapshot.sourceText.length + original.length > 2_000_000) break
            scripts[name] = Script(name, ProfileStackSource(-1, snapshot.sourceName, snapshot.sourceText,
                origin.box.id, fieldSourcePropertyFromFileName(snapshot.sourceName) ?: "code", original))
        }
    }

    @Synchronized fun finish(endNs: Long) {
        if (finishing) return
        finishing = true
        state = "processing"
        val frozenScripts = scripts.values.toList()
        scripts.clear()
        reader.execute {
            var file: Path? = null
            try {
                if (recording.state == RecordingState.RUNNING) recording.stop()
                file = Files.createTempFile("field-profile-", ".jfr")
                recording.dump(file)
                result = read(file, frozenScripts, (endNs-startNs)/1e6)
                state = "ready"
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                state = "failed"
            } finally {
                recording.close()
                file?.let { Files.deleteIfExists(it) }
            }
        }
    }

    fun decorate(snapshot: CoroutineProfileSnapshot, after: Int): CoroutineProfileSnapshot {
        val r = result
        val first = (after+1).coerceIn(0, r?.samples?.size ?: 0)
        return snapshot.copy(stackState = state, stackError = error,
            stackSampleCount = r?.samples?.size ?: 0, stackDropped = r?.dropped ?: 0,
            stackFrames = if (after < 0) r?.frames.orEmpty() else emptyList(),
            stackSources = if (after < 0) r?.sources.orEmpty() else emptyList(),
            stackSamples = r?.samples?.subList(first, minOf(first+1024, r.samples.size)).orEmpty(),
            gcPauses = r?.pauses.orEmpty())
    }

    private fun read(file: Path, scripts: List<Script>, endMs: Double): Result {
        val frames = arrayListOf<ProfileStackFrame>()
        val frameIds = hashMapOf<Triple<String,String,Int>, Int>()
        val sources = arrayListOf<ProfileStackSource>()
        val sourceIds = hashMapOf<String, Int>()
        val sourcePaths = hashMapOf<String, Int>()
        val samples = arrayListOf<ProfileStackSample>()
        val pauses = arrayListOf<ProfileGcPause>()
        val resolver = ProfileSourceResolver()
        var sourceBytes = 0
        var dropped = 0
        val allocationThreads = hashSetOf<Long>()
        fun sourceFor(name: String): Int {
            val script = scripts.filter { name == it.name || name.startsWith(it.name + "$") }.maxByOrNull { it.name.length }
            return sourceIds.getOrPut(script?.name ?: name.substringBefore('$')) {
            val source = script?.source ?: resolver.source(name) ?: return@getOrPut -1
            sourcePaths[source.path]?.let { return@getOrPut it }
            val size = source.code.length + source.boxCode.length
            if (sources.size >= 256 || sourceBytes + size > 4_000_000) return@getOrPut -1
            sourceBytes += size
            sources.size.also { sources += source.copy(id = it); sourcePaths[source.path] = it }
            }
        }
        RecordingFile(file).use { input ->
            while (input.hasMoreEvents()) {
                val event = input.readEvent()
                if (event.eventType.name == "jdk.GCPhasePause") {
                    val time = Duration.between(start, event.startTime).toNanos()/1e6
                    if (time in 0.0..endMs && pauses.size < 2048) pauses += ProfileGcPause(time, event.duration.toNanos()/1e6)
                    continue
                }
                val kind = when (event.eventType.name) {
                    "jdk.ExecutionSample" -> "cpu"
                    "jdk.ObjectAllocationSample" -> "allocation"
                    else -> continue
                }
                val time = Duration.between(start, event.startTime).toNanos()/1e6
                val thread = if (kind == "cpu") event.getThread("sampledThread") else event.thread
                // The first allocation weight on a thread can include bytes allocated before this
                // recording. Establish its baseline instead of charging those bytes to the brush.
                if (kind == "allocation" && allocationThreads.add(thread?.javaThreadId ?: -1)) {
                    if (time in 0.0..endMs) dropped++
                    continue
                }
                if (time < 0 || time > endMs) continue
                if (samples.size >= 12000) { dropped++; continue }
                val stack = event.stackTrace?.frames.orEmpty().take(64).mapNotNull { frame ->
                    val name = frame.method.type.name
                    val key = Triple(name, frame.method.name, frame.lineNumber)
                    frameIds[key] ?: if (frames.size >= 16000) null else frames.size.also { id ->
                        frameIds[key] = id
                        frames += ProfileStackFrame(id, name, frame.method.name, frame.lineNumber, sourceFor(name))
                    }
                }
                if (stack.isEmpty()) { dropped++; continue }
                samples += ProfileStackSample(time, thread?.javaThreadId ?: -1, thread?.javaName ?: "unknown",
                    kind, if (kind == "cpu") 1 else event.getLong("weight"), stack)
            }
        }
        samples.sortBy { it.timeMs }
        return Result(frames, sources, samples, dropped, pauses)
    }

    companion object {
        private val reader = Executors.newSingleThreadExecutor { Thread(it, "field-profile-reader").apply { isDaemon = true } }
    }
}

/** Reuse the source index; verify package and SourceFile rather than guessing by simple class name. */
internal class ProfileSourceResolver {
    private val index by lazy { KotlinSourceIndex.latestIndex ?: KotlinSourceIndex.loadIndex() }
    private val root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
    private val cache = hashMapOf<String, ProfileStackSource?>()

    fun source(className: String): ProfileStackSource? = cache.getOrPut(className.substringBefore('$')) {
        val top = className.substringBefore('$')
        val packageName = top.substringBeforeLast('.', "")
        val filename = runCatching {
            var name: String? = null
            javaClass.classLoader.getResourceAsStream(top.replace('.', '/') + ".class")?.use {
                ClassReader(it).accept(object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitSource(source: String?, debug: String?) { name = source }
                }, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES)
            }
            name
        }.getOrNull() ?: return@getOrPut null
        val candidates = index?.files.orEmpty().filter { it.path.substringAfterLast('/') == filename }
        val matches = candidates.mapNotNull { record ->
            val path = root.resolve(record.path).normalize()
            if (!path.startsWith(root.resolve("src")) || !Files.isRegularFile(path) || Files.size(path) > 262144) return@mapNotNull null
            val code = Files.readString(path)
            val declared = Regex("(?m)^\\s*package\\s+([\\w.]+)").find(code)?.groupValues?.get(1).orEmpty()
            if (declared != packageName) null else ProfileStackSource(-1, record.path, code)
        }
        matches.singleOrNull()
    }
}
