package afield.ai.core

class IdeChatHelpers(
    private val bridge: LlmBridge,
    private val log: LogSink = StdoutLog
) {
    /** Backward-compatible constructor: existing call sites pass the concrete OpenAI bridge. */
    constructor(bridge: OpenAiIdeCodexBridge, log: LogSink = StdoutLog) : this(OpenAiLlmBridge(bridge), log)

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
        val toolTranscript: List<OpenAiIdeCodexBridge.ToolTranscriptEntry> = emptyList()
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
        workspaceSymbolSearch: ((WorkspaceSymbolSearch) -> WorkspaceSymbolSearchResult)? = null,
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
            workspaceSymbolSearch = workspaceSymbolSearch
        )

        val instructions = buildDynamicInstructions(
            mode = InstructionMode.General,
            lang = lang,
            userPrompt = userPrompt,
            errorMessage = null,
            lineNumber = null,
            ctx = ctx
        )

        val wrapped = lang.wrap(code)
        val result = bridge.send(
            state,
            "$instructions\n\n$userPrompt\n\n$wrapped",
            ctx = ctx,
            vectorStoreIds = vectorStoreIds,
            onTextDelta = onTextDelta,
            onProgress = onProgress
        )
        val unwrapped = lang.unwrap(result.text)

        validateNoMarkers(unwrapped)

        info("chatFile(): done editedChars=${unwrapped.length} commentary=${result.commentary.size}")
        return HighLevelResult(
            editedCode = unwrapped,
            commentary = result.commentary,
            rawResponseId = result.rawResponseId,
            threadState = result.threadState,
            toolTranscript = result.toolTranscript
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
        workspaceSymbolSearch: ((WorkspaceSymbolSearch) -> WorkspaceSymbolSearchResult)? = null,
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
            workspaceSymbolSearch = workspaceSymbolSearch
        )

        val instructions = buildDynamicInstructions(
            mode = InstructionMode.Chat,
            lang = lang,
            userPrompt = userPrompt,
            errorMessage = null,
            lineNumber = null,
            ctx = ctx
        )

        val attachmentNote = attachmentContextNote(attachments)
        val prompt = buildString {
            append(instructions)
            if (attachmentNote.isNotBlank()) {
                append("\n\n")
                append(attachmentNote)
            }
            append("\n\n")
            append(userPrompt)
        }

        return bridge.send(
            state,
            prompt,
            fileText = code,
            ctx = ctx,
            vectorStoreIds = vectorStoreIds,
            imageDataUrls = imageUrls,
            onTextDelta = onTextDelta,
            onProgress = onProgress,
            maxOutputTokens = 10000
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
        workspaceSymbolSearch: ((WorkspaceSymbolSearch) -> WorkspaceSymbolSearchResult)? = null,
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
            workspaceSymbolSearch = workspaceSymbolSearch
        )

        val instructions = buildDynamicInstructions(
            mode = InstructionMode.Inline,
            lang = lang,
            userPrompt = userPrompt,
            errorMessage = null,
            lineNumber = lineNumber,
            ctx = ctx
        )

        val wrappedLines = lang.wrap(code).split("\n").toMutableList()
        val insertAt = (lineNumber + 1).coerceIn(0, wrappedLines.size)
        wrappedLines.add(insertAt, "<help>")
        val withHelp = wrappedLines.joinToString("\n")

        val result = bridge.send(
            state,
            "$instructions\n\n$withHelp",
            ctx = ctx,
            vectorStoreIds = vectorStoreIds,
            onTextDelta = onTextDelta,
            onProgress = onProgress
        )
        val unwrapped = lang.unwrap(result.text)

        validateNoMarkers(unwrapped)

        info("chatInline(): done editedChars=${unwrapped.length} commentary=${result.commentary.size}")
        return HighLevelResult(
            editedCode = unwrapped,
            commentary = result.commentary,
            rawResponseId = result.rawResponseId,
            threadState = result.threadState,
            toolTranscript = result.toolTranscript
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
        workspaceSymbolSearch: ((WorkspaceSymbolSearch) -> WorkspaceSymbolSearchResult)? = null,
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
            workspaceSymbolSearch = workspaceSymbolSearch
        )

        val instructions = buildDynamicInstructions(
            mode = InstructionMode.Error,
            lang = lang,
            userPrompt = null,
            errorMessage = errorMessage,
            lineNumber = null,
            ctx = ctx
        )

        val wrapped = lang.wrap(code)
        val result = bridge.send(
            state,
            "$instructions\n\n$wrapped",
            ctx = ctx,
            vectorStoreIds = vectorStoreIds,
            onTextDelta = onTextDelta,
            onProgress = onProgress
        )
        val unwrapped = lang.unwrap(result.text)

        validateNoMarkers(unwrapped)

        info("chatError(): done editedChars=${unwrapped.length} commentary=${result.commentary.size}")
        return HighLevelResult(
            editedCode = unwrapped,
            commentary = result.commentary,
            rawResponseId = result.rawResponseId,
            threadState = result.threadState,
            toolTranscript = result.toolTranscript
        )
    }
}
