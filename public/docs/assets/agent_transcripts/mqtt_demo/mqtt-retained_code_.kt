import afield.io.mqtt.*

// A retained message stays on the broker: anything that subscribes later gets it straight away.
// This is how a device learns the current scene when it boots.
mqtt.publish("installation/scene", mapOf("name" to "dusk", "brightness" to 0.4), qos = 1, retain = true)

mqtt.subscribe("installation/#") { topic, message ->
    markdown(
        "`$topic`: **${message.string("name")}** at ${message.number("brightness")} (retained: ${message.retained})",
    )
}
mqtt
