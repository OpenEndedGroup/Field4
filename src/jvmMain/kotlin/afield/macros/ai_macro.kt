package afield.macros

import afield.*
import afield.ai.core.DefinitionInfo
import afield.ai.core.ListDefinitionsResult
import afield.ai.core.OpenAiIdeCodexBridge
import afield.ai.core.Ai4ModelDefaults
import afield.ai.core.StdoutLog
import afield.ai.core.ToolExecutionContext
import afield.logger.lprintln
import afield.macros.MacroRegistry.MacroExpansionProblem
import afield.macros.MacroRegistry.callRewriteWithContext
import com.openai.models.ReasoningEffort
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

// -- Metadata format for AI-generated blocks in attic --
//
// // @ai-gen: __foo | sig: (a: Double, v: Vec3): FLine | prompt: a random wiggly FLine...
// fun __foo(a: Double, v: Vec3): FLine {
//     ...
// }
// // @ai-gen-end: __foo

private val aiGenHeaderRegex = Regex("""^// @ai-gen:\s*(\S+)\s*\|\s*sig:\s*(.*?)\s*\|\s*prompt:\s*(.*)$""")
private val aiGenEndRegex = Regex("""^// @ai-gen-end:\s*(\S+)\s*$""")

data class AtticBlock(
    val functionName: String,
    val signature: String,
    val prompt: String,
    val body: String
)

fun parseAtticBlock(attic: String, functionName: String): AtticBlock? {
    val lines = attic.lines()
    var i = 0
    while (i < lines.size) {
        val match = aiGenHeaderRegex.matchEntire(lines[i].trim())
        if (match != null && match.groupValues[1] == functionName) {
            val sig = match.groupValues[2]
            val prompt = match.groupValues[3]
            val bodyLines = mutableListOf<String>()
            i++
            while (i < lines.size) {
                val endMatch = aiGenEndRegex.matchEntire(lines[i].trim())
                if (endMatch != null && endMatch.groupValues[1] == functionName) break
                bodyLines.add(lines[i])
                i++
            }
            return AtticBlock(functionName, sig, prompt, bodyLines.joinToString("\n"))
        }
        i++
    }
    return null
}

fun removeAtticBlock(attic: String, functionName: String): String {
    val lines = attic.lines()
    val result = mutableListOf<String>()
    var i = 0
    while (i < lines.size) {
        val match = aiGenHeaderRegex.matchEntire(lines[i].trim())
        if (match != null && match.groupValues[1] == functionName) {
            // skip until end marker
            i++
            while (i < lines.size) {
                val endMatch = aiGenEndRegex.matchEntire(lines[i].trim())
                if (endMatch != null && endMatch.groupValues[1] == functionName) {
                    i++
                    break
                }
                i++
            }
            continue
        }
        result.add(lines[i])
        i++
    }
    return result.joinToString("\n").trimEnd()
}

fun appendAtticBlock(attic: String, block: AtticBlock): String {
    val header = "// @ai-gen: ${block.functionName} | sig: ${block.signature} | prompt: ${block.prompt}"
    val footer = "// @ai-gen-end: ${block.functionName}"
    val newBlock = "$header\n${block.body}\n$footer"
    return if (attic.isBlank()) newBlock else "${attic.trimEnd()}\n\n$newBlock"
}

fun extractParamNames(parameterList: String): String {
    // "(a: Double, v: Vec3)" -> "a, v"
    val inner = parameterList.trim().removeSurrounding("(", ")")
    if (inner.isBlank()) return ""
    return inner.split(",").joinToString(", ") { param ->
        param.trim().substringBefore(":").trim()
    }
}

fun stripAiGenMetadata(attic: String): String {
    return attic.lines().filterNot { line ->
        val trimmed = line.trim()
        trimmed.startsWith("// @ai-gen:") || trimmed.startsWith("// @ai-gen-end:")
    }.joinToString("\n")
}

// -- Lazy AI bridge singleton --

object AiMacroBridge {
    val bridge: OpenAiIdeCodexBridge by lazy {
        lprintln("info,ai.macro", "Initializing AI macro bridge")
        OpenAiIdeCodexBridge(
            model = System.getenv("AFIELD_AI_MACRO_MODEL") ?: Ai4ModelDefaults.flagshipModel(),
            log = StdoutLog,
            reasoningEffort = ReasoningEffort.MEDIUM
        )
    }

