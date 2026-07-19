@file:Suppress("MemberVisibilityCanBePrivate")

package afield.debug

import com.sun.jdi.AbsentInformationException
import com.sun.jdi.ArrayReference
import com.sun.jdi.Bootstrap
import com.sun.jdi.IncompatibleThreadStateException
import com.sun.jdi.Location
import com.sun.jdi.ObjectReference
import com.sun.jdi.PrimitiveValue
import com.sun.jdi.ReferenceType
import com.sun.jdi.StringReference
import com.sun.jdi.ThreadReference
import com.sun.jdi.Value
import com.sun.jdi.VirtualMachine
import com.sun.jdi.event.BreakpointEvent
import com.sun.jdi.event.ClassPrepareEvent
import com.sun.jdi.event.EventSet
import com.sun.jdi.event.VMDeathEvent
import com.sun.jdi.event.VMDisconnectEvent
import com.sun.jdi.request.BreakpointRequest
import com.sun.jdi.request.ClassPrepareRequest
import com.sun.jdi.request.EventRequest
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

data class ActiveProbe(
    val request: ProbeVariableRequest,
    val eventRequests: MutableSet<EventRequest> = LinkedHashSet(),
    val createdAtMs: Long,
    var hits: Int = 0,
    var done: Boolean = false,
    var lastDetail: String? = null,
    var lastScope: ScopeSnapshotPayload? = null
)

/**
 * Installs JDI class-prepare and breakpoint requests for runtime value probes.
 *
 * Requests use [EventRequest.SUSPEND_EVENT_THREAD], never `SUSPEND_ALL`: the
 * thread that hits the probe is paused while [handleEventSet] reads its stack
 * value and is resumed in that function's `finally` block. Probes are therefore
 * deliberately brief and VM-local, but they are not literally non-suspending;
 * value rendering cost is paid by the event thread at each sampled hit.
 */
