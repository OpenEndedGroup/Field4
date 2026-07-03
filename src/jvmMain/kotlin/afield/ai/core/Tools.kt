package afield.ai.core
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseFunctionToolCall
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Commentary tool: model uses this for non-code notes/questions/alternatives.
 * You collect these separately and keep code output clean.
 */
@JsonClassDescription("Assistant commentary or design notes about the code.")
data class Commentary(val text: String)

/**
 * Represents a file/language/code triple that can be attached to a request.
 * Attachments are retrieved via tools rather than stuffed into the prompt body.
 */
data class ContextAttachment(
    val fileName: String,
    val language: String,
    val code: String,
    val description: String = ""
)

/** Tool: list available attachment filenames (+ languages) for this request. */
@JsonClassDescription("List all attached context files available for this request.")
data class ListAttachmentsRequest(val unused: Int = 0)

data class ListAttachmentsResult(val attachments: List<AttachmentInfo>)
data class AttachmentInfo(val file: String, val language: String, val description: String = "")

/** Tool: retrieve an attached file's contents by name. */
@JsonClassDescription("Retrieve the code content of an attached file by its name. Responses may also include relatedGuides and relatedExamples paths.")
data class AttachmentRequest(val file: String)

data class AttachmentResponse(
    val file: String,
    val language: String,
    val content: String,
    val relatedGuides: List<String> = emptyList(),
    val relatedExamples: List<String> = emptyList()
)

/** Kotlin-only tools -------------------------------------------------------- */

/** Tool: list all definitions visible from the current Kotlin file (incl shaders). */
@JsonClassDescription("List all definitions visible from the current Kotlin file, including associated shaders.")
data class ListDefinitionsRequest(val unused: Int = 0)

data class ListDefinitionsResult(val definitions: List<DefinitionInfo>)
data class DefinitionInfo(val name: String, val type: String, val fileRef: String, val language: String)

/** Tool: get full contents of text properties for the current box/file context. */
@JsonClassDescription("Return full contents for available text properties (code and shaders) in the current Field box and graph-relevant boxes, with file refs, languages, box names, relation labels, and preamble hints. Preamble hints distinguish inherited Box properties from shader-generated Mesh/Module binding helpers.")
data class ListContentsRequest(val unused: Int = 0)

data class ListContentsResult(
    val fileRef: String? = null,
    val content: String? = null,
    val language: String? = null,
    val contents: List<ListContentEntry> = emptyList(),
    val preambleHints: List<PreamblePropertyHint> = emptyList()
)

data class PreamblePropertyHint(
    val property: String,
    val receiver: String,
    val delegate: String,
    val type: String = "",
    val boxUid: String = "",
    val boxName: String = "",
    val relation: String = "current",
    val graphRelevant: Boolean = false
)

data class ListContentEntry(
    val fileRef: String,
    val property: String,
    val language: String,
    val content: String,
    val boxUid: String = "",
    val boxName: String = "",
    val relation: String = "current"
)

@JsonClassDescription("List Kotlin REPL context variables currently retained by a Field box's KotlinInterface/KotlinInterface2 without evaluating code. Target may be blank for the current box, a box uid/name, or an afield://... ref. This lists retained script variables, not Field box properties. In later EvaluateKotlinExpression calls, refer to these variables by their names; refer to the current/target box as thisBox, never as _ or _.foo.")
data class ListKotlinContextVariablesRequest(
    val target: String = "",
    val maxValueChars: Int = 300
)

data class KotlinContextVariableInfo(
    val name: String,
    val declaration: String,
    val declaredType: String,
    val runtimeType: String,
    val mutable: Boolean,
    val valuePreview: String,
    val primitiveValue: Any? = null,
    val primitiveKind: String = ""
)

data class ListKotlinContextVariablesResult(
    val success: Boolean,
    val target: String,
    val boxUid: String,
    val boxName: String,
    val generation: Int,
    val message: String,
    val variables: List<KotlinContextVariableInfo> = emptyList()
)

