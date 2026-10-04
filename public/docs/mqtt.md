# MQTT: sensors, devices and brokers

`afield.mqtt` lets a box publish and subscribe over MQTT, the small publish/subscribe protocol that
ESP32 and Arduino sketches, Node-RED, Home Assistant and TouchDesigner all speak. Messages go to a
**broker**; anything subscribed to a matching **topic** (a path such as `sensors/kitchen/temp`)
receives them. Field includes its own broker, so nothing needs installing to try it.

Open `public/docs/assets/mqtt_demo/` as a document. Box 1 plays three pretend sensors, box 2 listens
to every temperature, box 3 plots them, and box 4 shows a retained message.

## Five lines to a sensor feed

This subscribes to every room's temperature and shows the latest one:

```diffstatic {data-lang=kotlin}
import afield.mqtt.*

mqtt.subscribe("sensors/+/temp") { topic, message ->
    markdown("**$topic** = ${message.number}")
}
```

`+` stands for exactly one level of the topic, so this matches `sensors/kitchen/temp` and
`sensors/hall/temp` but not `sensors/kitchen/humidity`. `#`, as the last level, matches everything
below: `sensors/#`.

The callback runs on Field's main loop, like the rest of your box, so it can draw, set sliders and
display output directly. Re-running the box replaces the callback rather than adding a second one.
Stopping the box leaves the subscription in place, so a feed keeps arriving while you work on the
box that uses it; `mqtt.unsubscribe()` ends it.

## Publishing

`publish` sends text, numbers, bytes, or JSON for a `Map` or `List`:

```diffstatic {data-lang=kotlin}
mqtt.publish("lights/stage/level", 0.8)
mqtt.publish("lights/stage/colour", mapOf("r" to 255, "g" to 120, "b" to 0))
mqtt.publish("installation/scene", "dusk", qos = 1, retain = true)
```

`qos = 1` asks the broker to acknowledge the message; Field keeps it and sends it again after a
reconnect until it is acknowledged. A **retained** message stays on the broker, and every later
subscriber receives it immediately: this is how a device that has just booted learns the current
state. Publishing an empty retained message (`mqtt.clearRetained(topic)`) removes it.

## Reading values without a callback

Often you only want the latest value. `track` subscribes without a callback, and then `number`,
`text` and `series` read what has arrived:

```diffstatic {data-lang=kotlin}
mqtt.track("sensors/#")
loop {
    showSignals(
        "kitchen" to mqtt.series("sensors/kitchen/temp"),
        "garden" to mqtt.series("sensors/garden/env", "temp"),
    )
    delay(500)
}
```

`series` keeps the last 512 numbers on a topic. Many devices send JSON such as
`{"temp": 12.5, "humidity": 70}`; give a field name (or a dotted path, `"env.temp"`, `"values.2"`)
as the second argument to `series` or `number`. On a message itself, `message.number("temp")`,
`message.string("name")` and `message.json` do the same.

## Which broker

`mqtt` on its own uses Field's built-in broker on port 1883 (or a free port, if something else
already has 1883). It listens only on this machine. To let devices on your network connect to it,
start it bound to every interface before anything uses `mqtt`, and point the devices at this
machine's address:

```diffstatic {data-lang=kotlin}
mqttBroker(bindAddress = "0.0.0.0")
```

To use a broker that is already running somewhere — Mosquitto, a Home Assistant install, a cloud
service — name it:

```diffstatic {data-lang=kotlin}
val home = mqtt("tcp://192.168.1.20:1883", username = "field", password = "secret")
home.subscribe("zigbee2mqtt/+") { topic, message -> print("$topic ${message.text}") }
```

Setting the environment variable `AFIELD_MQTT_URL` makes that broker the default for plain `mqtt`.
Pass `will = "field/status" to "offline"` and the broker publishes that message, retained, if Field
disappears without disconnecting.

The connection is made once per broker and reconnects by itself, resubscribing, if the broker
restarts or the network drops. `print(mqtt)` shows its state and message counts.

## What this does not do

- MQTT 3.1.1 only; MQTT 5 brokers accept 3.1.1 clients, so this matters only for v5-only features.
- No TLS (`mqtts://`, port 8883) and no WebSocket transport.
- QoS 2 is accepted but delivered as QoS 1: a message can arrive twice, never zero times.
- The built-in broker keeps no sessions between connections and has no authentication.
