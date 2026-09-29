package afield.ai.core

import afield.ai.RelatedContextDiscovery

/**
 * Registers the full IDE agent tool set (attachments, Kotlin tools, macros, CodeUse,
 * semantic retrieval, agent-memory, workspace symbol search, Commentary) on [tools].
 * Provider-independent: every handler references only the [ToolExecutionContext] closures,
 * so both OpenAiIdeCodexBridge and AnthropicBridge populate identical registries from here.
 */
internal fun registerBuiltInAgentTools(tools: ToolRegistry) {
        // ---- code outside core -------------------------------------------------------------
        // Whatever registered itself through AgentToolExtensions: personal packages, a document's own
        // boxes, anything. Core deliberately does not name a domain cluster here — it used to name one
        // directly, and moving that cluster into src/personal made the compiler reject the reference.
        // Inverting it was the right fix independent of the move; see AgentToolExtensions.
        AgentToolExtensions.applyTo(tools)
        registerDocumentGuideTools(tools)
        registerDocumentSourceTools(tools)
        afield.ai.agent.registerFieldGraphTools(tools)


        // Commentary: handler can be a no-op acknowledgement; we extract text from call args anyway.
        tools.register(Commentary::class.java) { _, _ -> mapOf("ok" to true) }

        // Attachments
        tools.register(ListAttachmentsRequest::class.java, { ctx -> ctx.attachments.isNotEmpty() || ctx.resolveAttachment != null }) { ctx, _ ->
            ListAttachmentsResult(
                attachments = ctx.attachments.map { AttachmentInfo(it.fileName, it.language, it.description) }
            )
        }
        tools.register(AttachmentRequest::class.java, { ctx -> ctx.attachments.isNotEmpty() || ctx.resolveAttachment != null }) { ctx, req ->
            val requested = req.file.trim()
            if (requested.isEmpty()) {
                throw IllegalArgumentException("Attachment name is empty.")
            }

            fun normalizePath(value: String): String = value.replace('\\', '/').trim()

            val normalizedRequested = normalizePath(requested)
            val hit = ctx.attachments.firstOrNull { it.fileName == requested }
                ?: ctx.attachments.firstOrNull { normalizePath(it.fileName) == normalizedRequested }
                ?: ctx.attachments.firstOrNull {
                    val normalizedCandidate = normalizePath(it.fileName)
                    normalizedCandidate.endsWith("/$normalizedRequested")
                }

            if (hit != null) {
                val related = RelatedContextDiscovery.forPath(hit.fileName)
                AttachmentResponse(
                    file = hit.fileName,
                    language = hit.language,
                    content = hit.code,
                    relatedGuides = related.guides,
                    relatedExamples = related.examples
                )
            } else {
                ctx.resolveAttachment?.invoke(requested)
                    ?: throw IllegalArgumentException(
                        "No attachment or resolvable file named '$requested'. Call ListAttachments first."
                    )
            }
        }

        // Kotlin-only tools (only work if provider is supplied in ctx)
        tools.register(ListDefinitionsRequest::class.java, { ctx -> ctx.listDefinitions != null }) { ctx, _ ->
            ctx.listDefinitions?.invoke()
                ?: throw IllegalStateException("ListDefinitions not available for this request.")
        }
        tools.register(ListContentsRequest::class.java, { ctx -> ctx.listFileContents != null }) { ctx, _ ->
            ctx.listFileContents?.invoke()
                ?: throw IllegalStateException("ListContents not available for this request.")
        }
        tools.register(ListKotlinContextVariablesRequest::class.java, { ctx -> ctx.listKotlinContextVariables != null }) { ctx, req ->
            ctx.listKotlinContextVariables?.invoke(req)
                ?: throw IllegalStateException("ListKotlinContextVariables not available for this request.")
        }
        tools.register(EvaluateKotlinExpressionRequest::class.java, { ctx -> ctx.evaluateKotlinExpression != null }) { ctx, req ->
            ctx.evaluateKotlinExpression?.invoke(req)
                ?: throw IllegalStateException("EvaluateKotlinExpression not available for this request.")
        }
        tools.register(EditFileRequest::class.java, { ctx -> ctx.editOtherFile != null }) { ctx, req ->
            ctx.editOtherFile?.invoke(req)
                ?: throw IllegalStateException("EditFile not available for this request.")
        }
        tools.register(CompileKotlinRequest::class.java, { ctx -> ctx.compileKotlin != null }) { ctx, req ->
            ctx.compileKotlin?.invoke(req)
                ?: throw IllegalStateException("CompileKotlin not available for this request.")
        }
        tools.register(ReloadShadersRequest::class.java, { ctx -> ctx.reloadShaders != null }) { ctx, req ->
            ctx.reloadShaders?.invoke(req)
                ?: throw IllegalStateException("ReloadShaders not available for this request.")
        }
        // Deliberately gated only on the provider existing, not on whether the conversation already
        // happens to contain an image. Seeing the render window is the whole point of an agent editing
        // graphics code, and making it conditional on prior visual context is what left agents blind.
        tools.register(CaptureRenderWindowRequest::class.java, { ctx -> ctx.captureRenderWindow != null }) { ctx, req ->
            ctx.captureRenderWindow?.invoke(req)
                ?: throw IllegalStateException("CaptureRenderWindow not available for this request.")
        }
        tools.register(MacroListRequest::class.java, { ctx -> ctx.listMacros != null }) { ctx, req ->
            ctx.listMacros?.invoke(req)
                ?: throw IllegalStateException("MacroList not available for this request.")
        }
        tools.register(MacroReadRequest::class.java, { ctx -> ctx.readMacro != null }) { ctx, req ->
            ctx.readMacro?.invoke(req)
                ?: throw IllegalStateException("MacroRead not available for this request.")
        }
        tools.register(MacroWriteRequest::class.java, { ctx -> ctx.writeMacro != null }) { ctx, req ->
            ctx.writeMacro?.invoke(req)
                ?: throw IllegalStateException("MacroWrite not available for this request.")
        }
        tools.register(MacroRunRequest::class.java, { ctx -> ctx.runMacro != null }) { ctx, req ->
            ctx.runMacro?.invoke(req)
                ?: throw IllegalStateException("MacroRun not available for this request.")
        }
        tools.register(CodeUseQueryRequest::class.java, { ctx -> ctx.codeUseQuery != null }) { ctx, req ->
            ctx.codeUseQuery?.invoke(req)
                ?: throw IllegalStateException("CodeUseQuery not available for this request.")
        }
        tools.register(CodeUseCallersForSourceLineRequest::class.java, { ctx -> ctx.codeUseQuery != null }) { ctx, req ->
            ctx.codeUseQuery?.invoke(
                CodeUseQueryRequest(
                    kind = "line-callers",
                    sourcePath = req.sourcePath,
                    line = req.line,
                    limit = req.limit
                )
            ) ?: throw IllegalStateException("CodeUseCallersForSourceLine not available for this request.")
        }
        tools.register(CodeUseReferencesForCodeRequest::class.java, { ctx -> ctx.codeUseQuery != null }) { ctx, req ->
            ctx.codeUseQuery?.invoke(
                CodeUseQueryRequest(
                    kind = "references-for-code",
                    code = req.code,
                    limit = req.limit
                )
            ) ?: throw IllegalStateException("CodeUseReferencesForCode not available for this request.")
        }
        tools.register(CodeUseRelatedSourceForCodeRequest::class.java, { ctx -> ctx.codeUseQuery != null }) { ctx, req ->
            ctx.codeUseQuery?.invoke(
                CodeUseQueryRequest(
                    kind = "related-source-for-code",
                    code = req.code,
                    limit = req.limit,
                    callersPerSymbol = req.callersPerSymbol,
                    sourceRadiusLines = req.sourceRadiusLines
                )
            ) ?: throw IllegalStateException("CodeUseRelatedSourceForCode not available for this request.")
        }
        tools.register(CodeUseSemanticAnalyzeCodeRequest::class.java, { ctx -> ctx.codeUseQuery != null }) { ctx, req ->
            ctx.codeUseQuery?.invoke(
                CodeUseQueryRequest(
                    kind = "semantic-analyze",
                    code = req.code,
                    sourceName = req.sourceName,
                    limit = req.limit,
                    callersPerSymbol = req.callersPerSymbol,
                    sourceRadiusLines = req.sourceRadiusLines
                )
            ) ?: throw IllegalStateException("CodeUseSemanticAnalyzeCode not available for this request.")
        }
        tools.register(CodeUseHotSymbolsRequest::class.java, { ctx -> ctx.codeUseQuery != null }) { ctx, req ->
            ctx.codeUseQuery?.invoke(
                CodeUseQueryRequest(
                    kind = "hot-symbols",
                    window = req.window,
                    limit = req.limit
                )
            ) ?: throw IllegalStateException("CodeUseHotSymbols not available for this request.")
        }
        tools.register(CodeUseEarlyDocumentSymbolsRequest::class.java, { ctx -> ctx.codeUseQuery != null }) { ctx, req ->
            ctx.codeUseQuery?.invoke(
                CodeUseQueryRequest(
                    kind = "early-document-symbols",
                    document = req.document,
                    earlyWindowMs = req.earlyWindowMs,
                    limit = req.limit
                )
            ) ?: throw IllegalStateException("CodeUseEarlyDocumentSymbols not available for this request.")
        }
        tools.register(SemanticRetrievalSearchRequest::class.java, { ctx -> ctx.semanticRetrievalSearch != null }) { ctx, req ->
            ctx.semanticRetrievalSearch?.invoke(req)
                ?: throw IllegalStateException("SemanticRetrievalSearch not available for this request.")
        }
        tools.register(SemanticRetrievalStatusRequest::class.java, { ctx -> ctx.semanticRetrievalStatus != null }) { ctx, _ ->
            ctx.semanticRetrievalStatus?.invoke()
                ?: throw IllegalStateException("SemanticRetrievalStatus not available for this request.")
        }
        tools.register(SemanticRetrievalRebuildRequest::class.java, { ctx -> ctx.semanticRetrievalRebuild != null }) { ctx, _ ->
            ctx.semanticRetrievalRebuild?.invoke()
                ?: throw IllegalStateException("SemanticRetrievalRebuild not available for this request.")
        }
        tools.register(ScratchpadReadRequest::class.java, { ctx -> ctx.agentMemory != null }) { ctx, _ ->
            ctx.agentMemory?.readScratchpad()
                ?: throw IllegalStateException("Scratchpad not available for this request.")
        }
        tools.register(ScratchpadUpdateRequest::class.java, { ctx -> ctx.agentMemory != null }) { ctx, req ->
            ctx.agentMemory?.updateScratchpad(req)
                ?: throw IllegalStateException("Scratchpad not available for this request.")
        }
        tools.register(ChecklistReadRequest::class.java, { ctx -> ctx.agentMemory != null }) { ctx, _ ->
            ctx.agentMemory?.readChecklist()
                ?: throw IllegalStateException("Checklist not available for this request.")
        }
        tools.register(ChecklistUpdateRequest::class.java, { ctx -> ctx.agentMemory != null }) { ctx, req ->
            ctx.agentMemory?.updateChecklist(req)
                ?: throw IllegalStateException("Checklist not available for this request.")
        }

        // Optional extra tool
        tools.register(WorkspaceSymbolSearch::class.java, { ctx -> ctx.workspaceSymbolSearch != null }) { ctx, req ->
            ctx.workspaceSymbolSearch?.invoke(req)
                ?: throw IllegalStateException("WorkspaceSymbolSearch not available for this request.")
        }
}
