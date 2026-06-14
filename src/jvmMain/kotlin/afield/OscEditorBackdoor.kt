package afield

import afield.logger.lprintln
import com.illposed.osc.OSCMessageEvent
import com.illposed.osc.OSCMessageListener
import com.illposed.osc.messageselector.OSCPatternAddressMessageSelector
import com.illposed.osc.transport.OSCPortIn
import com.illposed.osc.transport.OSCPortInBuilder
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

private data class OscEditorConfig(
    val enabled: Boolean,
    val port: Int,
    val floatAddress: String,
    val launchAddress: String,
    val stopAddress: String,
    val debounceMs: Int
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
            debounceMs = preferences.oscEditorDebounceMs.coerceIn(0, 5_000)
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
            val value = firstNumber(event) ?: return
            _send(
                Message("osc-editor").payload(
                    OscEditorCommand(
                        command = "float",
                        address = address,
                        value = value,
                        text = formatFloat(value),
                        debounceMs = config.debounceMs
                    )
                )
            )
            return
        }

        val first = firstNumber(event)
        if (first != null && abs(first) <= 1.0e-9) return
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

    private fun firstNumber(event: OSCMessageEvent): Double? {
        val arguments = event.message.arguments ?: return null
        for (argument in arguments) {
            when (argument) {
                is Number -> return argument.toDouble()
                is String -> argument.toDoubleOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun formatFloat(value: Double): String {
        val symbols = DecimalFormatSymbols(Locale.US)
        val format = DecimalFormat("0.#######", symbols)
        return format.format(value)
    }
}
