@file:OptIn(DelicateCoroutinesApi::class)

package afield.app.protocol

import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem

import afield.app.diagnostics.BoxOutputLog
import afield.app.server.broadcastWelcomeEvent
import afield.app.server.controlUpdateId
import afield.app.server.controlUpdateScope
import afield.app.server.documents
import afield.app.server.isBackPressuredOutputMessage
import afield.app.server.noteSkippedOutputs
import afield.app.server.outputBackPressureLog
import afield.app.server.outputBackPressureLogEnabled
import afield.app.server.outputBackPressureLogIntervalMillis
import afield.app.server.outputBackPressureLogMode
import afield.app.server.outputBackPressureLogTimes
import afield.app.server.outputBackPressureMessageName
import afield.app.server.outputBackPressureTraceLogEnabled
import afield.app.server.sendConflatedControlUpdate
import afield.app.server.sendSkippedOutputNotify
import afield.app.server.sendWithOutputBackPressure
import afield.app.server.sessionControlUpdatePumps
import afield.app.server.sessionSkippedOutputNotifiers
import afield.app.server.sessions
import afield.app.server.skippedOutputBurstResetMillis
import afield.app.server.skippedOutputNotifyIntervalMillis
import afield.app.server.skippedOutputNotifyScope
import afield.audio.synth.*
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import afield.ai.compat.Ai4CompletionDoc
import afield.ai.completion.AI4_AUTO_NAME_PROPERTY
import afield.ai.completion.AI4_AUTO_NAME_SIGNATURE_PROPERTY
import afield.ai.models.AiSettingsSaveRequest
import afield.ai.models.aiProviderStatus
import afield.ai.models.configuredAiBaseUrl
import afield.ai.models.configuredAiModelSettings
import afield.ai.models.saveAiSettings
import afield.ai.completion.Ai4KotlinAutocomplete
import afield.ai.retrieval.SemanticRetrievalQueryRequest
import afield.ai.retrieval.SemanticRetrievalService
import afield.ai.retrieval.SemanticRetrievalConfigSaveRequest
import afield.ai.retrieval.SemanticRetrievalDeleteCacheRequest
import afield.ai.retrieval.SemanticRetrievalDownloadRequest
import afield.app.diagnostics.debug.RuntimeDebugProbeService
import afield.media.stream.FieldRTTStreamer
import afield.graphics.vulkan.util.FramePresentationLog
import afield.graphics.vulkan.util.Options
import afield.geometry.path.getFLineEditSession
import afield.app.diagnostics.logging.SaveReloadLog
import afield.app.diagnostics.logging.lblock
import afield.app.diagnostics.logging.lprint
import afield.app.diagnostics.logging.lprintln
import afield.lang.python.PythonInterface
import afield.lang.python.PythonSettingsRequest
import afield.app.server.refreshFileProxyBoxes
import afield.app.server.refuseUnlessSameSiteDocsClient
import afield.app.server.installFieldAudioProxyRoutes
import afield.app.server.installSignalViewerRoutes
import afield.app.server.installDocsAnnotationRoutes
import afield.app.server.installTerminalRoutes
import afield.app.server.docsAccessPolicy
import afield.app.server.SEC_FETCH_SITE_HEADER
import afield.app.server.DocsAccessPolicy
import afield.app.server.docsFlagEnabled
import afield.app.server.isSameSiteRequest
import afield.app.server.installDocumentGuideRoutes
import afield.ai.conversation.chatlog.installChatlogRoutes
import afield.app.server.installControllerRoutes
import afield.app.server.broadcastControllerControlUpdates
import afield.app.server.recordControllerOutputMessage
import afield.app.server.recordControllerSelection
import afield.app.server.FileProxyWriteResult
import afield.app.server.writeFileProxyContents
import afield.runtime.execution.begin
import afield.app.commands.Action
import com.sun.management.GarbageCollectionNotificationInfo
import com.aallam.openai.api.BetaOpenAI
import com.microsoft.playwright.*
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.WaitUntilState
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.partialcontent.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.ktor.websocket.Frame
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import afield.math.linalg.Vector2f
import afield.math.linalg.Vector4f
import java.awt.Desktop
import java.io.File
import java.lang.management.ManagementFactory
import java.net.BindException
import java.net.URI
import java.net.ServerSocket
import java.net.URLEncoder
import java.nio.file.Paths
import java.security.KeyStore
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.management.Notification
import javax.management.NotificationEmitter
import javax.management.openmbean.CompositeData
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds


@OptIn(DelicateCoroutinesApi::class)
actual fun _send(message: Message) {
    _send(message, document = ".*")
}


@OptIn(DelicateCoroutinesApi::class)
fun _send(message: Message, document: String = ".*") {
    recordControllerOutputMessage(document, message)
    // Resolve the exact owner for the log as well as the wire recipients.
    val logDocument=documents.values.singleOrNull { it.filename.matches(document.toRegex()) }?.filename.orEmpty()
    BoxOutputLog.observeBroadcast(message,logDocument)
    val documentRegex = document.toRegex()
    val m = Json.encodeToString(message)
    sessions.forEach { (session, info) ->
        val matchesDocument = info.filename.matches(documentRegex) ||
                info.documentName.matches(documentRegex) ||
                File(info.filename).name.matches(documentRegex)
        if (!matchesDocument) return@forEach
        recordControllerOutputMessage(info, message)
        sendWithOutputBackPressure(session, message, m)
    }
    if (message.name == "is-running") {
        broadcastWelcomeEvent("running")
    }
}

@OptIn(DelicateCoroutinesApi::class)
fun _send(session: WebSocketSession?, message: Message) {
    if (session == null) {
        _send(message)
        return
    }
    val m = Json.encodeToString(message)
    recordControllerOutputMessage(sessions[session], message)
    sendWithOutputBackPressure(session, message, m)
}

