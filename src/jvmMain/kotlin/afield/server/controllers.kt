package afield.server

import afield.FieldPreferences
import afield.Message
import afield.OscEditorCommand
import afield.ControllerControl
import afield.ControllerControlsResponse
import afield.ControllerSelectionState
import afield.controllerControlsForBox
import afield.getCheckboxCallback
import afield.getColorCallback
import afield.getSliderCallback
import afield.getStringCallback
import afield.getVec2Callback
import afield._send
import afield.filter
import afield.logger.lprintln
import afield.payload
import afield.sessions
import afield.SessionInformation
import afield.Set_output
import afield.updateControllerControlValue
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import org.joml.Vector2f
import org.joml.Vector4f
import java.io.File
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

private val controllerJson = Json { ignoreUnknownKeys = true }
private val controllerSelections = ConcurrentHashMap<String, ControllerSelectionState>()
private val controllerOutputAreas = ConcurrentHashMap<String, ConcurrentHashMap<String, ConcurrentHashMap<String, ControllerOutputArea>>>()
private val controllerCallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
    val maxExecutionMs: Int? = null,
    val controlId: String = "",
    val value: Double = 0.0,
    val checked: Boolean? = null,
    val text: String = "",
    val r: Double = 0.0,
    val g: Double = 0.0,
    val b: Double = 0.0,
    val a: Double = 1.0
)

