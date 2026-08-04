package afield.server

import afield.logger.lprintln
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveStream
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val fieldAudioProxyClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(5))
    .version(HttpClient.Version.HTTP_1_1)
    .followRedirects(HttpClient.Redirect.NEVER)
    .build()

private val fieldAudioServiceBase: String =
    System.getenv("FIELD_AUDIO_SERVICE_URL")?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        ?: "http://127.0.0.1:8765"

private val representationContentPath =
    Regex("^/v1/representations/[A-Za-z0-9:_-]+/content$")
private val representationWaveformPath =
    Regex("^/v1/representations/[A-Za-z0-9:_-]+/waveform$")
private val candidateJudgementPath =
    Regex("^/v1/candidate-sets/[A-Za-z0-9:_-]+/candidates/[0-9]+$")
private val jobCancellationPath =
    Regex("^/v1/jobs/[A-Za-z0-9:_-]+/cancel$")

internal fun isAllowedFieldAudioProxyRequest(method: HttpMethod, path: String): Boolean = when (method) {
    HttpMethod.Get -> path == "/monitor" || path == "/v1/jobs" ||
        representationContentPath.matches(path) || representationWaveformPath.matches(path)
    HttpMethod.Post -> jobCancellationPath.matches(path)
    HttpMethod.Put -> candidateJudgementPath.matches(path)
    else -> false
}

/**
 * A deliberately narrow same-origin bridge for browser-facing Field Audio UI.
 *
 * The model service remains loopback-only. Browsers fetch candidate audio and
 * persist candidate judgements through Field's own HTTPS origin, which also
 * makes remote Field sessions independent of the browser machine's localhost.
 */
fun Route.installFieldAudioProxyRoutes() {
    route("/field-audio") {
        get("{...}") { call.proxyFieldAudioRequest() }
        post("{...}") { call.proxyFieldAudioRequest() }
        put("{...}") { call.proxyFieldAudioRequest() }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.proxyFieldAudioRequest() {
    val requestUri = runCatching { URI(request.uri) }.getOrElse {
        respond(HttpStatusCode.BadRequest, "Invalid Field Audio browser route")
        return
    }
    val upstreamPath = requestUri.rawPath.removePrefix("/field-audio")
    val method = request.httpMethod
    if (!isAllowedFieldAudioProxyRequest(method, upstreamPath)) {
        respond(HttpStatusCode.NotFound, "Field Audio browser route not found")
        return
    }

    val query = requestUri.rawQuery?.let { "?$it" }.orEmpty()
    val target = URI.create("$fieldAudioServiceBase$upstreamPath$query")
    val builder = HttpRequest.newBuilder(target).timeout(Duration.ofMinutes(2))
    listOf(HttpHeaders.Accept, HttpHeaders.ContentType, HttpHeaders.Range, HttpHeaders.IfNoneMatch,
        HttpHeaders.IfModifiedSince).forEach { header ->
        request.headers[header]?.let { builder.header(header, it) }
    }
    val body = if (method == HttpMethod.Put || method == HttpMethod.Post) {
        HttpRequest.BodyPublishers.ofByteArray(receiveStream().use { it.readBytes() })
    } else {
        HttpRequest.BodyPublishers.noBody()
    }
    builder.method(method.value, body)

    val upstream = try {
        withContext(Dispatchers.IO) {
            fieldAudioProxyClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        }
    } catch (error: Exception) {
        lprintln("warning,field-audio", "browser proxy failed for $upstreamPath: ${error.message}")
        respond(HttpStatusCode.BadGateway, "Field Audio service unavailable")
        return
    }

    listOf(HttpHeaders.AcceptRanges, HttpHeaders.ContentRange, HttpHeaders.ETag,
        HttpHeaders.LastModified, HttpHeaders.CacheControl).forEach { header ->
        upstream.headers().firstValue(header).ifPresent { response.headers.append(header, it) }
    }
    val contentType = upstream.headers().firstValue(HttpHeaders.ContentType).orElse(null)
        ?.let { runCatching { ContentType.parse(it) }.getOrNull() }
        ?: ContentType.Application.OctetStream
    respondBytes(
        bytes = upstream.body(),
        contentType = contentType,
        status = HttpStatusCode.fromValue(upstream.statusCode()),
    )
}
