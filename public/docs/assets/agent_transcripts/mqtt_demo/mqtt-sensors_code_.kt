import afield.io.mqtt.*

// Three pretend sensors publishing to the embedded broker ten times a second.
// The kitchen and hall send plain numbers; the garden sends JSON, as many ESP32 sketches do.
// Point real devices at this machine's broker instead and delete this box.
val start = AnimationClock.nowSeconds()
loop {
    val t = AnimationClock.nowSeconds() - start
    mqtt.publish("sensors/kitchen/temp", 21.0 + sin(t * 0.7) * 1.5)
    mqtt.publish("sensors/hall/temp", 18.0 + sin(t * 0.3 + 1.0) * 2.0)
    mqtt.publish("sensors/garden/env", mapOf("temp" to 12.0 + sin(t * 0.2) * 4.0, "humidity" to 60 + 20 * cos(t * 0.5)))
    delay(100)
}
