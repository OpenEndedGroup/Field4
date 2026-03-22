package afield.ai.core

class IdeChatHelpers(
    private val bridge: OpenAiIdeCodexBridge,
    private val log: LogSink = StdoutLog
) {
    private fun info(message: String) = log.log("info,ai.chat", message)

    private fun attachmentContextNote(attachments: List<ContextAttachment>): String {
        if (attachments.isEmpty()) return ""
        val lines = attachments.joinToString("\n") { attachment ->
            "- ${attachment.fileName} (${attachment.language})"
        }
        return buildString {
            appendLine("Attached context files are available via AttachmentRequest:")
            append(lines)
            appendLine()
            appendLine("If the current box includes sibling shader/code properties above, inspect them before claiming they are unavailable.")
        }.trim()
    }

    data class HighLevelResult(
        val editedCode: String,
        val commentary: List<String>,
        val rawResponseId: String,
        val threadState: OpenAiIdeCodexBridge.ThreadState
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
        editOtherFile: ((EditFileRequest) -> EditFileResponse)? = null,
        workspaceSymbolSearch: ((WorkspaceSymbolSearch) -> WorkspaceSymbolSearchResult)? = null,
        vectorStoreIds: List<String> = emptyList(),
        onTextDelta: ((String) -> Unit)? = null
    ): HighLevelResult {
        info("chatFile(): lang=${lang.name} codeChars=${code.length} attachments=${attachments.size}")

        val ctx = ToolExecutionContext(
            attachments = attachments,
            resolveAttachment = resolveAttachment,
            listDefinitions = listDefinitions,
            listFileContents = listFileContents,
            editOtherFile = editOtherFile,
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
            onTextDelta = onTextDelta
        )
        val unwrapped = lang.unwrap(result.text)

        validateNoMarkers(unwrapped)

        info("chatFile(): done editedChars=${unwrapped.length} commentary=${result.commentary.size}")
        return HighLevelResult(
            editedCode = unwrapped,
            commentary = result.commentary,
            rawResponseId = result.rawResponseId,
            threadState = result.threadState
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
        editOtherFile: ((EditFileRequest) -> EditFileResponse)? = null,
        workspaceSymbolSearch: ((WorkspaceSymbolSearch) -> WorkspaceSymbolSearchResult)? = null,
        vectorStoreIds: List<String> = emptyList(),
        onTextDelta: ((String) -> Unit)? = null
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
            editOtherFile = editOtherFile,
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
        editOtherFile: ((EditFileRequest) -> EditFileResponse)? = null,
        workspaceSymbolSearch: ((WorkspaceSymbolSearch) -> WorkspaceSymbolSearchResult)? = null,
        vectorStoreIds: List<String> = emptyList(),
        onTextDelta: ((String) -> Unit)? = null
    ): HighLevelResult {
        info("chatInline(): lang=${lang.name} line=$lineNumber codeChars=${code.length} attachments=${attachments.size}")

        val ctx = ToolExecutionContext(
            attachments = attachments,
            resolveAttachment = resolveAttachment,
            listDefinitions = listDefinitions,
            listFileContents = listFileContents,
            editOtherFile = editOtherFile,
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
            onTextDelta = onTextDelta
        )
        val unwrapped = lang.unwrap(result.text)

        validateNoMarkers(unwrapped)

        info("chatInline(): done editedChars=${unwrapped.length} commentary=${result.commentary.size}")
        return HighLevelResult(
            editedCode = unwrapped,
            commentary = result.commentary,
            rawResponseId = result.rawResponseId,
            threadState = result.threadState
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
        editOtherFile: ((EditFileRequest) -> EditFileResponse)? = null,
        workspaceSymbolSearch: ((WorkspaceSymbolSearch) -> WorkspaceSymbolSearchResult)? = null,
        vectorStoreIds: List<String> = emptyList(),
        onTextDelta: ((String) -> Unit)? = null
    ): HighLevelResult {
        info("chatError(): lang=${lang.name} error='${errorMessage.take(120)}' codeChars=${code.length}")

        val ctx = ToolExecutionContext(
            attachments = attachments,
            resolveAttachment = resolveAttachment,
            listDefinitions = listDefinitions,
            listFileContents = listFileContents,
            editOtherFile = editOtherFile,
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
            onTextDelta = onTextDelta
        )
        val unwrapped = lang.unwrap(result.text)

        validateNoMarkers(unwrapped)

        info("chatError(): done editedChars=${unwrapped.length} commentary=${result.commentary.size}")
        return HighLevelResult(
            editedCode = unwrapped,
            commentary = result.commentary,
            rawResponseId = result.rawResponseId,
            threadState = result.threadState
        )
    }
}
