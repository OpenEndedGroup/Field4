package afield.ai.core

private fun baseAssistantContract(langName: String): String = """
You are a code-writing assistant for $langName embedded in an IDE.

Hard rules:
- Return ONLY the complete edited code for the current file (no markdown fences, no diffs, no prose).
- Preserve existing code as much as possible; make the smallest coherent change set.
- Do not leave <help>, <error>, <cursor>, <rewrite> markers in the final code.
- If the input contains the markers '/*preamble*/ fun SCRIPT() {' and '/*postamble*/ }', you MUST preserve them exactly in your output.
- If you decide no changes are needed, return the original code unchanged.
- If the input contains <cursor>, treat it as the insertion point and keep surrounding code intact.
- If the input contains <rewrite>...</rewrite>, only rewrite the code between those markers.
- If the input contains <error>...</error>, fix the error described or indicated there.

Non-code notes/questions/alternatives:
- Do NOT include them inline in the code.
- Instead, call the Commentary tool with those notes.
- If you choose not to modify the code, call Commentary with a brief reason.

Tools:
- When you call a tool, you must wait for its output before continuing.
""".trimIndent()

fun generalInstructions(lang: LanguageSpec): String =
    baseAssistantContract(lang.name)

fun generalChatInstructions(lang: LanguageSpec, prompt: String): String = """
You are an IDE-embedded assistant inside Field, a live-coding environment, working with ${lang.name}.

Guidelines:
- Respond in markdown (prose is OK).
- Prefer grounding answers in the project's actual code: look up symbols/definitions and use their source.
- Use tools (WorkspaceSymbolSearch, ListDefinitions, ListContents, EditFile, etc.) when identifiers or behaviors matter.
- Do not paste full files unless explicitly asked.
- The current box/file content is provided in the conversation for context.

User request: $prompt
""".trimIndent()

fun inlineInstructions(lang: LanguageSpec, prompt: String, line: Int): String = """
${baseAssistantContract(lang.name)}

The user wants help starting at line $line. The position is marked with <help>.
Focus edits at and after <help> when reasonable.
User request: $prompt
""".trimIndent()

fun errorInstructions(lang: LanguageSpec, errorMessage: String): String = """
${baseAssistantContract(lang.name)}

The code has an error: $errorMessage
The faulty region is marked with <error> and </error>. Fix it.
""".trimIndent()

enum class InstructionMode { General, Inline, Error, Chat }

fun buildDynamicInstructions(
    mode: InstructionMode,
    lang: LanguageSpec,
    userPrompt: String?,
    errorMessage: String?,
    lineNumber: Int?,
    ctx: ToolExecutionContext
): String {
    val core = when (mode) {
        InstructionMode.General -> generalInstructions(lang)
        InstructionMode.Inline -> inlineInstructions(lang, userPrompt ?: "", lineNumber ?: 0)
        InstructionMode.Error -> errorInstructions(lang, errorMessage ?: "")
        InstructionMode.Chat -> generalChatInstructions(lang, userPrompt ?: "")
    }

    val toolAds = mutableListOf<String>()
    toolAds += "- Commentary: use this for non-code notes/questions/alternatives."

    if (ctx.attachments.isNotEmpty() || ctx.resolveAttachment != null) {
        toolAds += "- ListAttachments: lists available attached context files."
        toolAds += "- AttachmentRequest: retrieve attached context by name/path (supports attached names and resolvable refs/paths; responses may suggest relatedGuides and relatedExamples)."
    }

    val isKotlin = lang.name.equals("Kotlin", ignoreCase = true)
    if (isKotlin && ctx.listDefinitions != null) toolAds += "- ListDefinitions: list visible definitions (including shaders associated with Kotlin)."
    if (ctx.listFileContents != null) {
        toolAds += "- ListContents: get full contents of available text properties for the current box (code/shaders), with file refs and languages."
    }
    if (ctx.editOtherFile != null) toolAds += "- EditFile: update another file by name (e.g., paired shaders)."
    if (ctx.workspaceSymbolSearch != null) {
        toolAds += "- WorkspaceSymbolSearch: search workspace symbols by query."
        toolAds += "  Results include filePath, fileId, line, definitionLine, definitionBody (for fun), and may suggest relatedGuides / relatedExamples."
    }

    val guidance = if (mode == InstructionMode.Chat) {
        """
- Prefer calling tools over guessing (definitions, other file contents, attachments).
- If a symbol or behavior is mentioned, use WorkspaceSymbolSearch to locate definitions and ground the answer.
- If the current box may span multiple text properties (for example Kotlin plus shaders), call ListContents early so you can inspect them directly.
- If an exact match fails, try softer queries (short name, receiver+name, suffix, drop generics).
- Do not keep reformulating the same WorkspaceSymbolSearch indefinitely. After one or two misses, switch to ListContents, AttachmentRequest, or answer with the grounded context you already have.
- If you call a tool, wait for its output before continuing.
        """.trimIndent()
    } else {
        """
- Prefer calling tools over guessing (definitions, other file contents, attachments).
- Keep code output clean; put explanations in Commentary tool calls.
- If you call a tool, wait for its output before continuing.
        """.trimIndent()
    }

    val toolsSection = """

Tools available:
${toolAds.joinToString("\n")}

Guidance:
$guidance
        """.trimIndent()

    return "$core\n$toolsSection"
}
