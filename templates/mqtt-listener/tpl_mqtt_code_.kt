import afield.io.mqtt.*

// The last eight messages under `field/#`, newest first, from the MQTT broker: an embedded one
// unless AFIELD_MQTT_URL names another. Run this box; the publish at the end sends one to see.
val recent = ArrayDeque<String>()
mqtt.subscribe("field/#") { topic, message ->
    recent.addFirst("**$topic** = ${message.text}")
    while (recent.size > 8) recent.removeLast()
    markdown(recent.joinToString("\n\n"))
}

mqtt.publish("field/hello", "hello from $name")
