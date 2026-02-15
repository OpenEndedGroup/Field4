@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package afield.ai

import afield.RunsAt
import afield.installHandlers
import afield.ai.core.installAi4

// Logging --------------------------------------------------------------------

typealias LogSink = afield.ai.core.LogSink
val StdoutLog get() = afield.ai.core.StdoutLog
typealias NotifyLog = afield.ai.core.NotifyLog
val SilentLog get() = afield.ai.core.SilentLog
typealias TraceLogger = afield.ai.core.TraceLogger

// Language specs -------------------------------------------------------------

typealias LanguageSpec = afield.ai.core.LanguageSpec
typealias MalformedAI = afield.ai.core.MalformedAI
val KOTLIN_SCRIPT get() = afield.ai.core.KOTLIN_SCRIPT
val GLSL_VERTEX get() = afield.ai.core.GLSL_VERTEX
val GLSL_FRAGMENT get() = afield.ai.core.GLSL_FRAGMENT
val GLSL_MESH get() = afield.ai.core.GLSL_MESH
val GLSL_TASK get() = afield.ai.core.GLSL_TASK
val GLSL_COMPUTE get() = afield.ai.core.GLSL_COMPUTE
fun wrapScript(script: String) = afield.ai.core.wrapScript(script)
fun unwrapScriptLenient(wrapped: String) = afield.ai.core.unwrapScriptLenient(wrapped)
fun glslSpecForProperty(property: String) = afield.ai.core.glslSpecForProperty(property)

// Tool schemas ---------------------------------------------------------------

typealias Commentary = afield.ai.core.Commentary
typealias ContextAttachment = afield.ai.core.ContextAttachment
typealias ListAttachmentsRequest = afield.ai.core.ListAttachmentsRequest
typealias ListAttachmentsResult = afield.ai.core.ListAttachmentsResult
typealias AttachmentInfo = afield.ai.core.AttachmentInfo
typealias AttachmentRequest = afield.ai.core.AttachmentRequest
typealias AttachmentResponse = afield.ai.core.AttachmentResponse
typealias ListDefinitionsRequest = afield.ai.core.ListDefinitionsRequest
typealias ListDefinitionsResult = afield.ai.core.ListDefinitionsResult
typealias DefinitionInfo = afield.ai.core.DefinitionInfo
typealias ListContentsRequest = afield.ai.core.ListContentsRequest
typealias ListContentsResult = afield.ai.core.ListContentsResult
typealias ListContentEntry = afield.ai.core.ListContentEntry
typealias EditFileRequest = afield.ai.core.EditFileRequest
typealias EditFileResponse = afield.ai.core.EditFileResponse
typealias WorkspaceSymbolSearch = afield.ai.core.WorkspaceSymbolSearch
typealias WorkspaceSymbolSearchResult = afield.ai.core.WorkspaceSymbolSearchResult
typealias WorkspaceSymbolMatch = afield.ai.core.WorkspaceSymbolMatch
typealias CheckCodeRequest = afield.ai.core.CheckCodeRequest
typealias CheckCodeResult = afield.ai.core.CheckCodeResult
typealias CodeError = afield.ai.core.CodeError
typealias GetUtcTime = afield.ai.core.GetUtcTime
typealias Remember = afield.ai.core.Remember
typealias Recall = afield.ai.core.Recall
typealias ToolExecutionContext = afield.ai.core.ToolExecutionContext
fun virtualFilesToAttachments(files: List<afield.ai.VirtualTextFile>) =
    afield.ai.core.virtualFilesToAttachments(files)
fun validateNoMarkers(code: String) = afield.ai.core.validateNoMarkers(code)

// Core runtime ---------------------------------------------------------------

typealias OpenAiIdeCodexBridge = afield.ai.core.OpenAiIdeCodexBridge
typealias IdeChatHelpers = afield.ai.core.IdeChatHelpers

// GLSL autocomplete ----------------------------------------------------------

typealias CompletionCandidate = afield.ai.core.CompletionCandidate
val Ai4GlslAutocomplete get() = afield.ai.core.Ai4GlslAutocomplete

// Completion docs ------------------------------------------------------------

val Ai4CompletionDoc get() = afield.ai.core.completion.Ai4CompletionDoc

// Installer ------------------------------------------------------------------

@RunsAt(installHandlers)
fun ai4_install() = installAi4()

// MCP Server -----------------------------------------------------------------

typealias McpServer = afield.ai.mcp.McpServer
