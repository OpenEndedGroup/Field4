@file:Suppress("unused", "MemberVisibilityCanBePrivate")

/**
 * Re-exports the AI4 assistant's public types and helpers under one package, `afield.ai.compat`, so
 * code that imports from this one package keeps compiling.
 *
 * Every declaration here is a `typealias` or a one-line forwarding `val` or `fun` into the package
 * that now owns it: logging sinks from `afield.ai.diagnostics`, language specs such as
 * [KOTLIN_SCRIPT] and the GLSL and Slang stage specs from `afield.runtime.languages`, tool request
 * and result schemas from `afield.ai.tools`, and the completion helpers from `afield.ai.completion`.
 * New code should import from those packages directly.
 *
 * The file also declares two `@RunsAt(installHandlers)` hooks, [ai4_install] and
 * [docs_search_install], which call `installAi4` and `installDocsSearch` (the latter installs the
 * `docs_search_input` message handler).
 */

package afield.ai.compat

import afield.ai.conversation.*
import afield.ai.models.*
import afield.document.workspace.VirtualTextFile
import afield.graphics.render.*
import afield.runtime.lifecycle.RunsAt
import afield.runtime.lifecycle.installHandlers
import afield.ai.models.installAi4
import afield.ai.tools.installDocsSearch

// Logging --------------------------------------------------------------------

typealias LogSink = afield.ai.diagnostics.LogSink
val StdoutLog get() = afield.ai.diagnostics.StdoutLog
typealias NotifyLog = afield.ai.diagnostics.NotifyLog
val SilentLog get() = afield.ai.diagnostics.SilentLog
typealias TraceLogger = afield.ai.diagnostics.TraceLogger

// Language specs -------------------------------------------------------------

typealias LanguageSpec = afield.runtime.languages.LanguageSpec
typealias MalformedAI = afield.runtime.languages.MalformedAI
val KOTLIN_SCRIPT get() = afield.runtime.languages.KOTLIN_SCRIPT
val GLSL_VERTEX get() = afield.runtime.languages.GLSL_VERTEX
val GLSL_FRAGMENT get() = afield.runtime.languages.GLSL_FRAGMENT
val GLSL_MESH get() = afield.runtime.languages.GLSL_MESH
val GLSL_TASK get() = afield.runtime.languages.GLSL_TASK
val GLSL_COMPUTE get() = afield.runtime.languages.GLSL_COMPUTE
val SLANG_VERTEX get() = afield.runtime.languages.SLANG_VERTEX
val SLANG_FRAGMENT get() = afield.runtime.languages.SLANG_FRAGMENT
val SLANG_MESH get() = afield.runtime.languages.SLANG_MESH
val SLANG_TASK get() = afield.runtime.languages.SLANG_TASK
val SLANG_COMPUTE get() = afield.runtime.languages.SLANG_COMPUTE
/** Forwards to `afield.runtime.languages.wrapScript`: wraps box script in the `SCRIPT()` function the completion engine reads, keeping imports and `@file` lines on top. */
fun wrapScript(script: String) = afield.runtime.languages.wrapScript(script)
/** Forwards to `afield.runtime.languages.unwrapScriptLenient`: recovers box script from wrapped model output, with or without its markers. */
fun unwrapScriptLenient(wrapped: String) = afield.runtime.languages.unwrapScriptLenient(wrapped)
/** Forwards to `afield.runtime.languages.glslSpecForProperty`: the GLSL stage spec for a shader property name such as `fragment`, or null. */
fun glslSpecForProperty(property: String) = afield.runtime.languages.glslSpecForProperty(property)
/** Forwards to `afield.runtime.languages.shaderSpecForProperty`: the GLSL or Slang stage spec for a shader property name, or null. */
fun shaderSpecForProperty(property: String) = afield.runtime.languages.shaderSpecForProperty(property)

// Tool schemas ---------------------------------------------------------------