@JsonClassDescription("Evaluate a Kotlin expression in a Field box's existing KotlinInterface/KotlinInterface2 context. Target may be blank for the current box, a box uid/name, or an afield://... ref. Prefer read-only expressions; this runs against live runtime objects. The current/target Field box is thisBox. Do not use _, _.foo, or bare box as current-box syntax. Use thisBox.getPropertyRaw(\"name\") / thisBox.setPropertyRaw(\"name\", value) for raw box properties, and use box(\"uid\", root = thisBox.inside) when intentionally resolving or creating another box in the same document.")
data class EvaluateKotlinExpressionRequest(
    val target: String = "",
    val expression: String,
    val renderHtml: Boolean = true,
    val maxChars: Int = 12000
)

data class EvaluateKotlinExpressionResult(
    val success: Boolean,
    val target: String,
    val boxUid: String,
    val boxName: String,
    val expression: String,
    val valueKind: String,
    val type: String = "",
    val runtimeType: String = "",
    val primitiveValue: Any? = null,
    val primitiveKind: String = "",
    val stringValue: String = "",
    val html: String = "",
    val diagnostics: List<CompileDiagnostic> = emptyList(),
    val message: String = ""
)

/** Tool: edit another file by name (your integration decides how to apply this). */
@JsonClassDescription("Edit another file by specifying its name and new content.")
data class EditFileRequest(val file: String, val newContent: String)

data class EditFileResponse(val success: Boolean, val message: String? = null)

/** Agentic verification tools --------------------------------------------- */

@JsonClassDescription("Compile Kotlin for the current Field box or an afield://.../code file ref. Call this after editing Kotlin and before finishing the turn; fix errors and rerun until success.")
data class CompileKotlinRequest(
    val file: String = "",
    val source: String? = null,
    val runSetupBlocks: Boolean = true
)

data class CompileDiagnostic(
    val severity: String,
    val message: String,
    val line: Int = -1,
    val column: Int = -1,
    val endLine: Int = -1,
    val endColumn: Int = -1,
    val code: String? = null
)

data class SetupPropertyChange(
    val property: String,
    val changed: Boolean,
    val cacheChanged: Boolean,
    val beforeChars: Int,
    val afterChars: Int
)

data class CompileKotlinResult(
    val success: Boolean,
    val file: String,
    val message: String,
    val diagnostics: List<CompileDiagnostic> = emptyList(),
    val setupChanges: List<SetupPropertyChange> = emptyList()
)

@JsonClassDescription("Reload shader modules for the current Field box or target box/file ref. Call this after editing GLSL/Slang shader properties and before finishing the turn; fix errors and rerun until success.")
data class ReloadShadersRequest(
    val target: String = "",
    val mode: String = "auto",
    val visualCheck: Boolean = true
)

data class ShaderReloadError(
    val property: String,
    val line: Int = -1,
    val message: String
)

data class ReloadShadersResult(
    val success: Boolean,
    val target: String,
    val mode: String,
    val message: String,
    val reloaded: List<String> = emptyList(),
    val errors: List<ShaderReloadError> = emptyList(),
    val visualCheckAttached: Boolean = false,
    val visualCheckMessage: String = "",
    @get:JsonIgnore
    val visualDataUrl: String = ""
)

/** Macro transcript tools --------------------------------------------------- */

@JsonClassDescription("List saved operation-transcript macros available to the current Field document. Use this before running a macro when the user names one imprecisely or asks what macros exist.")
data class MacroListRequest(val unused: Int = 0)

data class MacroListResult(
    val success: Boolean,
    val message: String,
    val macros: List<String> = emptyList()
)

@JsonClassDescription("Read a saved operation-transcript macro YAML file by name. Read before editing a macro, and usually read before running when target mapping or expected effects matter.")
data class MacroReadRequest(val name: String)

data class MacroReadResult(
    val success: Boolean,
    val message: String,
    val name: String,
    val content: String = "",
    val operationCount: Int = 0
)

@JsonClassDescription("Create or replace a saved operation-transcript macro YAML file in the current Field document. Content should be YAML with an operations list. Use this to save a user-requested new/edited macro; do not rewrite an existing macro just to run it.")
data class MacroWriteRequest(
    val name: String,
    val content: String
)

data class MacroWriteResult(
    val success: Boolean,
    val message: String,
    val name: String,
    val operationCount: Int = 0
)