    private val threadStates = ConcurrentHashMap<String, OpenAiIdeCodexBridge.ThreadState>()

    fun getOrCreateThread(boxId: String, contextCode: String): OpenAiIdeCodexBridge.ThreadState {
        return threadStates.getOrPut(boxId) {
            bridge.startThread(
                fileKey = "attic:$boxId",
                fileText = contextCode,
                instructions = """
You are a code-generation assistant embedded in Field4, a Kotlin live-coding environment.
Your job is to write Kotlin function implementations given a function signature and a natural-language description.

Rules:
- Return ONLY the function definition. No markdown fences, no explanation, no imports.
- The function name will be provided (it will start with double underscores, e.g. __foo).
- Match the exact parameter types and return type specified.
- The function body should fulfill the natural-language description as faithfully as possible.
- You may use standard Kotlin stdlib, JOML (org.joml.*), and Field4 types (FLine, Vec3, etc.) that are available in context.
- If other functions or declarations are provided as context, you may call them.
- Keep the implementation concise and correct.
                """.trimIndent()
            )
        }
    }

    fun clearThread(boxId: String) {
        threadStates.remove(boxId)
    }
}

// -- The ai() macro implementation --

fun Box.macro_ai(args: List<String>, containingFunction: ContainingFunction?): Insert? {
    if (containingFunction == null) {
        throw MacroExpansionProblem(
            "ai() must be called inside a function with explicit parameter types and return type, e.g.:\n" +
            "  fun foo(a: Double): Double { ai(\"description\") }"
        )
    }

    // Parse the prompt from args — args looks like: ["(\"some prompt text\")"]
    val rawArgs = args.joinToString("")
    val promptMatch = Regex("""^\(\s*"(.*)"\s*\)$""", RegexOption.DOT_MATCHES_ALL).find(rawArgs)
        ?: throw MacroExpansionProblem("ai() expects a single string argument, e.g. ai(\"description of what this function does\")")
    val prompt = promptMatch.groupValues[1]

    val funcName = containingFunction.name
    val implName = "__$funcName"
    val paramList = containingFunction.parameterList
    val returnType = containingFunction.returnType
    val signature = "$paramList: $returnType"
    val paramNames = extractParamNames(paramList)
    val forwardCall = if (paramNames.isBlank()) "return $implName()" else "return $implName($paramNames)"

    // Check attic for existing matching block
    val currentAttic = this.attic
    val existing = parseAtticBlock(currentAttic, implName)

    if (existing != null && existing.signature == signature && existing.prompt == prompt) {
        // Cache hit — signature and prompt match
        lprintln("info,ai.macro", "ai() cache hit for $implName in box ${this.id}")
        safeNotify("ai($funcName): using cached implementation")
        return Insert(here = forwardCall)
    }

    // Remove stale block if it exists
    val cleanedAttic = if (existing != null) {
        lprintln("info,ai.macro", "ai() removing stale block for $implName (sig or prompt changed)")
        removeAtticBlock(currentAttic, implName)
    } else {
        currentAttic
    }

    // Build AI context
    val atticContext = stripAiGenMetadata(cleanedAttic).trim()

    // Get preview-expanded code with this ai() macro skipped and the containing function removed
    val rawCode = this.code
    val codeWithoutFunction = removeFunctionByName(rawCode, funcName)
    val expandedCode = try {
        this.ki.applyMacros(this, codeWithoutFunction, skipMacros = setOf("ai"), previewOnly = true)
    } catch (e: Exception) {
        lprintln("warning,ai.macro", "Failed to preview-expand code for AI context: ${e.message}")
        codeWithoutFunction
    }

    // Build the prompt for AI
    val userPrompt = buildString {
        appendLine("Write a Kotlin function with the following specification:")
        appendLine()
        appendLine("Function name: $implName")
        appendLine("Parameters: $paramList")
        appendLine("Return type: $returnType")
        appendLine("Description: $prompt")
        appendLine()
        if (atticContext.isNotBlank()) {
            appendLine("Here are other definitions already available in this scope (attic):")
            appendLine("```")
            appendLine(atticContext)
            appendLine("```")
            appendLine()
        }
        if (expandedCode.isNotBlank()) {
            appendLine("Here is the rest of the code in this box for context:")
            appendLine("```")
            appendLine(expandedCode)
            appendLine("```")
            appendLine()
        }
        appendLine("Write ONLY the function definition for $implName. No markdown, no explanation.")
    }

    // Notify the user that generation is in progress
    lprintln("info,ai.macro", "ai() generating $implName for box ${this.id}: \"$prompt\"")
    safeNotify("ai($funcName): generating via AI — \"$prompt\"")

    val contextForThread = buildString {
        if (atticContext.isNotBlank()) {
            appendLine(atticContext)
            appendLine()
        }
        appendLine(expandedCode)
    }

    val maxAttempts = 3
    var currentPrompt = userPrompt
    var generatedCode: String? = null

    for (attempt in 1..maxAttempts) {
        // Generate code from AI
        safeNotify("ai($funcName): calling AI${if (attempt > 1) " (retry $attempt/$maxAttempts)" else ""}...")
        val candidate = try {
            val thread = AiMacroBridge.getOrCreateThread(this.id, contextForThread)

            // Build definitions from this box's KotlinInterface context
            val box = this
            val ctx = ToolExecutionContext(
                listDefinitions = {
                    val defs = mutableListOf<DefinitionInfo>()
                    try {
                        box.ki.context.vars.values.mapTo(defs) { v ->
                            DefinitionInfo(name = v.name, type = "variable", fileRef = "box:${box.id}", language = "kotlin")
                        }
                        box.ki.context.functions.values.mapTo(defs) { f ->
                            DefinitionInfo(name = f.toString(true), type = "function", fileRef = "box:${box.id}", language = "kotlin")
                        }
                    } catch (e: Exception) {
                        lprintln("warning,ai.macro", "Failed to gather definitions for ListDefinitions: ${e.message}")
                    }
                    ListDefinitionsResult(definitions = defs)
                }
            )

            val result = AiMacroBridge.bridge.send(
                state = thread,
                userPrompt = currentPrompt,
                ctx = ctx,
                maxOutputTokens = 4096
            )
            result.text.trim()
                .removePrefix("```kotlin").removePrefix("```")
                .removeSuffix("```")
                .trim()
        } catch (e: Exception) {
            lprintln("error,ai.macro", "AI generation failed for $implName (attempt $attempt): ${e.message}")
            if (attempt == maxAttempts) {
                safeNotify("ai($funcName): AI generation failed after $maxAttempts attempts")
                throw MacroExpansionProblem("AI generation failed for $implName: ${e.message}")
            }
            continue
        }

        // Validate that the generated code looks like a function
        if (!candidate.contains("fun $implName")) {
            lprintln("error,ai.macro", "AI did not generate expected function $implName (attempt $attempt). Got:\n$candidate")
            if (attempt == maxAttempts) {
                safeNotify("ai($funcName): AI failed to produce valid function after $maxAttempts attempts")
                throw MacroExpansionProblem("AI did not generate expected function $implName. Response:\n$candidate")
            }
            currentPrompt = buildString {
                appendLine("Your previous response did not contain the expected function definition 'fun $implName'.")
                appendLine("Here is what you returned:")
                appendLine("```")
                appendLine(candidate)
                appendLine("```")
                appendLine()
                appendLine("Please try again. Write ONLY the function definition for $implName with the exact signature: fun $implName$paramList: $returnType")
            }
            continue
        }

        // Compile-check the generated code
        safeNotify("ai($funcName): compile-checking generated code...")
        val compileErrors = compileCheckGenerated(candidate, cleanedAttic, expandedCode)

        if (compileErrors == null) {
            // Compilation succeeded
            lprintln("info,ai.macro", "ai() compile check passed for $implName on attempt $attempt")
            safeNotify("ai($funcName): compile check passed" + if (attempt > 1) " (after $attempt attempts)" else "")
            generatedCode = candidate
            break
        } else {
            // Compilation failed — show the first error in the notification
            val firstError = compileErrors.lines().firstOrNull() ?: compileErrors
            lprintln("warning,ai.macro", "ai() compile check failed for $implName (attempt $attempt):\n$compileErrors")
            safeNotify("ai($funcName): compile error (attempt $attempt): $firstError")

            if (attempt == maxAttempts) {
                // Last attempt — use the code anyway but warn the user
                lprintln("warning,ai.macro", "ai() giving up on compile-clean code for $implName after $maxAttempts attempts, using last result")
                safeNotify("ai($funcName): could not get clean compile after $maxAttempts attempts — using last result")
                generatedCode = candidate
                break
            }

            // Build a fix-it prompt for the next iteration
            currentPrompt = buildString {
                appendLine("The function you generated has Kotlin compiler errors:")
                appendLine()
                appendLine("```")
                appendLine(compileErrors)
                appendLine("```")
                appendLine()
                appendLine("Here is the code you wrote:")
                appendLine("```kotlin")
                appendLine(candidate)
                appendLine("```")
                appendLine()
                appendLine("Please fix these errors and return the corrected function definition for $implName.")
                appendLine("Return ONLY the function definition, no markdown fences, no explanation.")
            }
        }
    }

    if (generatedCode == null) {
        safeNotify("ai($funcName): generation failed")
        throw MacroExpansionProblem("AI generation failed for $implName after $maxAttempts attempts")
    }

    // Build the attic block and patch attic
    val block = AtticBlock(
        functionName = implName,
        signature = signature,
        prompt = prompt,
        body = generatedCode
    )
    val newAttic = appendAtticBlock(cleanedAttic, block)

    // Update attic property (this triggers resync to frontend)
    this.setPropertyRaw("attic", newAttic, doResync = true)
    // _attic is intentionally NOT updated here so checkAtticAndExecute detects the change and executes the new code

    lprintln("info,ai.macro", "ai() generated $implName successfully, attic updated")
    safeNotify("ai($funcName): done ✓")

    return Insert(here = forwardCall)
}

