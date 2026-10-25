package afield.app.server

import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.ui.notation.with

import afield.app.protocol.Message
import afield.app.protocol.OscEditorCommand
import afield.app.protocol._send
import afield.app.protocol.handle
import afield.app.protocol.payload
import afield.audio.synth.*
import afield.document.workspace.FieldPreferences
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import afield.tools.docs.format
import afield.app.diagnostics.logging.lprintln
import com.illposed.osc.OSCMessageEvent
import com.illposed.osc.OSCMessageListener
import com.illposed.osc.messageselector.OSCPatternAddressMessageSelector
import com.illposed.osc.transport.OSCPortIn
import com.illposed.osc.transport.OSCPortInBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

/**
 * Lets an OSC controller drive the editor by forwarding float, launch and stop messages to the front end as `osc-editor` commands.
 *
 * [OscEditorBackdoor] reads its port, addresses, debounce and delta mode from [FieldPreferences],
 * listens with an `OSCPortIn`, and forwards each matching message to the front end as an
 * `osc-editor` message. [OscEditorBackdoor.learnNextAddress] listens once on a port and returns the
 * first address it hears, for filling in the preferences.
 */

/** The OSC listener settings taken from [FieldPreferences]; a change restarts the listener. */
private data class OscEditorConfig(
    val enabled: Boolean,
    val port: Int,
    val floatAddress: String,
    val launchAddress: String,
    val stopAddress: String,
    val debounceMs: Int,
    val maxExecutionMs: Int,
    val deltaMode: Boolean
)

object OscEditorBackdoor {
    private var current: OscEditorConfig? = null
    private var portIn: OSCPortIn? = null

    @Synchronized
    fun configure(preferences: FieldPreferences) {
        val next = OscEditorConfig(
            enabled = preferences.oscEditorEnabled,
            port = preferences.oscEditorPort.coerceIn(1, 65535),
            floatAddress = preferences.oscEditorFloatAddress.trim(),
            launchAddress = preferences.oscEditorLaunchAddress.trim(),
            stopAddress = preferences.oscEditorStopAddress.trim(),
            debounceMs = preferences.oscEditorDebounceMs.coerceIn(0, 5_000),
            maxExecutionMs = preferences.oscEditorMaxExecutionMs.coerceIn(0, 60_000),
            deltaMode = preferences.oscEditorDeltaMode
        )

        if (next == current) return
        stop()
        current = next

        if (!next.enabled) return
        if (listOf(next.floatAddress, next.launchAddress, next.stopAddress).all { it.isBlank() }) return

        try {
            val created = OSCPortInBuilder()
                .setLocalPort(next.port)
                .build()
            portIn = created
            try {
                created.connect()
            } catch (_: Throwable) {
            }

            addListener(created, next.floatAddress, "float", next)
            addListener(created, next.launchAddress, "launch", next)
            addListener(created, next.stopAddress, "stop", next)
            created.startListening()
            lprintln("info,osc-editor", "listening on UDP ${next.port}")
        } catch (t: Throwable) {
            lprintln("warning,osc-editor", "could not start OSC editor backdoor on UDP ${next.port}: ${t.message}")
            stop()
        }
    }

    @Synchronized
    fun stop() {
        val existing = portIn
        current = null
        portIn = null
        if (existing != null) {
            runCatching { existing.stopListening() }
            runCatching { existing.close() }
        }
    }

    suspend fun learnNextAddress(port: Int, timeoutMs: Long = 10_000, ignoreAddresses: Set<String> = emptySet()): String? {
        val listenPort = port.coerceIn(1, 65535)
        val timeout = timeoutMs.coerceIn(1_000, 60_000)
        val ignored = ignoreAddresses.mapNotNull { normalizeAddressOrNull(it) }.toSet()
        val learned = CompletableDeferred<String>()
        val listener = OSCMessageListener { event ->
            val address = event.message.address ?: return@OSCMessageListener
            if (address.isBlank()) return@OSCMessageListener
            if (address in ignored) return@OSCMessageListener
            learned.complete(address)
        }
        val tempPort = OSCPortInBuilder()
            .setLocalPort(listenPort)
            .build()

        return try {
            try {
                tempPort.connect()
            } catch (_: Throwable) {
            }
            tempPort.dispatcher.addListener(OSCPatternAddressMessageSelector("//*"), listener)
            tempPort.startListening()
            withTimeoutOrNull(timeout) { learned.await() }
        } finally {
            runCatching { tempPort.stopListening() }
            runCatching { tempPort.close() }
        }
    }

    private fun normalizeAddressOrNull(address: String): String? {
        val trimmed = address.trim()
        return when {
            trimmed.isBlank() -> null
            trimmed.startsWith("/") -> trimmed
            else -> "/$trimmed"
        }
    }

    private fun addListener(port: OSCPortIn, address: String, command: String, config: OscEditorConfig) {
        if (address.isBlank()) return
        if (!address.startsWith("/")) {
            lprintln("warning,osc-editor", "ignoring OSC editor address '$address' because it does not start with /")
            return
        }

        val listener = OSCMessageListener { event ->
            runCatching { handle(event, command, config) }
                .onFailure { lprintln("warning,osc-editor", "OSC editor message failed: ${it.message}") }
        }
        port.dispatcher.addListener(OSCPatternAddressMessageSelector(address), listener)
    }

    private fun handle(event: OSCMessageEvent, command: String, config: OscEditorConfig) {
        val address = event.message.address ?: ""
        if (command == "float") {
            val value = lastNumber(event) ?: return
            _send(
                Message("osc-editor").payload(
                    OscEditorCommand(
                        command = "float",
                        address = address,
                        value = value,
                        text = formatFloat(value),
                        debounceMs = config.debounceMs,
                        maxExecutionMs = config.maxExecutionMs,
                        delta = config.deltaMode
                    )
                )
            )
            return
        }

        val last = lastNumber(event)
        if (last != null && abs(last) <= 1.0e-9) return
        _send(
            Message("osc-editor").payload(
                OscEditorCommand(
                    command = command,
                    address = address,
                    debounceMs = config.debounceMs
                )
            )
        )
    }

    private fun lastNumber(event: OSCMessageEvent): Double? {
        val arguments = event.message.arguments ?: return null
        var found: Double? = null
        for (argument in arguments) {
            when (argument) {
                is Number -> found = argument.toDouble()
                is String -> argument.toDoubleOrNull()?.let { found = it }
            }
        }
        return found
    }

    private fun formatFloat(value: Double): String {
        val symbols = DecimalFormatSymbols(Locale.US)
        val format = DecimalFormat("0.#######", symbols)
        return format.format(value)
    }
}
