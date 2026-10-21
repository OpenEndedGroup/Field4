package afield.ai.models

import afield.document.workspace.LocalAiConnectionRequest
import afield.document.workspace.LocalAiConnectionStatus
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Checks discovery from the Field JVM, without sending code or running inference. */
internal fun checkLocalAiConnection(request: LocalAiConnectionRequest): LocalAiConnectionStatus {
    val base = normalizeLocalAiBaseUrl(request.baseUrl)
    // Reuse a saved key only for the endpoint it belongs to. Never use cloud credentials here.
    val key = request.apiKey.trim().takeIf { it.isNotBlank() }
        ?: if (base == configuredAiBaseUrl("local")) readLocalAiCredential()?.first else null
    val builder = HttpRequest.newBuilder(URI.create("$base/models"))
        .timeout(Duration.ofSeconds(8)).header("Accept", "application/json").GET()
    key?.let { builder.header("Authorization", "Bearer $it") }
    val response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build()
        .send(builder.build(), HttpResponse.BodyHandlers.ofString())
    require(response.statusCode() == 200) {
        "Model discovery returned HTTP ${response.statusCode()}. Check the API base URL and optional API key."
    }
    val data = ObjectMapper().readTree(response.body()).path("data")
    require(data.isArray) { "The endpoint did not return an OpenAI-style model list at /models." }
    val models = data.mapNotNull { it.path("id").asText().takeIf(String::isNotBlank) }.distinct()
    val selected = request.model.trim()
    val available = selected.isBlank() || selected in models
    return LocalAiConnectionStatus(
        connected = true, models = models, modelAvailable = available,
        message = when {
            models.isEmpty() -> "Connected, but no models are listed. Load a model on the inference server."
            !available -> "Connected, but '$selected' is not listed. Choose one of the model IDs below."
            else -> "Connected from Field. Model discovery succeeded; AI4 also needs Responses API and tool support."
        }
    )
}
