@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package afield.ai

import afield.RunsAt
import afield.installHandlers
import afield.ai.core.install.installAi4

// Logging --------------------------------------------------------------------

typealias LogSink = afield.ai.core.logging.LogSink
val StdoutLog get() = afield.ai.core.logging.StdoutLog
typealias NotifyLog = afield.ai.core.logging.NotifyLog
val SilentLog get() = afield.ai.core.logging.SilentLog
typealias TraceLogger = afield.ai.core.logging.TraceLogger

// Language specs -------------------------------------------------------------

typealias LanguageSpec = afield.ai.core.lang.LanguageSpec
typealias MalformedAI = afield.ai.core.lang.MalformedAI
val KOTLIN_SCRIPT get() = afield.ai.core.lang.KOTLIN_SCRIPT
val GLSL_VERTEX get() = afield.ai.core.lang.GLSL_VERTEX
val GLSL_FRAGMENT get() = afield.ai.core.lang.GLSL_FRAGMENT
val GLSL_MESH get() = afield.ai.core.lang.GLSL_MESH
val GLSL_TASK get() = afield.ai.core.lang.GLSL_TASK
val GLSL_COMPUTE get() = afield.ai.core.lang.GLSL_COMPUTE
fun wrapScript(script: String) = afield.ai.core.lang.wrapScript(script)
fun unwrapScriptLenient(wrapped: String) = afield.ai.core.lang.unwrapScriptLenient(wrapped)
fun glslSpecForProperty(property: String) = afield.ai.core.lang.glslSpecForProperty(property)

// Tool schemas ---------------------------------------------------------------

typealias Commentary = afield.ai.core.tools.Commentary
typealias ContextAttachment = afield.ai.core.tools.ContextAttachment
typealias ListAttachmentsRequest = afield.ai.core.tools.ListAttachmentsRequest
typealias ListAttachmentsResult = afield.ai.core.tools.ListAttachmentsResult
typealias AttachmentInfo = afield.ai.core.tools.AttachmentInfo
typealias AttachmentRequest = afield.ai.core.tools.AttachmentRequest
typealias AttachmentResponse = afield.ai.core.tools.AttachmentResponse
typealias ListDefinitionsRequest = afield.ai.core.tools.ListDefinitionsRequest
typealias ListDefinitionsResult = afield.ai.core.tools.ListDefinitionsResult
typealias DefinitionInfo = afield.ai.core.tools.DefinitionInfo
typealias ListContentsRequest = afield.ai.core.tools.ListContentsRequest
typealias ListContentsResult = afield.ai.core.tools.ListContentsResult
typealias EditFileRequest = afield.ai.core.tools.EditFileRequest
typealias EditFileResponse = afield.ai.core.tools.EditFileResponse
typealias WorkspaceSymbolSearch = afield.ai.core.tools.WorkspaceSymbolSearch
typealias WorkspaceSymbolSearchResult = afield.ai.core.tools.WorkspaceSymbolSearchResult
typealias WorkspaceSymbolMatch = afield.ai.core.tools.WorkspaceSymbolMatch
typealias GetUtcTime = afield.ai.core.tools.GetUtcTime
typealias Remember = afield.ai.core.tools.Remember
typealias Recall = afield.ai.core.tools.Recall
typealias ToolExecutionContext = afield.ai.core.tools.ToolExecutionContext
fun virtualFilesToAttachments(files: List<afield.ai.VirtualTextFile>) =
    afield.ai.core.tools.virtualFilesToAttachments(files)
fun validateNoMarkers(code: String) = afield.ai.core.tools.validateNoMarkers(code)

// Core runtime ---------------------------------------------------------------

typealias OpenAiIdeCodexBridge = afield.ai.core.bridge.OpenAiIdeCodexBridge
typealias IdeChatHelpers = afield.ai.core.chat.IdeChatHelpers

// GLSL autocomplete ----------------------------------------------------------

typealias CompletionCandidate = afield.ai.core.glsl.CompletionCandidate
val Ai4GlslAutocomplete get() = afield.ai.core.glsl.Ai4GlslAutocomplete

// Completion docs ------------------------------------------------------------

val Ai4CompletionDoc get() = afield.ai.core.completion.Ai4CompletionDoc

// Installer ------------------------------------------------------------------

@RunsAt(installHandlers)
fun ai4_install() = installAi4()