typealias Commentary = afield.ai.tools.Commentary
typealias ContextAttachment = afield.ai.tools.ContextAttachment
typealias ListAttachmentsRequest = afield.ai.tools.ListAttachmentsRequest
typealias ListAttachmentsResult = afield.ai.tools.ListAttachmentsResult
typealias AttachmentInfo = afield.ai.tools.AttachmentInfo
typealias AttachmentRequest = afield.ai.tools.AttachmentRequest
typealias AttachmentResponse = afield.ai.tools.AttachmentResponse
typealias ListDefinitionsRequest = afield.ai.tools.ListDefinitionsRequest
typealias ListDefinitionsResult = afield.ai.tools.ListDefinitionsResult
typealias DefinitionInfo = afield.ai.tools.DefinitionInfo
typealias ListContentsRequest = afield.ai.tools.ListContentsRequest
typealias ListContentsResult = afield.ai.tools.ListContentsResult
typealias ListContentEntry = afield.ai.tools.ListContentEntry
typealias ListKotlinContextVariablesRequest = afield.ai.tools.ListKotlinContextVariablesRequest
typealias KotlinContextVariableInfo = afield.ai.tools.KotlinContextVariableInfo
typealias ListKotlinContextVariablesResult = afield.ai.tools.ListKotlinContextVariablesResult
typealias EvaluateKotlinExpressionRequest = afield.ai.tools.EvaluateKotlinExpressionRequest
typealias EvaluateKotlinExpressionResult = afield.ai.tools.EvaluateKotlinExpressionResult
typealias EditFileRequest = afield.ai.tools.EditFileRequest
typealias EditFileResponse = afield.ai.tools.EditFileResponse
typealias CompileKotlinRequest = afield.ai.tools.CompileKotlinRequest
typealias CompileKotlinResult = afield.ai.tools.CompileKotlinResult
typealias ReloadShadersRequest = afield.ai.tools.ReloadShadersRequest
typealias ReloadShadersResult = afield.ai.tools.ReloadShadersResult
typealias ScratchpadReadRequest = afield.ai.tools.ScratchpadReadRequest
typealias ScratchpadUpdateRequest = afield.ai.tools.ScratchpadUpdateRequest
typealias ScratchpadResult = afield.ai.tools.ScratchpadResult
typealias ChecklistReadRequest = afield.ai.tools.ChecklistReadRequest
typealias ChecklistUpdateRequest = afield.ai.tools.ChecklistUpdateRequest
typealias ChecklistItemInput = afield.ai.tools.ChecklistItemInput
typealias ChecklistItem = afield.ai.tools.ChecklistItem
typealias ChecklistResult = afield.ai.tools.ChecklistResult
typealias AgentMemoryState = afield.ai.tools.AgentMemoryState
typealias WorkspaceSymbolSearch = afield.ai.tools.WorkspaceSymbolSearch
typealias WorkspaceSymbolSearchResult = afield.ai.tools.WorkspaceSymbolSearchResult
typealias WorkspaceSymbolMatch = afield.ai.tools.WorkspaceSymbolMatch
typealias GetUtcTime = afield.ai.tools.GetUtcTime
typealias Remember = afield.ai.tools.Remember
typealias Recall = afield.ai.tools.Recall
typealias ToolExecutionContext = afield.ai.tools.ToolExecutionContext
/** Forwards to [afield.ai.tools.virtualFilesToAttachments]. */
fun virtualFilesToAttachments(files: List<afield.document.workspace.VirtualTextFile>) =
    afield.ai.tools.virtualFilesToAttachments(files)
/** Forwards to [afield.ai.tools.validateNoMarkers]. */
fun validateNoMarkers(code: String) = afield.ai.tools.validateNoMarkers(code)

// Core runtime ---------------------------------------------------------------

typealias OpenAiIdeCodexBridge = afield.ai.models.OpenAiIdeCodexBridge
typealias IdeChatHelpers = afield.ai.conversation.IdeChatHelpers

// GLSL autocomplete ----------------------------------------------------------

typealias CompletionCandidate = afield.ai.completion.CompletionCandidate
val Ai4GlslAutocomplete get() = afield.ai.completion.Ai4GlslAutocomplete

// Completion docs ------------------------------------------------------------

val Ai4CompletionDoc get() = afield.ai.completion.Ai4CompletionDoc

// Installer ------------------------------------------------------------------

/** Installs AI4's message handlers when Field starts. */
@RunsAt(installHandlers)
fun ai4_install() = installAi4()

/** Installs the docs search, including its `docs_search_input` message handler, when Field starts. */
@RunsAt(installHandlers)
fun docs_search_install() = installDocsSearch()

// Durable document guide tools, shared with the viewer and MCP.
typealias DocumentGuideTools = afield.ai.tools.DocumentGuideTools
typealias DocumentGuideListRequest = afield.ai.tools.DocumentGuideListRequest
typealias DocumentGuideReadRequest = afield.ai.tools.DocumentGuideReadRequest
typealias DocumentGuideSearchRequest = afield.ai.tools.DocumentGuideSearchRequest
typealias DocumentGuideCreateRequest = afield.ai.tools.DocumentGuideCreateRequest
typealias DocumentGuidePatchRequest = afield.ai.tools.DocumentGuidePatchRequest
typealias DocumentGuideAppendRequest = afield.ai.tools.DocumentGuideAppendRequest
typealias DocumentGuideToolResult = afield.ai.tools.DocumentGuideToolResult