@Serializable
private data class ControllerOutputArea(
    val uid: String,
    val channel: String,
    val html: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
private data class ControllerOutputsResponse(
    val document: String = "",
    val selectedUid: String? = null,
    val selectedName: String = "",
    val outputs: List<ControllerOutputArea> = emptyList(),
    val controls: List<ControllerControl> = emptyList()
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

        get("/controls") {
            val targetDocument = call.request.queryParameters["file"]
                ?: call.request.queryParameters["document"]
                ?: ""
            call.respondText(
                controllerJson.encodeToString(controllerControls(targetDocument)),
                ContentType.Application.Json
            )
        }

        get("/outputs") {
            val targetDocument = call.request.queryParameters["file"]
                ?: call.request.queryParameters["document"]
                ?: ""
            call.respondText(
                controllerJson.encodeToString(controllerOutputs(targetDocument)),
                ContentType.Application.Json
            )
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
        "release" -> sendControllerRelease(input, preferences, targetDocument)
        "command" -> sendControllerCommand(input, preferences, targetDocument)
        "slider" -> updateControllerSlider(input, targetDocument)
        "vec2" -> updateControllerVec2(input, targetDocument)
        "checkbox" -> updateControllerCheckbox(input, targetDocument)
        "string" -> updateControllerString(input, targetDocument)
        "color" -> updateControllerColor(input, targetDocument)
    }
}

private fun sendControllerRelease(input: ControllerInput, preferences: FieldPreferences, targetDocument: String) {
    sendControllerMessage(
        OscEditorCommand(
            command = "update-shader",
            address = "/controllers/touch/release",
            debounceMs = 0,
            maxExecutionMs = controllerMaxExecution(input, preferences)
        ),
        targetDocument
    )
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
    if (command !in setOf("save", "restore", "lock", "unlock", "launch", "stop")) return
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
    val vector2 = controllerAxis(input.valueAxis) == "xy"
    sendControllerMessage(
        OscEditorCommand(
            command = "float",
            address = address,
            value = value,
            text = formatControllerFloat(value),
            debounceMs = controllerDebounce(input, preferences),
            maxExecutionMs = controllerMaxExecution(input, preferences),
            delta = delta,
            vector2 = vector2,
            x = if (vector2) input.x else null,
            y = if (vector2) input.y else null,
            dx = if (vector2) input.dx else null,
            dy = if (vector2) input.dy else null
        ),
        targetDocument
    )
}

private fun controllerAxis(axis: String): String =
    when (axis.trim().lowercase(Locale.US)) {
        "x" -> "x"
        "xy" -> "xy"
        else -> "y"
    }

private fun controllerAxisValue(axis: String, x: Double, y: Double): Double =
    if (controllerAxis(axis) == "x") x else y

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


fun recordControllerOutputMessage(info: SessionInformation?, message: Message) {
    val output = controllerOutputPayload(message) ?: return
    val keys = info?.let { controllerSelectionKeys(it) } ?: listOf("")
    keys.forEach { key ->
        val byUid = controllerOutputAreas.computeIfAbsent(key) { ConcurrentHashMap() }
        val byChannel = byUid.computeIfAbsent(output.uid) { ConcurrentHashMap() }
        byChannel[output.channel] = ControllerOutputArea(
            uid = output.uid,
            channel = output.channel.ifBlank { "output" },
            html = output.value
        )
    }
}

fun recordControllerOutputMessage(targetDocument: String, message: Message) {
    val output = controllerOutputPayload(message) ?: return
    val target = targetDocument.trim()
    if (target.isBlank() || target == ".*") return
    val byUid = controllerOutputAreas.computeIfAbsent(target) { ConcurrentHashMap() }
    val byChannel = byUid.computeIfAbsent(output.uid) { ConcurrentHashMap() }
    byChannel[output.channel] = ControllerOutputArea(
        uid = output.uid,
        channel = output.channel.ifBlank { "output" },
        html = output.value
    )
}

private fun controllerOutputPayload(message: Message): Set_output? {
    if (message.name != "set-output") return null
    val payload: JsonElement = message.payload ?: return null
    val output = runCatching { controllerJson.decodeFromJsonElement(Set_output.serializer(), payload) }.getOrNull() ?: return null
    if (output.uid.isBlank() || output.property != "outputarea") return null
    return output
}

fun recordControllerSelection(info: SessionInformation, selection: ControllerSelectionState) {
    val normalized = selection.copy(selectedUids = selection.selectedUids.filter { it.isNotBlank() })
    controllerSelectionKeys(info).forEach { key ->
        controllerSelections[key] = normalized
    }
}

fun broadcastControllerControlUpdates(controls: List<ControllerControl>, targetDocument: String = "") {
    val target = targetDocument.trim()
    controls.forEach { control ->
        val message = Message("control-value-update").payload(control)
        if (target.isBlank()) {
            _send(message)
        } else {
            _send(message, document = Regex.escape(target))
        }
    }
}

private fun controllerControls(targetDocument: String): ControllerControlsResponse {
    val candidates = controllerSessions(targetDocument)
    val info = candidates.firstOrNull() ?: return ControllerControlsResponse(document = targetDocument)
    val selection = controllerSelectionFor(targetDocument, candidates)
    val selectedUid = selection?.selectedUids?.lastOrNull { info.boxes.containsKey(it) }
    val selectedBox = selectedUid?.let { info.boxes[it] }
    val controls = selectedUid?.let { controllerControlsForBox(it) } ?: emptyList()
    return ControllerControlsResponse(
        document = info.documentName,
        selectedUid = selectedUid,
        selectedName = selectedBox?.getPropertyRaw("name") as? String ?: selectedUid.orEmpty(),
        selectedProperty = selection?.property ?: "code",
        controls = controls
    )
}

private fun controllerOutputs(targetDocument: String): ControllerOutputsResponse {
    val candidates = controllerSessions(targetDocument)
    val info = candidates.firstOrNull() ?: return ControllerOutputsResponse(document = targetDocument)
    val selection = controllerSelectionFor(targetDocument, candidates)
    val selectedUid = selection?.selectedUids?.lastOrNull { info.boxes.containsKey(it) }
    val selectedBox = selectedUid?.let { info.boxes[it] }
    val outputs = selectedUid?.let { uid ->
        controllerSelectionKeys(info).asSequence()
            .mapNotNull { key -> controllerOutputAreas[key]?.get(uid) }
            .firstOrNull()
            ?.values
            ?.sortedWith(compareBy<ControllerOutputArea> { it.channel != "controls" }.thenBy { it.channel })
            ?: emptyList()
    } ?: emptyList()
    return ControllerOutputsResponse(
        document = info.documentName,
        selectedUid = selectedUid,
        selectedName = selectedBox?.getPropertyRaw("name") as? String ?: selectedUid.orEmpty(),
        outputs = outputs,
        controls = selectedUid?.let { controllerControlsForBox(it) } ?: emptyList()
    )
}

private fun controllerSelectionFor(targetDocument: String, candidates: List<SessionInformation>): ControllerSelectionState? {
    val target = targetDocument.trim()
    if (target.isNotBlank()) {
        controllerSelections[target]?.let { return it }
    }
    candidates.forEach { info ->
        controllerSelectionKeys(info).forEach { key ->
            controllerSelections[key]?.let { return it }
        }
    }
    return controllerSelections.values.firstOrNull { it.selectedUids.isNotEmpty() }
}

private fun controllerSessions(targetDocument: String): List<SessionInformation> {
    val target = targetDocument.trim()
    val all = sessions.values.distinctBy { it.filename }
    if (target.isBlank()) return all
    return all.filter { controllerSessionMatches(it, target) }
}

private fun controllerSessionMatches(info: SessionInformation, target: String): Boolean =
    info.filename == target || info.documentName == target || File(info.filename).name == target

private fun controllerSelectionKeys(info: SessionInformation): List<String> =
    listOf(info.filename, info.documentName, File(info.filename).name).distinct().filter { it.isNotBlank() }

private fun updateControllerSlider(input: ControllerInput, targetDocument: String) {
    val controlId = input.controlId.trim()
    if (controlId.isBlank()) return
    val controls = updateControllerControlValue(controlId) { it.copy(value = input.value) }
    broadcastControllerControlUpdates(controls, targetDocument)
    val callback = getSliderCallback(controlId) ?: return
    launchControllerCallback("slider", controlId) { callback(input.value) }
}

private fun updateControllerVec2(input: ControllerInput, targetDocument: String) {
    val controlId = input.controlId.trim()
    if (controlId.isBlank()) return
    val controls = updateControllerControlValue(controlId) { it.copy(x = input.x, y = input.y) }
    broadcastControllerControlUpdates(controls, targetDocument)
    val callback = getVec2Callback(controlId) ?: return
    launchControllerCallback("vec2", controlId) { callback(Vector2f(input.x.toFloat(), input.y.toFloat())) }
}

private fun updateControllerCheckbox(input: ControllerInput, targetDocument: String) {
    val controlId = input.controlId.trim()
    if (controlId.isBlank()) return
    val value = input.checked ?: (abs(input.value) > 1.0e-9)
    val controls = updateControllerControlValue(controlId) { it.copy(checked = value) }
    broadcastControllerControlUpdates(controls, targetDocument)
    val callback = getCheckboxCallback(controlId) ?: return
    launchControllerCallback("checkbox", controlId) { callback(value) }
}

private fun updateControllerString(input: ControllerInput, targetDocument: String) {
    val controlId = input.controlId.trim()
    if (controlId.isBlank()) return
    val controls = updateControllerControlValue(controlId) { it.copy(text = input.text) }
    broadcastControllerControlUpdates(controls, targetDocument)
    val callback = getStringCallback(controlId) ?: return
    launchControllerCallback("string", controlId) { callback(input.text) }
}

private fun updateControllerColor(input: ControllerInput, targetDocument: String) {
    val controlId = input.controlId.trim()
    if (controlId.isBlank()) return
    val controls = updateControllerControlValue(controlId) {
        it.copy(r = input.r, g = input.g, b = input.b, a = input.a, value = input.a)
    }
    broadcastControllerControlUpdates(controls, targetDocument)
    val callback = getColorCallback(controlId) ?: return
    launchControllerCallback("color", controlId) {
        callback(Vector4f(input.r.toFloat(), input.g.toFloat(), input.b.toFloat(), input.a.toFloat()))
    }
}

private fun launchControllerCallback(kind: String, controlId: String, callback: () -> Unit) {
    controllerCallbackScope.launch {
        runCatching { callback() }
            .onFailure { throwable ->
                lprintln("error,controllers", "Error in $kind controller callback for $controlId: ${throwable.message}")
            }
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