class JdiProbeService(
    private val vm: VirtualMachine,
    private val out: PrintWriter
) {
    private val traceEnabled = System.getenv("AFIELD_DEBUG_PROBE_TRACE") == "true"
    private val erm = vm.eventRequestManager()
    private val activeByEventRequest = ConcurrentHashMap<EventRequest, ActiveProbe>()
    private val activeByRequestId = ConcurrentHashMap<String, ActiveProbe>()
    private val running = AtomicBoolean(true)

    fun startEventLoop() {
        val thread = Thread {
            while (running.get()) {
                try {
                    val eventSet = vm.eventQueue().remove(250)
                    if (eventSet != null) handleEventSet(eventSet)
                    sweepTimeouts()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (t: Throwable) {
                    emit(ErrorResponse("_system", "event_loop_failure", t.message))
                }
            }
        }
        thread.name = "jdi-probe-event-loop"
        thread.isDaemon = true
        thread.start()
    }

    fun stop() {
        running.set(false)
        activeByRequestId.values.toList().forEach { completeAndDelete(it, "service_stopped") }
        runCatching { vm.dispose() }
    }

    fun submit(req: ProbeVariableRequest) {
        trace("submit requestId=${req.requestId} className=${req.className} sourceName=${req.sourceName} sourceNamePrefix=${req.sourceNamePrefix} line=${req.line} variable=${req.variableName}")
        emit(AckResponse(req.requestId))

        if (req.maxHits <= 0) {
            emit(ErrorResponse(req.requestId, "invalid_request", "maxHits must be >= 1"))
            emit(CompleteResponse(req.requestId, hits = 0, error = "invalid_request"))
            return
        }

        val threadFilter = req.threadName?.let(::findThreadByName)
        if (req.threadName != null && threadFilter == null) {
            emit(CompleteResponse(req.requestId, hits = 0, error = "thread_not_found"))
            return
        }

        val probe = ActiveProbe(
            request = req,
            createdAtMs = System.currentTimeMillis()
        )
        activeByRequestId[req.requestId] = probe
        try {
            installBreakpointsForTypes(probe, candidateTypes(req), threadFilter)
            installClassPrepareRequestIfNeeded(probe)

            if (probe.eventRequests.isEmpty()) {
                activeByRequestId.remove(req.requestId)
                emit(CompleteResponse(req.requestId, hits = 0, error = "no_executable_location"))
            }
        } catch (t: Throwable) {
            deleteRequests(probe)
            activeByRequestId.remove(req.requestId)
            emit(ErrorResponse(req.requestId, "request_install_failed", t.message))
            emit(CompleteResponse(req.requestId, hits = 0, error = "request_install_failed"))
        }
    }

    private fun handleEventSet(eventSet: EventSet) {
        try {
            for (event in eventSet) {
                when (event) {
                    is BreakpointEvent -> handleBreakpointEvent(event)
                    is ClassPrepareEvent -> handleClassPrepareEvent(event)
                    is VMDeathEvent -> {
                        emit(ErrorResponse("_system", "vm_died"))
                        running.set(false)
                    }
                    is VMDisconnectEvent -> {
                        emit(ErrorResponse("_system", "vm_disconnected"))
                        running.set(false)
                    }
                }
            }
        } finally {
            runCatching { eventSet.resume() }
        }
    }

    private fun handleClassPrepareEvent(event: ClassPrepareEvent) {
        val probe = activeByEventRequest[event.request()] ?: return
        if (probe.done) return

        trace(
            "class prepare requestId=${probe.request.requestId} type=${event.referenceType().name()} source=${safeSourceName(event.referenceType())}"
        )
        try {
            installBreakpointsForTypes(probe, listOf(event.referenceType()), threadFilter = null)
        } catch (t: Throwable) {
            completeAndDelete(probe, error = "request_install_failed")
        }
    }

    private fun handleBreakpointEvent(event: BreakpointEvent) {
        val request = event.request()
        val probe = activeByEventRequest[request] ?: return
        if (probe.done) return

        when (val result = tryExtractValue(event.thread(), probe.request.variableName)) {
            is ExtractResult.Found -> {
                probe.hits += 1
                emit(
                    SampleResponse(
                        requestId = probe.request.requestId,
                        hitIndex = probe.hits,
                        threadName = event.thread().name(),
                        declaringType = result.location.declaringType().name(),
                        method = result.location.method().name(),
                        line = safeLineNumber(result.location),
                        resolution = result.resolution,
                        value = renderValue(result.value)
                    )
                )

                if (probe.hits >= probe.request.maxHits) {
                    completeAndDelete(probe, error = null)
                }
            }

            ExtractResult.MissingDebugInfo -> completeAndDelete(probe, error = "missing_debug_info")
            is ExtractResult.SymbolNotVisible -> {
                probe.lastDetail = result.detail
                probe.lastScope = result.scope
                deleteRequest(probe, request)
                if (!hasPendingRequests(probe)) {
                    completeAndDelete(
                        probe,
                        error = "symbol_not_visible",
                        detail = result.detail,
                        scope = result.scope
                    )
                }
            }
            ExtractResult.ThreadStateError -> completeAndDelete(probe, error = "thread_not_inspectable")
        }
    }

    private fun sweepTimeouts() {
        val now = System.currentTimeMillis()
        val expired = activeByRequestId.values.filter { probe ->
            !probe.done && now - probe.createdAtMs > probe.request.timeoutMs
        }
        expired.forEach {
            completeAndDelete(
                it,
                error = "timed_out_waiting_for_hit",
                detail = it.lastDetail,
                scope = it.lastScope
            )
        }
    }

    private fun completeAndDelete(
        probe: ActiveProbe,
        error: String?,
        detail: String? = probe.lastDetail,
        scope: ScopeSnapshotPayload? = probe.lastScope
    ) {
        if (probe.done) return
        probe.done = true

        deleteRequests(probe)
        activeByRequestId.remove(probe.request.requestId)
        emit(
            CompleteResponse(
                probe.request.requestId,
                hits = probe.hits,
                error = error,
                detail = detail,
                scope = scope
            )
        )
    }

    private fun deleteRequests(probe: ActiveProbe) {
        val requests = synchronized(probe.eventRequests) { probe.eventRequests.toList() }
        requests.forEach { request -> deleteRequest(probe, request) }
        synchronized(probe.eventRequests) {
            probe.eventRequests.clear()
        }
    }

    private fun deleteRequest(probe: ActiveProbe, request: EventRequest) {
        runCatching { request.disable() }
        runCatching { erm.deleteEventRequest(request) }
        activeByEventRequest.remove(request)
        synchronized(probe.eventRequests) {
            probe.eventRequests.remove(request)
        }
    }

    private fun hasPendingRequests(probe: ActiveProbe): Boolean =
        synchronized(probe.eventRequests) { probe.eventRequests.isNotEmpty() }

    private fun emit(message: ProbeResponse) {
        synchronized(out) {
            out.println(ProbeJson.encodeToString<ProbeResponse>(message))
            out.flush()
        }
    }

    private fun findThreadByName(threadName: String): ThreadReference? =
        runCatching { vm.allThreads().firstOrNull { it.name() == threadName } }.getOrNull()

    private fun candidateTypes(req: ProbeVariableRequest): List<ReferenceType> {
        val seen = LinkedHashMap<String, ReferenceType>()

        fun add(type: ReferenceType) {
            seen.putIfAbsent(type.name(), type)
        }

        req.className?.takeIf { it.isNotBlank() }?.let { className ->
            vm.classesByName(className).forEach(::add)
            vm.allClasses()
                .asSequence()
                .filter { it.name() == className || it.name().startsWith("$className$") }
                .forEach(::add)
        }

        req.sourceName?.takeIf { it.isNotBlank() }?.let { sourceName ->
            vm.allClasses()
                .asSequence()
                .filter { runCatching { it.sourceName() == sourceName }.getOrDefault(false) }
                .forEach(::add)
        }

        req.sourceNamePrefix?.takeIf { it.isNotBlank() }?.let { sourceNamePrefix ->
            vm.allClasses()
                .asSequence()
                .filter { runCatching { it.sourceName().startsWith(sourceNamePrefix) }.getOrDefault(false) }
                .forEach(::add)
        }

        return seen.values.toList()
    }

    private fun rankedTypes(types: List<ReferenceType>, req: ProbeVariableRequest): List<ReferenceType> {
        val ranked = types.sortedWith(
            compareBy<ReferenceType>(
                { type ->
                    when {
                        req.className != null && type.name() == req.className -> 0
                        req.className != null && type.name().startsWith("${req.className}$") -> 1
                        else -> 2
                    }
                },
                { type -> type.name().count { it == '$' } },
                { type -> type.name() }
            )
        )
        return ranked
    }

    private fun installBreakpointsForTypes(
        probe: ActiveProbe,
        types: List<ReferenceType>,
        threadFilter: ThreadReference?
    ) {
        for (type in rankedTypes(types, probe.request)) {
            trace(
                "candidate requestId=${probe.request.requestId} type=${type.name()} source=${safeSourceName(type)}"
            )
            val locations = try {
                type.locationsOfLine(probe.request.line)
            } catch (_: AbsentInformationException) {
                trace("candidate missing debug info type=${type.name()} line=${probe.request.line}")
                continue
            } catch (_: Throwable) {
                emptyList()
            }
            if (locations.isEmpty()) continue

            locations.forEach { location ->
                trace(
                    "install breakpoint requestId=${probe.request.requestId} type=${type.name()} method=${location.method().name()} line=${safeLineNumber(location)}"
                )
                val bp = erm.createBreakpointRequest(location)
                bp.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
                if (threadFilter != null) bp.addThreadFilter(threadFilter)
                if (probe.request.maxHits == 1) bp.addCountFilter(1)
                activeByEventRequest[bp] = probe
                synchronized(probe.eventRequests) {
                    probe.eventRequests.add(bp)
                }
                bp.enable()
            }
        }
    }

    private fun installClassPrepareRequestIfNeeded(probe: ActiveProbe) {
        val pattern = classPrepareSourcePattern(probe.request) ?: return
        trace("install class prepare requestId=${probe.request.requestId} pattern=$pattern")
        val prepare = erm.createClassPrepareRequest()
        prepare.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
        prepare.addSourceNameFilter(pattern)
        activeByEventRequest[prepare] = probe
        synchronized(probe.eventRequests) {
            probe.eventRequests.add(prepare)
        }
        prepare.enable()
    }

    private fun classPrepareSourcePattern(req: ProbeVariableRequest): String? =
        when {
            !req.sourceNamePrefix.isNullOrBlank() -> "${req.sourceNamePrefix}*"
            !req.sourceName.isNullOrBlank() -> req.sourceName
            else -> null
        }

    private fun safeSourceName(type: ReferenceType): String? =
        runCatching { type.sourceName() }.getOrNull()

    private fun trace(message: String) {
        if (!traceEnabled) return
        System.err.println("[debug-probe-helper] $message")
    }

    private fun matchesRequestSource(req: ProbeVariableRequest, type: ReferenceType): Boolean {
        val sourceName = safeSourceName(type)
        return when {
            sourceName == null -> false
            !req.sourceNamePrefix.isNullOrBlank() -> sourceName.startsWith(req.sourceNamePrefix)
            !req.sourceName.isNullOrBlank() -> sourceName == req.sourceName
            else -> true
        }
    }

    private sealed interface ExtractResult {
        data class Found(
            val value: Value?,
            val location: Location,
            val resolution: String
        ) : ExtractResult

        data object MissingDebugInfo : ExtractResult
        data class SymbolNotVisible(
            val detail: String,
            val scope: ScopeSnapshotPayload
        ) : ExtractResult
        data object ThreadStateError : ExtractResult
    }

    private fun tryExtractValue(thread: ThreadReference, variableName: String): ExtractResult {
        val frames = try {
            thread.frames()
        } catch (_: IncompatibleThreadStateException) {
            return ExtractResult.ThreadStateError
        } catch (_: Throwable) {
            return ExtractResult.ThreadStateError
        }

        for (frame in frames) {
            val visible = try {
                frame.visibleVariables()
            } catch (_: AbsentInformationException) {
                return ExtractResult.MissingDebugInfo
            } catch (_: Throwable) {
                continue
            }

            val local = visible.firstOrNull { it.name() == variableName }
            if (local != null) {
                return try {
                    ExtractResult.Found(
                        value = frame.getValue(local),
                        location = frame.location(),
                        resolution = "local"
                    )
                } catch (_: Throwable) {
                    ExtractResult.ThreadStateError
                }
            }
        }

        findFieldValue(frames, variableName)?.let { return it }

        return ExtractResult.SymbolNotVisible(
            detail = "No local or class member named '$variableName' was visible at this breakpoint location.",
            scope = collectScopeSnapshot(frames)
        )
    }

    private fun findFieldValue(frames: List<com.sun.jdi.StackFrame>, variableName: String): ExtractResult.Found? {
        for (frame in frames) {
            val declaringType = runCatching { frame.location().declaringType() }.getOrNull() ?: continue
            val field = declaringType.allFields().firstOrNull { it.name() == variableName } ?: continue
            val resolution = if (field.isStatic) "static_field" else "instance_field"

            val value = try {
                if (field.isStatic) {
                    declaringType.getValue(field)
                } else {
                    val thisObject = frame.thisObject() ?: continue
                    thisObject.getValue(field)
                }
            } catch (_: Throwable) {
                continue
            }

            return ExtractResult.Found(
                value = value,
                location = frame.location(),
                resolution = resolution
            )
        }
        return null
    }

    private fun collectScopeSnapshot(frames: List<com.sun.jdi.StackFrame>): ScopeSnapshotPayload {
        val symbols = mutableListOf<ScopeSymbolPayload>()
        val seen = LinkedHashSet<String>()

        fun addSymbol(symbol: ScopeSymbolPayload) {
            if (symbol.name.isBlank()) return
            if (!seen.add(symbol.name)) return
            symbols += symbol
        }

        frames.forEachIndexed { frameIndex, frame ->
            val location = runCatching { frame.location() }.getOrNull()
            val declaringType = runCatching { location?.declaringType()?.name() }.getOrNull()
            val method = runCatching { location?.method()?.name() }.getOrNull()
            val line = location?.let(::safeLineNumber) ?: -1

            val visible = try {
                frame.visibleVariables()
            } catch (_: AbsentInformationException) {
                emptyList()
            } catch (_: Throwable) {
                emptyList()
            }

            visible.forEach { local ->
                val value = runCatching { frame.getValue(local) }.getOrNull()
                val rendered = renderValue(value)
                addSymbol(
                    ScopeSymbolPayload(
                        name = truncateText(local.name(), 48),
                        resolution = "local",
                        type = truncateText(local.typeName(), 80),
                        preview = truncateText(rendered.text, 72),
                        frameIndex = frameIndex,
                        declaringType = declaringType,
                        method = method,
                        line = line
                    )
                )
            }
        }

        frames.forEachIndexed { frameIndex, frame ->
            val location = runCatching { frame.location() }.getOrNull()
            val declaringType = runCatching { location?.declaringType() }.getOrNull() ?: return@forEachIndexed
            val declaringTypeName = runCatching { declaringType.name() }.getOrNull()
            val method = runCatching { location?.method()?.name() }.getOrNull()
            val line = location?.let(::safeLineNumber) ?: -1
            val thisObject = runCatching { frame.thisObject() }.getOrNull()

            declaringType.allFields().forEach { field ->
                val value = try {
                    if (field.isStatic) {
                        declaringType.getValue(field)
                    } else {
                        val owner = thisObject ?: return@forEach
                        owner.getValue(field)
                    }
                } catch (_: Throwable) {
                    return@forEach
                }

                val rendered = renderValue(value)
                addSymbol(
                    ScopeSymbolPayload(
                        name = truncateText(field.name(), 48),
                        resolution = if (field.isStatic) "static_field" else "instance_field",
                        type = truncateText(runCatching { field.typeName() }.getOrNull(), 80),
                        preview = truncateText(rendered.text, 72),
                        frameIndex = frameIndex,
                        declaringType = declaringTypeName,
                        method = method,
                        line = line
                    )
                )
            }
        }

        val limit = 24
        val visibleSymbols = symbols.take(limit)
        return ScopeSnapshotPayload(
            symbols = visibleSymbols,
            omittedCount = (symbols.size - visibleSymbols.size).coerceAtLeast(0)
        )
    }

    private fun truncateText(text: String?, maxChars: Int): String {
        if (text == null) return ""
        val compact = text.replace('\n', ' ').replace('\r', ' ').trim()
        if (compact.length <= maxChars) return compact
        return compact.take(maxChars - 1) + "…"
    }

    private fun renderValue(value: Value?): ValuePayload =
        when (value) {
            null -> ValuePayload(kind = "null", type = null, text = "null")
            is PrimitiveValue -> ValuePayload("primitive", value.type().name(), value.toString())
            is StringReference -> ValuePayload("string", "java.lang.String", value.value())
            is ArrayReference -> {
                val length = runCatching { value.length() }.getOrDefault(-1)
                ValuePayload("array", value.referenceType().name(), "Array(length=$length)")
            }
            is ObjectReference -> ValuePayload("object", value.referenceType().name(), value.toString())
            else -> {
                val typeName = runCatching { value.type().name() }.getOrNull()
                ValuePayload("value", typeName, value.toString())
            }
        }

    private fun safeLineNumber(location: Location): Int =
        runCatching { location.lineNumber() }.getOrDefault(-1)
}

fun attachSocket(host: String, port: String): VirtualMachine {
    val connector = Bootstrap.virtualMachineManager()
        .attachingConnectors()
        .firstOrNull { it.name() == "com.sun.jdi.SocketAttach" }
        ?: error("SocketAttach connector not found")

    val args = connector.defaultArguments()
    args["hostname"]?.setValue(host)
    args["port"]?.setValue(port)
    return connector.attach(args)
}

fun main(args: Array<String>) {
    val host = args.getOrNull(0) ?: "127.0.0.1"
    val port = args.getOrNull(1) ?: "5005"

    val vm = attachSocket(host, port)
    val out = PrintWriter(System.out, true)
    val service = JdiProbeService(vm, out)
    service.startEventLoop()

    val reader = BufferedReader(InputStreamReader(System.`in`))
    while (true) {
        val line = reader.readLine() ?: break

        val req = try {
            ProbeJson.decodeFromString<ProbeRequest>(line)
        } catch (t: Throwable) {
            out.println(
                ProbeJson.encodeToString<ProbeResponse>(
                    ErrorResponse("_parse", "invalid_json", t.message)
                )
            )
            out.flush()
            continue
        }

        when (req) {
            is ProbeVariableRequest -> service.submit(req)
        }
    }

    service.stop()
}