@JsonClassDescription("Run or undo a saved operation-transcript macro. Use direction=do to replay it and direction=undo to run it backwards. targetBoxes may contain box uids or names; when set, the macro's first recorded source box is remapped to each target box, which is the normal way to do a recorded macro to the current/selected box. Empty targetBoxes replays the recorded uids literally. Inspect applied/skipped/errors; if applied=0 or errors are returned, do not repeat the identical request. If a normal run reports operation errors in chat, Field will attempt an automatic undo and return the undo result; inspect that result and ask before retrying.")
data class MacroRunRequest(
    val name: String,
    val direction: String = "do",
    val targetBoxes: List<String> = emptyList(),
    val strict: Boolean = false
)

data class MacroRunResult(
    val success: Boolean,
    val message: String,
    val name: String,
    val direction: String,
    val targetBoxes: List<String> = emptyList(),
    val applied: Int = 0,
    val skipped: Int = 0,
    val errors: List<String> = emptyList()
)

/** Long-running task tools -------------------------------------------------- */

@JsonClassDescription("Read the persistent scratchpad for this chat. Use it to recover durable notes, decisions, constraints, and handoff context from earlier turns.")
data class ScratchpadReadRequest(val unused: Int = 0)

@JsonClassDescription("Update the persistent scratchpad for this chat. Use append to add durable notes, replace to rewrite the whole scratchpad, or clear to erase it.")
data class ScratchpadUpdateRequest(
    val operation: String = "append",
    val text: String = ""
)

data class ScratchpadResult(
    val success: Boolean,
    val message: String,
    val text: String = "",
    val charCount: Int = 0
)

@JsonClassDescription("Read the persistent checklist for this chat. Use it to track long-running tasks across turns.")
data class ChecklistReadRequest(val unused: Int = 0)

@JsonClassDescription("Update the persistent checklist for this chat. Use operation add, update, complete, reopen, remove, replace, or clear. For update/complete/reopen/remove, itemId identifies the item.")
data class ChecklistUpdateRequest(
    val operation: String,
    val itemId: String = "",
    val text: String = "",
    val status: String = "pending",
    val items: List<ChecklistItemInput> = emptyList()
)

data class ChecklistItemInput(
    val text: String,
    val status: String = "pending",
    val id: String = ""
)

data class ChecklistItem(
    val id: String,
    val text: String,
    val status: String = "pending"
)

data class ChecklistResult(
    val success: Boolean,
    val message: String,
    val items: List<ChecklistItem> = emptyList()
)

class AgentMemoryState {
    private val lock = ReentrantReadWriteLock()
    private var scratchpad: String = ""
    private val checklist = mutableListOf<ChecklistItem>()
    private var nextChecklistId = 1

    fun readScratchpad(): ScratchpadResult = lock.read {
        ScratchpadResult(
            success = true,
            message = if (scratchpad.isBlank()) "Scratchpad is empty." else "Scratchpad loaded.",
            text = scratchpad,
            charCount = scratchpad.length
        )
    }

    fun updateScratchpad(request: ScratchpadUpdateRequest): ScratchpadResult = lock.write {
        when (request.operation.trim().lowercase()) {
            "append" -> {
                val addition = request.text.trim()
                if (addition.isBlank()) {
                    return@write ScratchpadResult(false, "Append requires non-empty text.", scratchpad, scratchpad.length)
                }
                scratchpad = buildString {
                    append(scratchpad.trimEnd())
                    if (isNotEmpty()) append("\n\n")
                    append(addition)
                }
                ScratchpadResult(true, "Scratchpad appended.", scratchpad, scratchpad.length)
            }
            "replace", "set" -> {
                scratchpad = request.text.trim()
                ScratchpadResult(true, "Scratchpad replaced.", scratchpad, scratchpad.length)
            }
            "clear" -> {
                scratchpad = ""
                ScratchpadResult(true, "Scratchpad cleared.", scratchpad, 0)
            }
            else -> ScratchpadResult(
                false,
                "Unknown scratchpad operation '${request.operation}'. Use append, replace, or clear.",
                scratchpad,
                scratchpad.length
            )
        }
    }

