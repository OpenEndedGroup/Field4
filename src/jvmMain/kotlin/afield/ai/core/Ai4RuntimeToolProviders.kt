package afield.ai.core

import afield.*
import afield.ai.KotlinSourceIndex
import afield.ai.searchWorkspaceSymbols
import afield.logger.lprintln
import afield.stdlib.*
import kotlinx.coroutines.*

/** Shared runtime providers. The caller supplies targeting and provisional source policy. */
internal class Ai4RuntimeToolProviders(
    private val sessionInfo: SessionInformation,
    private val refResolver: (String, String) -> FileRef,
    private val boxByIdOrNull: (String) -> Box?,
    private val applyStagedShaderSourcesForReload: (Box) -> (() -> Unit) = { {} }
) {
    private val docName = java.io.File(sessionInfo.filename).name
    fun modeForShaderProperty(property: String): String? = when (property) {
        "vertex", "fragment", "vertex_fragment", "vertex_s", "fragment_s" -> "graphics"
        "compute", "compute_s" -> "compute"
        "task", "mesh", "task_s", "mesh_s" -> "mesh"
        "raygen", "miss", "closesthit", "anyhit", "intersection", "callable",
        "raygen_s", "miss_s", "closesthit_s", "anyhit_s", "intersection_s", "callable_s" -> "ray"
        else -> null
    }

    fun modesForShaderReload(target: Box, requestedMode: String, requestedProperty: String): List<String> {
        val normalized = requestedMode.trim().lowercase()
        if (normalized !in setOf("", "auto")) return listOf(
            when (normalized) {
                "graphics", "graphic", "module", "vertex", "fragment" -> "graphics"
                "compute" -> "compute"
                "mesh", "task" -> "mesh"
                "ray", "rays", "raytracing", "ray-tracing", "ray_tracing" -> "ray"
                else -> normalized
            }
        )

        modeForShaderProperty(requestedProperty)?.let { return listOf(it) }

        return buildList {
            if (runCatching { target._shader != null }.getOrDefault(false)) add("graphics")
            if (runCatching { target._computeShader != null }.getOrDefault(false)) add("compute")
            if (runCatching { target._meshShader != null }.getOrDefault(false)) add("mesh")
            if (runCatching { target._rayTracingShader != null }.getOrDefault(false)) add("ray")
        }
    }

    val reloadShaders: (ReloadShadersRequest) -> ReloadShadersResult = reloadShaders@{ req ->
        val ref = refResolver(req.target, "code")
        if (ref.document != docName) {
            return@reloadShaders ReloadShadersResult(
                success = false,
                target = req.target,
                mode = req.mode,
                message = "Unknown document '${ref.document}'."
            )
        }
        val target = boxByIdOrNull(ref.uid)
            ?: return@reloadShaders ReloadShadersResult(
                success = false,
                target = req.target,
                mode = req.mode,
                message = "Unknown box '${ref.uid}'."
            )

        val modes = modesForShaderReload(target, req.mode, ref.property).distinct()
        if (modes.isEmpty()) {
            return@reloadShaders ReloadShadersResult(
                success = false,
                target = fileRefFor(docName, target.id, ref.property),
                mode = req.mode,
                message = "No shader module pointer is attached to '${target.id}'."
            )
        }

        notify("Reloading shader(s): ${modes.joinToString(", ")}", "AI4")
        val errors = mutableListOf<ShaderReloadError>()
        val reloaded = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val oldErrorProxy = target.output.errorProxy
        val restoreShaderSources = applyStagedShaderSourcesForReload(target)

        try {
            runCatching {
                runBlocking {
                    withContext(Runloop.runLoop.main) {
                        target.output.errorProxy = { content, property, line ->
                            if (content != null) {
                                errors += ShaderReloadError(
                                    property = property.ifBlank { "shader" },
                                    line = line,
                                    message = content.toString()
                                )
                            }
                            oldErrorProxy(content, property, line)
                        }
                        try {
                            modes.forEach { mode ->
                                val ok = when (mode) {
                                    "graphics" -> if (runCatching { target._shader != null }.getOrDefault(false)) {
                                        reloadShader(target)
                                    } else {
                                        false
                                    }
                                    "compute" -> if (runCatching { target._computeShader != null }.getOrDefault(false)) {
                                        reloadComputeShader(target)
                                    } else {
                                        false
                                    }
                                    "mesh" -> if (runCatching { target._meshShader != null }.getOrDefault(false)) {
                                        reloadMeshShader(target)
                                    } else {
                                        false
                                    }
                                    "ray" -> if (runCatching { target._rayTracingShader != null }.getOrDefault(false)) {
                                        reloadRayTracingShader(target)
                                    } else {
                                        false
                                    }
                                    else -> {
                                        errors += ShaderReloadError(mode, message = "Unknown shader reload mode '$mode'.")
                                        false
                                    }
                                }
                                if (ok) reloaded += mode else failed += mode
                            }
                        } finally {
                            target.output.errorProxy = oldErrorProxy
                        }
                    }
                }
            }.onFailure { throwable ->
                failed += modes.filter { it !in reloaded }
                errors += ShaderReloadError(
                    property = "shader",
                    message = throwable.message ?: throwable::class.java.name
                )
                target.output.errorProxy = oldErrorProxy
            }
        } finally {
            restoreShaderSources()
        }

        val success = failed.isEmpty() && errors.isEmpty()
        var visualAttached = false
        var visualMessage = ""
        var visualDataUrl = ""
        // `visualVerificationAvailable()` used to gate this too, and it was the whole bug: it asks
        // whether the conversation *already* contains a `data:image/`, which an agent has no way to
        // arrange for itself. The only two things that put one there are a person attaching an image
        // and a person typing `/screenshot`, so a fresh chat could never see the render window, and
        // `visualCheck=true` was refused for the life of the conversation. Worse, it was refused
        // with `visualCheckMessage` left empty, so the model got `visualCheckAttached=false` and no
        // reason at all — which is indistinguishable from a capture that was attempted and failed.
        //
        // Every branch below now leaves a message behind. A refusal that cannot explain itself sends
        // the agent off guessing, and that is exactly what happened.
        if (req.visualCheck && success) {
            runCatching {
                runBlocking {
                    val screenshot = captureChatScreenshot(
                        AiChatScreenshotRequest(uid = target.id, target = ""),
                        sessionInfo
                    )
                    visualMessage = screenshot.message
                    if (screenshot.success && screenshot.dataUrl.isNotBlank()) {
                        visualAttached = true
                        visualDataUrl = screenshot.dataUrl
                    }
                }
            }.onFailure { throwable ->
                visualMessage = throwable.message ?: throwable::class.java.simpleName
            }
        } else if (req.visualCheck) {
            // Still conditional on the reload having worked, but for a real reason: this is a
            // *post-reload* screenshot, and after a failed reload the window still shows the old
            // shader, so attaching it would be actively misleading. Name the other tool, because
            // "look at it as it stands" is a legitimate thing to want here.
            visualMessage = "Not captured: the reload did not succeed, so the window still shows the " +
                "previous shader and a screenshot would be misleading. Fix the errors and reload " +
                "again, or call CaptureRenderWindow to see the window as it currently stands."
        }

        val message = buildString {
            append(if (success) "Shader reload succeeded" else "Shader reload failed")
            append(" for ${target.id}")
            if (reloaded.isNotEmpty()) append("; reloaded=${reloaded.joinToString(",")}")
            if (failed.isNotEmpty()) append("; failed=${failed.joinToString(",")}")
            if (visualMessage.isNotBlank()) append("; visual=$visualMessage")
        }
        notify(message, "AI4")

        ReloadShadersResult(
            success = success,
            target = fileRefFor(docName, target.id, ref.property),
            mode = req.mode,
            message = message,
            reloaded = reloaded,
            errors = errors,
            visualCheckAttached = visualAttached,
            visualCheckMessage = visualMessage,
            visualDataUrl = visualDataUrl
        )
    }

    /**
     * Look at the afield.four render window, on demand.
     *
     * Separate from [reloadShaders]' `visualCheck` because that one is a *post-reload* check: it
     * only means anything when a reload has just succeeded, and it presupposes there was a shader
     * edit at all. An agent doing graphics work wants to look *before* it changes anything, and
     * wants to look when a reload has just failed. Until this tool existed the only route to the
     * window was a successful shader reload, so an agent asked to inspect a scene had to call
     * ReloadShaders on a shader it had not modified and hope — which is precisely what the logs
     * show one doing, twice, before giving up and reporting that no image had been attached.
     *
     * Ungated on purpose. See the note on the gate in [reloadShaders] above.
     */
    val captureRenderWindow: (CaptureRenderWindowRequest) -> CaptureRenderWindowResult = { req ->
        val settle = req.settleMs.coerceIn(0, CAPTURE_RENDER_WINDOW_MAX_SETTLE_MS)
        // requestedRefOrCurrent already handles all three spellings: blank (this chat's box), an
        // afield:// ref, and a bare uid.
        runCatching {
            val ref = refResolver(req.uid, "code")
            require(ref.document == docName) { "Capture must target the attached document '$docName'" }
            val uid = ref.uid
            runBlocking {
                if (settle > 0) delay(settle.toLong())
                val shot = captureChatScreenshot(
                    AiChatScreenshotRequest(uid = uid, target = req.target),
                    sessionInfo
                )
                CaptureRenderWindowResult(
                    success = shot.success && shot.dataUrl.isNotBlank(),
                    message = shot.message.ifBlank {
                        if (shot.success) "Captured the render window." else "Capture failed."
                    },
                    title = shot.title,
                    width = shot.width,
                    height = shot.height,
                    settledMs = settle,
                    dataUrl = if (shot.success) shot.dataUrl else ""
                )
            }
        }.getOrElse { throwable ->
            val why = throwable.message ?: throwable::class.java.simpleName
            lprintln("warning,ai4", "CaptureRenderWindow failed for uid '${req.uid}': $why")
            CaptureRenderWindowResult(success = false, message = why, settledMs = settle)
        }
    }


    val codeUseQuery: (CodeUseQueryRequest) -> CodeUseQueryResult = codeUseQuery@{ req ->
        val kind = req.kind.trim().lowercase().ifBlank { "failures" }
        val limit = req.limit.coerceIn(1, 50)
        val fetchLimit = (limit * 5).coerceIn(limit, 500)
        val documentFilter = req.document.trim()
        val boxFilter = req.box.trim()

        fun matches(document: String, boxUid: String, boxName: String): Boolean {
            if (documentFilter.isNotBlank() && !document.contains(documentFilter, ignoreCase = true)) return false
            if (boxFilter.isNotBlank()) {
                val hit = boxUid.contains(boxFilter, ignoreCase = true) ||
                    boxName.contains(boxFilter, ignoreCase = true)
                if (!hit) return false
            }
            return true
        }

        when (kind) {
            "summary", "status" -> {
                val summary = CodeUseTelemetryService.summary()
                CodeUseQueryResult(
                    success = summary.enabled,
                    kind = "summary",
                    message = if (summary.enabled) {
                        "Code-use telemetry is enabled; snippets=${summary.snippets}, failures available via kind=failures."
                    } else {
                        summary.message.ifBlank { "Code-use telemetry is disabled." }
                    },
                    summary = summary
                )
            }

            "failure", "failures", "error", "errors", "exceptions" -> {
                val rows = CodeUseTelemetryService.failures(fetchLimit)
                    .filter { matches(it.document, it.boxUid, it.boxName) }
                    .take(limit)
                CodeUseQueryResult(
                    success = true,
                    kind = "failures",
                    message = "Returned ${rows.size} retained compiler/runtime failure row(s).",
                    summary = CodeUseTelemetryService.summary(),
                    failures = rows
                )
            }

            "snippet", "snippets", "timeline" -> {
                val rows = CodeUseTelemetryService.recentSnippets(fetchLimit)
                    .filter { matches(it.document, it.boxUid, it.boxName) }
                    .take(limit)
                CodeUseQueryResult(
                    success = true,
                    kind = "snippets",
                    message = "Returned ${rows.size} recent snippet row(s).",
                    summary = CodeUseTelemetryService.summary(),
                    snippets = rows
                )
            }

            "launch", "launches", "lifecycle", "lifecycles" -> {
                val rows = CodeUseTelemetryService.recentLaunches(fetchLimit)
                    .filter { matches(it.document, it.boxUid, it.boxName) }
                    .take(limit)
                CodeUseQueryResult(
                    success = true,
                    kind = "launches",
                    message = "Returned ${rows.size} launch lifecycle row(s).",
                    summary = CodeUseTelemetryService.summary(),
                    launches = rows
                )
            }

            "symbol", "symbols" -> {
                val rows = CodeUseTelemetryService.symbols(limit)
                CodeUseQueryResult(
                    success = true,
                    kind = "symbols",
                    message = "Returned ${rows.size} symbol row(s). Symbol rows are populated once the analyzer has resolved uses.",
                    summary = CodeUseTelemetryService.summary(),
                    symbols = rows
                )
            }

            "unresolved" -> {
                val rows = CodeUseTelemetryService.unresolved(limit)
                CodeUseQueryResult(
                    success = true,
                    kind = "unresolved",
                    message = "Returned ${rows.size} unresolved analyzer row(s).",
                    summary = CodeUseTelemetryService.summary(),
                    unresolved = rows
                )
            }

            "line-callers", "callers", "callers-for-source-line", "source-line-callers" -> {
                val (target, rows) = CodeUseTelemetryService.callersForSourceLine(
                    sourcePath = req.sourcePath,
                    line = req.line,
                    limit = limit
                )
                CodeUseQueryResult(
                    success = target != null,
                    kind = "line-callers",
                    message = if (target != null) {
                        "Returned ${rows.size} recent Field snippet caller row(s) for ${target.symbolName} at ${target.sourceFile}:${target.line}."
                    } else {
                        "Could not resolve a source symbol for '${req.sourcePath}:${req.line}'."
                    },
                    summary = CodeUseTelemetryService.summary(),
                    target = target,
                    callers = rows
                )
            }

            "references", "references-for-code", "code-references", "refs" -> {
                val rows = CodeUseTelemetryService.referencesForCode(req.code, limit.coerceAtMost(10))
                CodeUseQueryResult(
                    success = true,
                    kind = "references-for-code",
                    message = "Returned ${rows.size} likely Field source reference row(s).",
                    summary = CodeUseTelemetryService.summary(),
                    references = rows
                )
            }

            "related-source", "related-source-for-code", "source-bundle", "source-context" -> {
                val rows = CodeUseTelemetryService.relatedSourceForCode(
                    code = req.code,
                    symbolLimit = limit.coerceAtMost(10),
                    callersPerSymbol = req.callersPerSymbol,
                    sourceRadiusLines = req.sourceRadiusLines
                )
                CodeUseQueryResult(
                    success = true,
                    kind = "related-source-for-code",
                    message = "Returned ${rows.size} related Field source bundle row(s) with excerpts, usage counts, and peer callers.",
                    summary = CodeUseTelemetryService.summary(),
                    relatedSources = rows
                )
            }

            "semantic", "semantic-analyze", "semantic-analysis", "analyze-code", "semantic-references" -> {
                val analysis = CodeUseTelemetryService.semanticAnalyzeCode(
                    CodeUseSemanticAnalyzeRequest(
                        code = req.code,
                        sourceName = req.sourceName,
                        limit = limit.coerceAtMost(10),
                        callersPerSymbol = req.callersPerSymbol,
                        sourceRadiusLines = req.sourceRadiusLines
                    )
                )
                CodeUseQueryResult(
                    success = analysis.success,
                    kind = "semantic-analyze",
                    message = analysis.message,
                    summary = CodeUseTelemetryService.summary(),
                    references = analysis.references,
                    relatedSources = analysis.relatedSources,
                    semanticAnalysis = analysis
                )
            }

            "hot", "hot-symbols", "hot-source-symbols" -> {
                val rows = CodeUseTelemetryService.hotSourceSymbols(req.window, limit)
                CodeUseQueryResult(
                    success = true,
                    kind = "hot-symbols",
                    message = "Returned ${rows.size} hot source symbol row(s) for window='${req.window}'. Confidence values are literal: k2-resolved is semantic; source-index values are conservative static matches; textual values are fallback evidence.",
                    summary = CodeUseTelemetryService.summary(),
                    hotSymbols = rows
                )
            }

            "early", "early-document-symbols", "open-time", "open-only" -> {
                val rows = CodeUseTelemetryService.earlyDocumentSymbols(
                    document = req.document,
                    earlyWindowMs = req.earlyWindowMs,
                    limit = limit
                )
                CodeUseQueryResult(
                    success = true,
                    kind = "early-document-symbols",
                    message = "Returned ${rows.size} source symbol row(s) used early in a document and rarely afterward.",
                    summary = CodeUseTelemetryService.summary(),
                    earlySymbols = rows
                )
            }

            else -> CodeUseQueryResult(
                success = false,
                kind = kind,
                message = "Unknown kind '${req.kind}'. Use summary, failures, snippets, launches, symbols, unresolved, line-callers, references-for-code, related-source-for-code, semantic-analyze, hot-symbols, or early-document-symbols."
            )
        }
    }


    val listKotlinContextVariables: (ListKotlinContextVariablesRequest) -> ListKotlinContextVariablesResult =
        listKotlinContextVariables@{ req ->
            val ref = refResolver(req.target, "code")
            if (ref.document != docName) {
                return@listKotlinContextVariables ListKotlinContextVariablesResult(
                    success = false,
                    target = req.target,
                    boxUid = ref.uid,
                    boxName = "",
                    generation = 0,
                    message = "Unknown document '${ref.document}'."
                )
            }
            val target = boxByIdOrNull(ref.uid)
                ?: return@listKotlinContextVariables ListKotlinContextVariablesResult(
                    success = false,
                    target = req.target,
                    boxUid = ref.uid,
                    boxName = "",
                    generation = 0,
                    message = "Unknown box '${ref.uid}'."
                )

            val variables = target.ki.context.vars.values
                .sortedBy { it.name }
                .map { info ->
                    val value = info.value
                    KotlinContextVariableInfo(
                        name = info.name,
                        declaration = runCatching { shortenType(info.descriptor.toString()) }.getOrDefault(info.type),
                        declaredType = runCatching { shortenType(info.descriptor.returnType.toString()) }.getOrDefault(""),
                        runtimeType = runtimeTypeName(value),
                        mutable = info.descriptor is kotlin.reflect.KMutableProperty<*>,
                        valuePreview = clipToolText(safeString(value), req.maxValueChars),
                        primitiveValue = primitiveToolValue(value),
                        primitiveKind = primitiveKind(value)
                    )
                }

            ListKotlinContextVariablesResult(
                success = true,
                target = fileRefFor(docName, target.id, ref.property),
                boxUid = target.id,
                boxName = boxLabel(target),
                generation = target.ki.context.generation,
                message = "Found ${variables.size} Kotlin context variable(s) for ${target.id}.",
                variables = variables
            )
        }


    val workspaceSymbolSearch: (WorkspaceSymbolSearch) -> WorkspaceSymbolSearchResult = { req ->
        searchWorkspaceSymbols(KotlinSourceIndex.latestIndex ?: KotlinSourceIndex.loadIndex(), req.query.trim(), req.limit)
    }
}
