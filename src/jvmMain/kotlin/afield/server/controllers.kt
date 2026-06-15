package afield.server

import afield.FieldPreferences
import afield.Message
import afield.OscEditorCommand
import afield._send
import afield.filter
import afield.payload
import afield.sessions
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

private val controllerJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class ControllerInput(
    val type: String = "touch",
    val phase: String = "move",
    val command: String = "float",
    val x: Double = 0.0,
    val y: Double = 0.0,
    val dx: Double = 0.0,
    val dy: Double = 0.0,
    val sendMode: String = "absolute",
    val valueAxis: String = "y",
    val normalized: Boolean = true,
    val debounceMs: Int? = null,
    val maxExecutionMs: Int? = null
)

@Serializable
private data class ControllerStatus(
    val type: String = "status",
    val state: String,
    val clients: Int
)

fun Route.installControllerRoutes(preferences: () -> FieldPreferences) {
    route("/controllers") {
        get {
            val source = File("./src/commonMain/resources/controllers.html").readText(Charsets.UTF_8)
            call.respondText(filter(source), ContentType.Text.Html)
        }

        webSocket("/ws") {
            val targetDocument = call.request.queryParameters["file"]
                ?: call.request.queryParameters["document"]
                ?: ""
            send(Frame.Text(controllerStatus(targetDocument, "connected")))

            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val input = runCatching {
                    controllerJson.decodeFromString<ControllerInput>(frame.readText())
                }.getOrNull() ?: continue

                handleControllerInput(input, preferences(), targetDocument)
                send(Frame.Text(controllerStatus(targetDocument, "sent")))
            }
        }
    }
}

private fun handleControllerInput(input: ControllerInput, preferences: FieldPreferences, targetDocument: String) {
    when (input.type.trim().lowercase(Locale.US)) {
        "touch" -> handleTouch(input, preferences, targetDocument)
        "command" -> sendControllerCommand(input, preferences, targetDocument)
    }
}

private fun handleTouch(input: ControllerInput, preferences: FieldPreferences, targetDocument: String) {
    val mode = input.sendMode.trim().lowercase(Locale.US)
    if (mode == "absolute" || mode == "both") {
        sendControllerFloat(
            value = controllerAxisValue(input.valueAxis, input.x, input.y),
            delta = false,
            address = "/controllers/touch/position",
            preferences = preferences,
            input = input,
            targetDocument = targetDocument
        )
    }

    if (input.phase != "start" && (mode == "delta" || mode == "both")) {
        sendControllerFloat(
            value = controllerAxisValue(input.valueAxis, input.dx, input.dy),
            delta = true,
            address = "/controllers/touch/delta",
            preferences = preferences,
            input = input,
            targetDocument = targetDocument
        )
    }
}

private fun sendControllerCommand(input: ControllerInput, preferences: FieldPreferences, targetDocument: String) {
    val command = input.command.trim().lowercase(Locale.US)
    if (command !in setOf("launch", "stop")) return
    val trigger = controllerAxisValue(input.valueAxis, input.x, input.y)
    if (abs(trigger) <= 1.0e-9) return

    sendControllerMessage(
        OscEditorCommand(
            command = command,
            address = "/controllers/$command",
            debounceMs = controllerDebounce(input, preferences)
        ),
        targetDocument
    )
}

private fun sendControllerFloat(
    value: Double,
    delta: Boolean,
    address: String,
    preferences: FieldPreferences,
    input: ControllerInput,
    targetDocument: String
) {
    sendControllerMessage(
        OscEditorCommand(
            command = "float",
            address = address,
            value = value,
            text = formatControllerFloat(value),
            debounceMs = controllerDebounce(input, preferences),
            maxExecutionMs = controllerMaxExecution(input, preferences),
            delta = delta
        ),
        targetDocument
    )
}

private fun controllerAxisValue(axis: String, x: Double, y: Double): Double =
    if (axis.trim().equals("x", ignoreCase = true)) x else y

private fun controllerDebounce(input: ControllerInput, preferences: FieldPreferences): Int =
    (input.debounceMs ?: preferences.oscEditorDebounceMs).coerceIn(0, 5_000)

private fun controllerMaxExecution(input: ControllerInput, preferences: FieldPreferences): Int =
    (input.maxExecutionMs ?: preferences.oscEditorMaxExecutionMs).coerceIn(0, 60_000)

private fun sendControllerMessage(command: OscEditorCommand, targetDocument: String) {
    val message = Message("osc-editor").payload(command)
    val target = targetDocument.trim()
    if (target.isBlank()) {
        _send(message)
    } else {
        _send(message, document = Regex.escape(target))
    }
}

private fun controllerStatus(targetDocument: String, state: String): String {
    val target = targetDocument.trim()
    val connected = if (target.isBlank()) {
        sessions.size
    } else {
        sessions.values.count { info ->
            info.filename == target || info.documentName == target || File(info.filename).name == target
        }
    }
    return controllerJson.encodeToString(ControllerStatus(state = state, clients = connected))
}

private fun formatControllerFloat(value: Double): String {
    val symbols = DecimalFormatSymbols(Locale.US)
    val format = DecimalFormat("0.#######", symbols)
    return format.format(value)
}
