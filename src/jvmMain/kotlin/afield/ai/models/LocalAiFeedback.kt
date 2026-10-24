package afield.ai.models

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val feedbackMapper = ObjectMapper()
private val feedbackClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
    .followRedirects(HttpClient.Redirect.NEVER).build()

/** The optional monitor lives beside the model server, one port above its API. */
internal fun localAiMonitorUri(baseUrl: String, override: String? = null): URI {
    val uri = if (!override.isNullOrBlank()) URI.create(override) else {
        val base = URI.create(normalizeLocalAiBaseUrl(baseUrl))
        val port = if (base.port >= 0) base.port else if (base.scheme == "https") 443 else 80
        require(port < 65535) { "Set AFIELD_LOCAL_AI_MONITOR_URL for this endpoint." }
        URI(base.scheme, null, base.host, port + 1, "/status", null, null)
    }
    require(uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null) {
        "The local monitor URL must be an HTTP or HTTPS address."
    }
    return uri
}

/** Remote hardware feedback is available only for Local; cloud credentials never leave Field here. */
internal fun localAiFeedback(monitorUrl: String? = System.getenv("AFIELD_LOCAL_AI_MONITOR_URL")): String {
    if (configuredAiProvider() != "local") return "{\"enabled\":false}"
    return try {
        val uri = localAiMonitorUri(configuredAiBaseUrl("local"), monitorUrl)
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3))
            .header("Accept", "application/json").GET().build()
        val response = feedbackClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val body = response.body().use { it.readNBytes(65537) }
        require(response.statusCode() == 200 && body.size <= 65536)
        val data = feedbackMapper.readTree(body)
        require(data.isObject && data.path("schemaVersion").asInt() == 1)
        val result = feedbackMapper.createObjectNode()
        result.put("enabled", true)
        result.put("available", true)
        result.set<com.fasterxml.jackson.databind.JsonNode>("sample", data)
        result.toString()
    } catch (_: Exception) {
        "{\"enabled\":true,\"available\":false}"
    }
}
