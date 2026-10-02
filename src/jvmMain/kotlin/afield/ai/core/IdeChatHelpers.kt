package afield.ai.core

class IdeChatHelpers(
    private val bridge: LlmBridge,
    private val log: LogSink = StdoutLog,
    /**
     * Send the instructions as ephemeral per-request context ([TurnContext]) rather than inside the stored
     * message. Off puts every turn back on the one-string prompt; an agent task's `context=legacy` is the A/B arm.
     */
    private val ephemeralInstructions: Boolean = ContextPipeline.enabledByDefault
) {
    /** Backward-compatible constructor: existing call sites pass the concrete OpenAI bridge. */
    constructor(bridge: OpenAiIdeCodexBridge, log: LogSink = StdoutLog) : this(OpenAiLlmBridge(bridge), log)

    private fun turn(fragments: List<ContextFragment>, rest: String) = TurnContext(fragments, rest)

    private fun info(message: String) = log.log("info,ai.chat", message)

    private fun attachmentContextNote(attachments: List<ContextAttachment>): String {
        if (attachments.isEmpty()) return ""
        val lines = attachments.joinToString("\n") { attachment ->
            val description = attachment.description.trim()
            "- ${attachment.fileName} (${attachment.language})" +
                if (description.isNotBlank()) " - $description" else ""
        }
        return buildString {
            appendLine("Attached context files are available via AttachmentRequest:")
            append(lines)
            appendLine()
            appendLine("Some attached files can be graph-connected boxes, not just the current box.")
            appendLine("If the current box exports data and a connected box renders it, inspect and edit the connected renderer's shaders for color/contrast requests.")
            appendLine("Treat `Box.* by Inheritance...` preamble properties as graph handoff clues; treat shader-generated `Mesh`/`ComputeMesh`/`ModulePointer` bindings as shader I/O helpers unless the surrounding code shows a graph value flowing through them.")
        }.trim()
    }

    data class HighLevelResult(
        val editedCode: String,
        val commentary: List<String>,
        val rawResponseId: String,
        val threadState: OpenAiIdeCodexBridge.ThreadState,
        val toolTranscript: List<OpenAiIdeCodexBridge.ToolTranscriptEntry> = emptyList(),
        val contextReport: ContextReport? = null
    )

    fun chatFile(
        state: OpenAiIdeCodexBridge.ThreadState,
        code: String,
        lang: LanguageSpec,
        userPrompt: String,
        attachments: List<ContextAttachment> = emptyList(),
        resolveAttachment: ((String) -> AttachmentResponse?)? = null,
        listDefinitions: (() -> ListDefinitionsResult)? = null,
        listFileContents: (() -> ListContentsResult)? = null,
        listKotlinContextVariables: ((ListKotlinContextVariablesRequest) -> ListKotlinContextVariablesResult)? = null,
        evaluateKotlinExpression: ((EvaluateKotlinExpressionRequest) -> EvaluateKotlinExpressionResult)? = null,
        editOtherFile: ((EditFileRequest) -> EditFileResponse)? = null,
        compileKotlin: ((CompileKotlinRequest) -> CompileKotlinResult)? = null,
        reloadShaders: ((ReloadShadersRequest) -> ReloadShadersResult)? = null,
        captureRenderWindow: ((CaptureRenderWindowRequest) -> CaptureRenderWindowResult)? = null,
        listMacros: ((MacroListRequest) -> MacroListResult)? = null,
        readMacro: ((MacroReadRequest) -> MacroReadResult)? = null,
        writeMacro: ((MacroWriteRequest) -> MacroWriteResult)? = null,
        runMacro: ((MacroRunRequest) -> MacroRunResult)? = null,
        codeUseQuery: ((CodeUseQueryRequest) -> CodeUseQueryResult)? = null,
        semanticRetrievalSearch: ((SemanticRetrievalSearchRequest) -> SemanticRetrievalToolResult)? = null,
        semanticRetrievalStatus: (() -> SemanticRetrievalToolResult)? = null,
        semanticRetrievalRebuild: (() -> SemanticRetrievalToolResult)? = null,
        agentMemory: AgentMemoryState? = null,
        documentGuides: DocumentGuideTools? = null,
        workspaceSymbolSearch: ((WorkspaceSymbolSearch) -> WorkspaceSymbolSearchResult)? = null,
        focus: ToolFocus? = null,
        dynamicTools: List<BoxTool> = emptyList(),
        fieldGraph: afield.ai.agent.FieldGraphTools? = null,
        vectorStoreIds: List<String> = emptyList(),
        onTextDelta: ((String) -> Unit)? = null,
        onProgress: ((OpenAiIdeCodexBridge.ProgressUpdate) -> Unit)? = null
    ): HighLevelResult {
        info("chatFile(): lang=${lang.name} codeChars=${code.length} attachments=${attachments.size}")

        val ctx = ToolExecutionContext(
            attachments = attachments,
            resolveAttachment = resolveAttachment,
            listDefinitions = listDefinitions,
            listFileContents = listFileContents,
            listKotlinContextVariables = listKotlinContextVariables,
            evaluateKotlinExpression = evaluateKotlinExpression,
            editOtherFile = editOtherFile,
            compileKotlin = compileKotlin,
            reloadShaders = reloadShaders,
            captureRenderWindow = captureRenderWindow,
            listMacros = listMacros,
            readMacro = readMacro,
            writeMacro = writeMacro,
            runMacro = runMacro,
            codeUseQuery = codeUseQuery,
            semanticRetrievalSearch = semanticRetrievalSearch,
            semanticRetrievalStatus = semanticRetrievalStatus,
            semanticRetrievalRebuild = semanticRetrievalRebuild,
            agentMemory = agentMemory,
            documentGuides = documentGuides,
            workspaceSymbolSearch = workspaceSymbolSearch,
            focus = focus,
            dynamicTools = dynamicTools,
            fieldGraph = fieldGraph
        )

        val tc = turn(
            buildInstructionFragments(InstructionMode.General, lang, userPrompt, null, null, ctx),
            "$userPrompt\n\n${lang.wrap(code)}"
        )
        val result = bridge.send(
            state,
            tc.legacyPrompt(),
            ctx = ctx,
            vectorStoreIds = vectorStoreIds,
            onTextDelta = onTextDelta,
            onProgress = onProgress,
            turnContext = tc.takeIf { ephemeralInstructions }
        )
        val unwrapped = lang.unwrap(result.text)

        validateNoMarkers(unwrapped)

        info("chatFile(): done editedChars=${unwrapped.length} commentary=${result.commentary.size}")
        return HighLevelResult(
            editedCode = unwrapped,
            commentary = result.commentary,
            rawResponseId = result.rawResponseId,
            threadState = result.threadState,
            toolTranscript = result.toolTranscript,
            contextReport = result.contextReport
        )
    }

    fun chatGeneral(
        state: OpenAiIdeCodexBridge.ThreadState,
        code: String,
        lang: LanguageSpec,
        userPrompt: String,
        imageUrls: List<String> = emptyList(),
        attachments: List<ContextAttachment> = emptyList(),
        resolveAttachment: ((String) -> AttachmentResponse?)? = null,
        listDefinitions: (() -> ListDefinitionsResult)? = null,
        listFileContents: (() -> ListContentsResult)? = null,
        listKotlinContextVariables: ((ListKotlinContextVariablesRequest) -> ListKotlinContextVariablesResult)? = null,
        evaluateKotlinExpression: ((EvaluateKotlinExpressionRequest) -> EvaluateKotlinExpressionResult)? = null,
        editOtherFile: ((EditFileRequest) -> EditFileResponse)? = null,
        compileKotlin: ((CompileKotlinRequest) -> CompileKotlinResult)? = null,
        reloadShaders: ((ReloadShadersRequest) -> ReloadShadersResult)? = null,
        captureRenderWindow: ((CaptureRenderWindowRequest) -> CaptureRenderWindowResult)? = null,
        listMacros: ((MacroListRequest) -> MacroListResult)? = null,
        readMacro: ((MacroReadRequest) -> MacroReadResult)? = null,
        writeMacro: ((MacroWriteRequest) -> MacroWriteResult)? = null,
        runMacro: ((MacroRunRequest) -> MacroRunResult)? = null,
        codeUseQuery: ((CodeUseQueryRequest) -> CodeUseQueryResult)? = null,
        semanticRetrievalSearch: ((SemanticRetrievalSearchRequest) -> SemanticRetrievalToolResult)? = null,
        semanticRetrievalStatus: (() -> SemanticRetrievalToolResult)? = null,
        semanticRetrievalRebuild: (() -> SemanticRetrievalToolResult)? = null,
        agentMemory: AgentMemoryState? = null,
        documentGuides: DocumentGuideTools? = null,
        workspaceSymbolSearch: ((WorkspaceSymbolSearch) -> WorkspaceSymbolSearchResult)? = null,
        focus: ToolFocus? = null,
        dynamicTools: List<BoxTool> = emptyList(),
        fieldGraph: afield.ai.agent.FieldGraphTools? = null,
        vectorStoreIds: List<String> = emptyList(),
        onTextDelta: ((String) -> Unit)? = null,
        onProgress: ((OpenAiIdeCodexBridge.ProgressUpdate) -> Unit)? = null
    ): OpenAiIdeCodexBridge.SendResult {
        info(
            "chatGeneral(): lang=${lang.name} codeChars=${code.length} attachments=${attachments.size} " +
                "images=${imageUrls.size}"
        )

        val ctx = ToolExecutionContext(
            attachments = attachments,
            resolveAttachment = resolveAttachment,
            listDefinitions = listDefinitions,
            listFileContents = listFileContents,
            listKotlinContextVariables = listKotlinContextVariables,
            evaluateKotlinExpression = evaluateKotlinExpression,
            editOtherFile = editOtherFile,
            compileKotlin = compileKotlin,
            reloadShaders = reloadShaders,
            captureRenderWindow = captureRenderWindow,
            listMacros = listMacros,
            readMacro = readMacro,
            writeMacro = writeMacro,
            runMacro = runMacro,
            codeUseQuery = codeUseQuery,
            semanticRetrievalSearch = semanticRetrievalSearch,
            semanticRetrievalStatus = semanticRetrievalStatus,
            semanticRetrievalRebuild = semanticRetrievalRebuild,
            agentMemory = agentMemory,
            documentGuides = documentGuides,
            workspaceSymbolSearch = workspaceSymbolSearch,
            focus = focus,
            dynamicTools = dynamicTools,
            fieldGraph = fieldGraph
        )

        return chatGeneral(state, code, lang, userPrompt, ctx, imageUrls, vectorStoreIds,
            onTextDelta = onTextDelta, onProgress = onProgress)
    }

    /**
     * The document/general chat turn, given an already-built tool context. Every AI4 prose turn goes
     * through here: the box panel's `general` kind and the document chat (via the overload above), and
     * `AgentTaskRunner`, which is a headless entry into this same turn rather than a harness of its own.
     */
    fun chatGeneral(
        state: OpenAiIdeCodexBridge.ThreadState,
        code: String,
        lang: LanguageSpec,
        userPrompt: String,
        ctx: ToolExecutionContext,
        imageUrls: List<String> = emptyList(),
        vectorStoreIds: List<String> = emptyList(),
        maxToolRounds: Int = 24,
        extraInstructions: String = "",
        onTextDelta: ((String) -> Unit)? = null,
        onProgress: ((OpenAiIdeCodexBridge.ProgressUpdate) -> Unit)? = null
    ): OpenAiIdeCodexBridge.SendResult {
        val attachmentNote = attachmentContextNote(ctx.attachments)
        val fragments = buildInstructionFragments(InstructionMode.Chat, lang, userPrompt, null, null, ctx).toMutableList()
        if (extraInstructions.isNotBlank()) fragments += ContextFragment("extra", "\n\n$extraInstructions")
        if (attachmentNote.isNotBlank()) fragments += ContextFragment("attachments", "\n\n$attachmentNote", stability = Stability.VOLATILE)
        val tc = turn(fragments, userPrompt)

        return bridge.send(
            state,
            tc.legacyPrompt(),
            fileText = code,
            ctx = ctx,
            vectorStoreIds = vectorStoreIds,
            imageDataUrls = imageUrls,
            onTextDelta = onTextDelta,
            onProgress = onProgress,
            maxOutputTokens = 10000,
            maxToolRounds = maxToolRounds,
            turnContext = tc.takeIf { ephemeralInstructions }
        )
    }

    fun chatInline(
        state: OpenAiIdeCodexBridge.ThreadState,
        code: String,
        lang: LanguageSpec,
        lineNumber: Int,
        userPrompt: String,
        attachments: List<ContextAttachment> = emptyList(),
        resolveAttachment: ((String) -> AttachmentResponse?)? = null,
        listDefinitions: (() -> ListDefinitionsResult)? = null,
        listFileContents: (() -> ListContentsResult)? = null,
        listKotlinContextVariables: ((ListKotlinContextVariablesRequest) -> ListKotlinContextVariablesResult)? = null,
        evaluateKotlinExpression: ((EvaluateKotlinExpressionRequest) -> EvaluateKotlinExpressionResult)? = null,
        editOtherFile: ((EditFileRequest) -> EditFileResponse)? = null,
        compileKotlin: ((CompileKotlinRequest) -> CompileKotlinResult)? = null,
        reloadShaders: ((ReloadShadersRequest) -> ReloadShadersResult)? = null,
        captureRenderWindow: ((CaptureRenderWindowRequest) -> CaptureRenderWindowResult)? = null,
        listMacros: ((MacroListRequest) -> MacroListResult)? = null,
        readMacro: ((MacroReadRequest) -> MacroReadResult)? = null,
        writeMacro: ((MacroWriteRequest) -> MacroWriteResult)? = null,
        runMacro: ((MacroRunRequest) -> MacroRunResult)? = null,
        codeUseQuery: ((CodeUseQueryRequest) -> CodeUseQueryResult)? = null,
        semanticRetrievalSearch: ((SemanticRetrievalSearchRequest) -> SemanticRetrievalToolResult)? = null,
        semanticRetrievalStatus: (() -> SemanticRetrievalToolResult)? = null,
        semanticRetrievalRebuild: (() -> SemanticRetrievalToolResult)? = null,
        agentMemory: AgentMemoryState? = null,
        documentGuides: DocumentGuideTools? = null,
        workspaceSymbolSearch: ((WorkspaceSymbolSearch) -> WorkspaceSymbolSearchResult)? = null,
        focus: ToolFocus? = null,
        dynamicTools: List<BoxTool> = emptyList(),
        fieldGraph: afield.ai.agent.FieldGraphTools? = null,
        vectorStoreIds: List<String> = emptyList(),
        onTextDelta: ((String) -> Unit)? = null,
        onProgress: ((OpenAiIdeCodexBridge.ProgressUpdate) -> Unit)? = null
    ): HighLevelResult {
        info("chatInline(): lang=${lang.name} line=$lineNumber codeChars=${code.length} attachments=${attachments.size}")

        val ctx = ToolExecutionContext(
            attachments = attachments,
            resolveAttachment = resolveAttachment,
            listDefinitions = listDefinitions,
            listFileContents = listFileContents,
            listKotlinContextVariables = listKotlinContextVariables,
            evaluateKotlinExpression = evaluateKotlinExpression,
            editOtherFile = editOtherFile,
            compileKotlin = compileKotlin,
            reloadShaders = reloadShaders,
            captureRenderWindow = captureRenderWindow,
            listMacros = listMacros,
            readMacro = readMacro,
            writeMacro = writeMacro,
            runMacro = runMacro,
            codeUseQuery = codeUseQuery,
            semanticRetrievalSearch = semanticRetrievalSearch,
            semanticRetrievalStatus = semanticRetrievalStatus,
            semanticRetrievalRebuild = semanticRetrievalRebuild,
            agentMemory = agentMemory,
            documentGuides = documentGuides,
            workspaceSymbolSearch = workspaceSymbolSearch,
            focus = focus,
            dynamicTools = dynamicTools,
            fieldGraph = fieldGraph
        )

        val fragments = buildInstructionFragments(InstructionMode.Inline, lang, userPrompt, null, lineNumber, ctx)

        val wrappedLines = lang.wrap(code).split("\n").toMutableList()
        val insertAt = (lineNumber + 1).coerceIn(0, wrappedLines.size)
        wrappedLines.add(insertAt, "<help>")
        val withHelp = wrappedLines.joinToString("\n")

        val tc = turn(fragments, withHelp)
        val result = bridge.send(
            state,
            tc.legacyPrompt(),
            ctx = ctx,
            vectorStoreIds = vectorStoreIds,
            onTextDelta = onTextDelta,
            onProgress = onProgress,
            turnContext = tc.takeIf { ephemeralInstructions }
        )
        val unwrapped = lang.unwrap(result.text)

        validateNoMarkers(unwrapped)

        info("chatInline(): done editedChars=${unwrapped.length} commentary=${result.commentary.size}")
        return HighLevelResult(
            editedCode = unwrapped,
            commentary = result.commentary,
            rawResponseId = result.rawResponseId,
            threadState = result.threadState,
            toolTranscript = result.toolTranscript,
            contextReport = result.contextReport
        )
    }

    fun chatError(
        state: OpenAiIdeCodexBridge.ThreadState,
        code: String,
        lang: LanguageSpec,
        errorMessage: String,
        attachments: List<ContextAttachment> = emptyList(),
        resolveAttachment: ((String) -> AttachmentResponse?)? = null,
        listDefinitions: (() -> ListDefinitionsResult)? = null,
        listFileContents: (() -> ListContentsResult)? = null,
        listKotlinContextVariables: ((ListKotlinContextVariablesRequest) -> ListKotlinContextVariablesResult)? = null,
        evaluateKotlinExpression: ((EvaluateKotlinExpressionRequest) -> EvaluateKotlinExpressionResult)? = null,
        editOtherFile: ((EditFileRequest) -> EditFileResponse)? = null,
        compileKotlin: ((CompileKotlinRequest) -> CompileKotlinResult)? = null,
        reloadShaders: ((ReloadShadersRequest) -> ReloadShadersResult)? = null,
        captureRenderWindow: ((CaptureRenderWindowRequest) -> CaptureRenderWindowResult)? = null,
        listMacros: ((MacroListRequest) -> MacroListResult)? = null,
        readMacro: ((MacroReadRequest) -> MacroReadResult)? = null,
        writeMacro: ((MacroWriteRequest) -> MacroWriteResult)? = null,
        runMacro: ((MacroRunRequest) -> MacroRunResult)? = null,
        codeUseQuery: ((CodeUseQueryRequest) -> CodeUseQueryResult)? = null,
        semanticRetrievalSearch: ((SemanticRetrievalSearchRequest) -> SemanticRetrievalToolResult)? = null,
        semanticRetrievalStatus: (() -> SemanticRetrievalToolResult)? = null,
        semanticRetrievalRebuild: (() -> SemanticRetrievalToolResult)? = null,
        agentMemory: AgentMemoryState? = null,
        documentGuides: DocumentGuideTools? = null,
        workspaceSymbolSearch: ((WorkspaceSymbolSearch) -> WorkspaceSymbolSearchResult)? = null,
        focus: ToolFocus? = null,
        dynamicTools: List<BoxTool> = emptyList(),
        fieldGraph: afield.ai.agent.FieldGraphTools? = null,
        vectorStoreIds: List<String> = emptyList(),
        onTextDelta: ((String) -> Unit)? = null,
        onProgress: ((OpenAiIdeCodexBridge.ProgressUpdate) -> Unit)? = null
    ): HighLevelResult {
        info("chatError(): lang=${lang.name} error='${errorMessage.take(120)}' codeChars=${code.length}")

        val ctx = ToolExecutionContext(
            attachments = attachments,
            resolveAttachment = resolveAttachment,
            listDefinitions = listDefinitions,
            listFileContents = listFileContents,
            listKotlinContextVariables = listKotlinContextVariables,
            evaluateKotlinExpression = evaluateKotlinExpression,
            editOtherFile = editOtherFile,
            compileKotlin = compileKotlin,
            reloadShaders = reloadShaders,
            captureRenderWindow = captureRenderWindow,
            listMacros = listMacros,
            readMacro = readMacro,
            writeMacro = writeMacro,
            runMacro = runMacro,
            codeUseQuery = codeUseQuery,
            semanticRetrievalSearch = semanticRetrievalSearch,
            semanticRetrievalStatus = semanticRetrievalStatus,
            semanticRetrievalRebuild = semanticRetrievalRebuild,
            agentMemory = agentMemory,
            documentGuides = documentGuides,
            workspaceSymbolSearch = workspaceSymbolSearch,
            focus = focus,
            dynamicTools = dynamicTools,
            fieldGraph = fieldGraph
        )

        val tc = turn(buildInstructionFragments(InstructionMode.Error, lang, null, errorMessage, null, ctx), lang.wrap(code))
        val result = bridge.send(
            state,
            tc.legacyPrompt(),
            ctx = ctx,
            vectorStoreIds = vectorStoreIds,
            onTextDelta = onTextDelta,
            onProgress = onProgress,
            turnContext = tc.takeIf { ephemeralInstructions }
        )
        val unwrapped = lang.unwrap(result.text)

        validateNoMarkers(unwrapped)

        info("chatError(): done editedChars=${unwrapped.length} commentary=${result.commentary.size}")
        return HighLevelResult(
            editedCode = unwrapped,
            commentary = result.commentary,
            rawResponseId = result.rawResponseId,
            threadState = result.threadState,
            toolTranscript = result.toolTranscript,
            contextReport = result.contextReport
        )
    }
}
