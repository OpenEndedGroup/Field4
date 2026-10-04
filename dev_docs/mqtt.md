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
- Each slot is owned by `box.runScopes.parent` as `ReleasePolicy.KEYED`, so `retireBoxes`
  (delete, history restore, document close) unsubscribes it; stop does not.
- On (re)connect the client sends queued QoS 1 publishes *before* resubscribing, so a retained
  publish made while connecting reaches a later subscribe as retained (the first runner capture
  showed `retained: false` before this). `Box.mqtt` waits up to 1 s for a newly made connection.
- Bridges: `toOsc(filter, oscClient)` and `toDmx(topic, out, universe, channel, path, range)` are
  ordinary keyed subscriptions.
- Callbacks are queued and drained in `Runloop.preRun["afield.mqtt.dispatch"]` (≤2000 per frame,
  `setUnowned`, permanent). With no Runloop (`Runloop.mainThread == null`: tests, jvm-main) they run
  on the reader thread.
- `series` history is recorded from every received message (numeric payloads, plus numeric JSON
  leaves as `topic|dotted.path`), only for topics something subscribed to; `track()` subscribes
  without a callback.

## Verification (2026-10-04)
- `./gradlew jvmTest --tests 'afield.mqtt.*'`: 21 tests at first, 25 now (+ retire, retained ordering, OSC, DMX) (codec spec bytes, §4.7 wildcard matrix,
  broker/client over loopback: QoS 1, QoS 2 downgrade, retained + clear, will vs clean close,
  keepalive timeout, reconnect + resubscribe, QoS 1 queued across an outage, id takeover, v5
  CONNECT refused with CONNACK 1, JSON helpers, demo bundle parse, re-run handler replacement).
  Passed 4 consecutive runs.
- All four demo boxes run one at a time through the backdoor (port 9611) on a copy of the bundle;
  outputs inspected in screenshots (live plot of three series, retained message with
  `retained: true`).
- Docs captures `assets/mqtt/{track_series,retained}.png` from
  `FIELD_DOCS_RUNNER_MODE=incremental tools/build_zensical_docs.py --page mqtt.md`, inspected.
- Guards: checkCodebaseReference, checkKDocAttachment, citationGates green (after recounting
  `logging.md` lprintln numerals). `run_python_tests.py`: 977/982, the 2 failures are
  `test_check_doc_offline_links` wanting `python_cv.md` in CARRIER_PAGES — present at base, untouched.
- Not checked: interop with a third-party broker or client (mosquitto not installed), MQTT 5, TLS.