private fun safeNotify(text: String) {
    try {
        _send(Message("notify").payload(Notify(text, "AI")))
    } catch (e: Exception) {
        lprintln("warning,ai.macro", "safeNotify failed: ${e.message}")
    }
}

/**
 * Compile-check generated code by combining it with existing attic functions,
 * then trying to compile as a Kotlin snippet via the box's KotlinInterface.
 * Only checks the attic code — user code in `code` is not included since it
 * may legitimately be incomplete or non-compiling.
 * Returns null on success, or a string of error messages on failure.
 */
private fun Box.compileCheckGenerated(candidate: String, cleanedAttic: String, expandedCode: String): String? {
    val testCode = buildString {
        val strippedAttic = stripAiGenMetadata(cleanedAttic).trim()
        if (strippedAttic.isNotBlank()) {
            appendLine(strippedAttic)
            appendLine()
        }
        appendLine(candidate)
    }

    val errors = mutableListOf<String>()
    this.ki.compileSnippetAndWait(
        testCode,
        implicits = listOf(this),
        onCompileError = { reports ->
            for (report in reports) {
                if (report.severity == kotlin.script.experimental.api.ScriptDiagnostic.Severity.ERROR) {
                    errors.add(report.message + (report.location?.let { " (line ${it.start.line})" } ?: ""))
                }
            }
        }
    )

    return if (errors.isEmpty()) null else errors.joinToString("\n")
}

/**
 * Remove a top-level function by name from Kotlin source code.
 * Simple heuristic: finds `fun <name>(` and removes until matching closing brace.
 */
private fun removeFunctionByName(code: String, functionName: String): String {
    val pattern = Regex("""(?m)^([ \t]*)fun\s+${Regex.escape(functionName)}\s*\(""")
    val match = pattern.find(code) ?: return code

    val startIndex = match.range.first
    // Find the opening brace
    var braceIndex = code.indexOf('{', match.range.last)
    if (braceIndex == -1) return code

    // Count braces to find the matching close
    var depth = 0
    var i = braceIndex
    while (i < code.length) {
        when (code[i]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) {
                    // Remove from startIndex to i (inclusive), plus any trailing newline
                    val endIndex = if (i + 1 < code.length && code[i + 1] == '\n') i + 2 else i + 1
                    return code.removeRange(startIndex, endIndex)
                }
            }
        }
        i++
    }
    return code
}

// -- Macro registration --

@RunsAt(macroRegistration)
fun registerAiMacro() {
    callRewriteWithContext["ai"] = { box, _, args, containingFunction ->
        box.macro_ai(args, containingFunction)
    }
}
