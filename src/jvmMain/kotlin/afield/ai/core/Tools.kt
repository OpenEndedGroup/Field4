package afield.ai.core
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseFunctionToolCall
import java.util.concurrent.ConcurrentHashMap

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
    val editOtherFile: ((EditFileRequest) -> EditFileResponse)? = null,
    val compileKotlin: ((CompileKotlinRequest) -> CompileKotlinResult)? = null,
    val reloadShaders: ((ReloadShadersRequest) -> ReloadShadersResult)? = null,

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
