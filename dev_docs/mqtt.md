# afield.mqtt — implementation notes

Pure-Kotlin MQTT 3.1.1 (no library dependency), added 2026-10-04 on `nightly/2026-10-04/1-mqtt-bridge`.
User page: `public/docs/mqtt.md`. Demo: `public/docs/assets/mqtt_demo/`.

## Files
- `src/jvmMain/kotlin/afield/mqtt/MqttCodec.kt` — packets, remaining-length varint, topic matching.
- `MqttBroker.kt` — embedded broker; thread per connection; clean sessions only.
- `MqttClient.kt` — client; reader thread owns keepalive; `dispatch` decides the callback thread.
- `MqttBox.kt` — `Box.mqtt`, `Box.mqtt(url)`, `Box.mqttBroker`, `MqttConnections`.

## Lifecycle decisions
- Clients (keyed by url) and brokers (keyed by port) survive box stop, per the house rule that
  launched things survive stop. Subscriptions are keyed `boxId|url|key`; a re-run with the same
  filter swaps the handler in place (no UNSUBSCRIBE/SUBSCRIBE, so no retained replay on re-run).
- Not yet released on box *delete*: a deleted box's subscriptions keep firing until
  `MqttConnections.closeAll()` or `mqtt.unsubscribe()`. Wiring into `ReleasePolicy.KEYED`
  (`LifecycleLedger.kt`) is the follow-up.
- Callbacks are queued and drained in `Runloop.preRun["afield.mqtt.dispatch"]` (≤2000 per frame,
  `setUnowned`, permanent). With no Runloop (`Runloop.mainThread == null`: tests, jvm-main) they run
  on the reader thread.
- `series` history is recorded from every received message (numeric payloads, plus numeric JSON
  leaves as `topic|dotted.path`), only for topics something subscribed to; `track()` subscribes
  without a callback.

## Verification (2026-10-04)
- `./gradlew jvmTest --tests 'afield.mqtt.*'`: 21 tests (codec spec bytes, §4.7 wildcard matrix,
  broker/client over loopback: QoS 1, QoS 2 downgrade, retained + clear, will vs clean close,
  keepalive timeout, reconnect + resubscribe, QoS 1 queued across an outage, id takeover, v5
  CONNECT refused with CONNACK 1, JSON helpers, demo bundle parse, re-run handler replacement).
  Passed 4 consecutive runs.
- All four demo boxes run one at a time through the backdoor (port 9611) on a copy of the bundle;
  outputs inspected in screenshots (live plot of three series, retained message with
  `retained: true`).
- Not checked: interop with a third-party broker or client (mosquitto not installed), MQTT 5, TLS.
