import afield.io.mqtt.mqtt

prop<String>(routingTopic)
var direct = false
var send = true
var forward = true

controlGroup("Links") {
    checkbox("Direct: console to screen", direct) { direct = it }
    checkbox("Console to relay", send) { send = it }
    checkbox("Relay to screen", forward) { forward = it }
}

loop {
    mqtt.publish(
        routingTopic,
        mapOf(
            "direct" to if (direct) 1 else 0,
            "send" to if (send) 1 else 0,
            "forward" to if (forward) 1 else 0,
        ),
    )
    delay(400)
}
