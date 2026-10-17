import afield.io.mqtt.*

// Keep the latest value and recent history of everything under sensors/, and plot it twice a second.
// `series` holds the last 512 numbers per topic; `number(topic, "temp")` reads a field out of JSON.
mqtt.track("sensors/#")
loop {
    showSignals(
        "kitchen" to mqtt.series("sensors/kitchen/temp"),
        "hall" to mqtt.series("sensors/hall/temp"),
        "garden" to mqtt.series("sensors/garden/env", "temp"),
        title = "Temperatures (°C)",
    )
    markdown("info", "garden: %.1f °C, %.0f %% humidity".format(
        mqtt.number("sensors/garden/env", "temp"), mqtt.number("sensors/garden/env", "humidity")))
    delay(500)
}