    fun readChecklist(): ChecklistResult = lock.read {
        ChecklistResult(
            success = true,
            message = if (checklist.isEmpty()) "Checklist is empty." else "Checklist loaded.",
            items = checklist.toList()
        )
    }

    fun updateChecklist(request: ChecklistUpdateRequest): ChecklistResult = lock.write {
        fun normalizedStatus(raw: String): String = when (raw.trim().lowercase()) {
            "done", "complete", "completed", "checked" -> "done"
            "in_progress", "in-progress", "active", "doing" -> "in_progress"
            else -> "pending"
        }

        fun findIndex(id: String): Int {
            val trimmed = id.trim()
            if (trimmed.isBlank()) return -1
            checklist.indexOfFirst { it.id == trimmed }.takeIf { it >= 0 }?.let { return it }
            val numeric = trimmed.toIntOrNull()
            if (numeric != null && numeric in 1..checklist.size) return numeric - 1
            return -1
        }

        fun nextId(): String = (nextChecklistId++).toString()

        val op = request.operation.trim().lowercase()
        val result = when (op) {
            "add" -> {
                val text = request.text.trim()
                if (text.isBlank()) {
                    ChecklistResult(false, "Add requires non-empty text.", checklist.toList())
                } else {
                    checklist += ChecklistItem(nextId(), text, normalizedStatus(request.status))
                    ChecklistResult(true, "Checklist item added.", checklist.toList())
                }
            }
            "update", "set" -> {
                val idx = findIndex(request.itemId)
                val text = request.text.trim()
                if (idx < 0) {
                    ChecklistResult(false, "Unknown checklist item '${request.itemId}'.", checklist.toList())
                } else if (text.isBlank()) {
                    ChecklistResult(false, "Update requires non-empty text.", checklist.toList())
                } else {
                    val old = checklist[idx]
                    val status = if (request.status.isBlank()) old.status else normalizedStatus(request.status)
                    checklist[idx] = old.copy(text = text, status = status)
                    ChecklistResult(true, "Checklist item updated.", checklist.toList())
                }
            }
            "complete", "done" -> {
                val idx = findIndex(request.itemId)
                if (idx < 0) {
                    ChecklistResult(false, "Unknown checklist item '${request.itemId}'.", checklist.toList())
                } else {
                    checklist[idx] = checklist[idx].copy(status = "done")
                    ChecklistResult(true, "Checklist item completed.", checklist.toList())
                }
            }
            "reopen", "pending" -> {
                val idx = findIndex(request.itemId)
                if (idx < 0) {
                    ChecklistResult(false, "Unknown checklist item '${request.itemId}'.", checklist.toList())
                } else {
                    checklist[idx] = checklist[idx].copy(status = "pending")
                    ChecklistResult(true, "Checklist item reopened.", checklist.toList())
                }
            }
            "remove", "delete" -> {
                val idx = findIndex(request.itemId)
                if (idx < 0) {
                    ChecklistResult(false, "Unknown checklist item '${request.itemId}'.", checklist.toList())
                } else {
                    checklist.removeAt(idx)
                    ChecklistResult(true, "Checklist item removed.", checklist.toList())
                }
            }
            "replace" -> {
                checklist.clear()
                request.items.forEach { item ->
                    val text = item.text.trim()
                    if (text.isNotBlank()) {
                        val id = item.id.trim().ifBlank { nextId() }
                        checklist += ChecklistItem(id, text, normalizedStatus(item.status))
                    }
                }
                ChecklistResult(true, "Checklist replaced.", checklist.toList())
            }
            "clear" -> {
                checklist.clear()
                ChecklistResult(true, "Checklist cleared.", emptyList())
            }
            else -> ChecklistResult(
                false,
                "Unknown checklist operation '${request.operation}'. Use add, update, complete, reopen, remove, replace, or clear.",
                checklist.toList()
            )
        }
        result
    }

    fun checklistMarkdown(): String = lock.read {
        if (checklist.isEmpty()) return@read ""
        buildString {
            appendLine("**Checklist**")
            checklist.forEach { item ->
                append(if (item.status == "done") "- [x] " else "- [ ] ")
                append(item.text)
                append(" ")
                append("`#${item.id}`")
                appendLine()
            }
        }.trimEnd()
    }
}

/** Example tool (optional) ------------------------------------------------- */
@JsonClassDescription("Looks up symbols in the IDE workspace index. Results may include relatedGuides and relatedExamples paths.")
data class WorkspaceSymbolSearch(val query: String, val limit: Int = 25)

data class WorkspaceSymbolSearchResult(val matches: List<WorkspaceSymbolMatch>)
data class WorkspaceSymbolMatch(
    val symbol: String,
    val kind: String,
    val locationHint: String,
    val filePath: String? = null,
    val fileId: String? = null,
    val line: Int? = null,
    val definitionLine: String? = null,
    val definitionBody: String? = null,
    val relatedGuides: List<String> = emptyList(),
    val relatedExamples: List<String> = emptyList()
)

/** CLI example tools -------------------------------------------------------- */

@JsonClassDescription("Return the current UTC time in the requested format.")
data class GetUtcTime(val format: String = "yyyy-MM-dd HH:mm:ss")

@JsonClassDescription("Store a short note under a key.")
data class Remember(val key: String, val value: String)

@JsonClassDescription("Recall a stored note by key.")
data class Recall(val key: String)

data class ToolExecutionContext(
    val attachments: List<ContextAttachment> = emptyList(),
    val resolveAttachment: ((String) -> AttachmentResponse?)? = null,

    // Kotlin-only providers (optional)
    val listDefinitions: (() -> ListDefinitionsResult)? = null,
    val listFileContents: (() -> ListContentsResult)? = null,
    val listKotlinContextVariables: ((ListKotlinContextVariablesRequest) -> ListKotlinContextVariablesResult)? = null,
    val evaluateKotlinExpression: ((EvaluateKotlinExpressionRequest) -> EvaluateKotlinExpressionResult)? = null,
    val editOtherFile: ((EditFileRequest) -> EditFileResponse)? = null,
    val compileKotlin: ((CompileKotlinRequest) -> CompileKotlinResult)? = null,
    val reloadShaders: ((ReloadShadersRequest) -> ReloadShadersResult)? = null,
    val listMacros: ((MacroListRequest) -> MacroListResult)? = null,
    val readMacro: ((MacroReadRequest) -> MacroReadResult)? = null,
    val writeMacro: ((MacroWriteRequest) -> MacroWriteResult)? = null,
    val runMacro: ((MacroRunRequest) -> MacroRunResult)? = null,

    val agentMemory: AgentMemoryState? = null,

    // Optional extra tools
    val workspaceSymbolSearch: ((WorkspaceSymbolSearch) -> WorkspaceSymbolSearchResult)? = null,
    val visualVerificationEnabled: Boolean = false
)

fun virtualFilesToAttachments(files: List<afield.ai.VirtualTextFile>): List<ContextAttachment> =
    files.map { ContextAttachment(it.ref.fileName, it.ref.language, it.content) }

internal class ToolRegistry {
    private data class Handler<T : Any>(
        val cls: Class<T>,
        val fn: (ToolExecutionContext, T) -> Any
    )

    private val handlers = ConcurrentHashMap<String, Handler<*>>()

    fun <T : Any> register(toolClass: Class<T>, handler: (ToolExecutionContext, T) -> Any) {
        handlers[toolClass.simpleName] = Handler(toolClass, handler)
    }

    fun addAllTo(builder: ResponseCreateParams.Builder) {
        handlers.values.forEach { h ->
            @Suppress("UNCHECKED_CAST")
            builder.addTool(h.cls as Class<Any>)
        }
    }

    fun execute(ctx: ToolExecutionContext, call: ResponseFunctionToolCall): Any {
        val h = handlers[call.name()]
            ?: throw IllegalArgumentException("No tool registered for: ${call.name()}")
        @Suppress("UNCHECKED_CAST")
        val handler = h as Handler<Any>
        val args: Any = call.arguments(handler.cls)
        return handler.fn(ctx, args)
    }
}

fun validateNoMarkers(code: String) {
    val bad = listOf("<help>", "<error>", "</error>", "<cursor>", "<rewrite>", "</rewrite>")
    val hit = bad.firstOrNull { code.contains(it) }
    if (hit != null) throw MalformedAI("Model output still contained marker '$hit'. Refusing to apply.")
}
