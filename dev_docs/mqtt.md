# afield.io.mqtt — implementation notes

Pure-Kotlin MQTT 3.1.1 (no library dependency), added 2026-10-04 on `nightly/2026-10-04/1-mqtt-bridge`.
User page: `public/docs/mqtt.md`. Demo: `public/docs/assets/mqtt_demo/`.

## Files
- `src/jvmMain/kotlin/afield/io/mqtt/MqttCodec.kt` — packets, remaining-length varint, topic matching.
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
- Callbacks are queued and drained in `Runloop.preRun["afield.io.mqtt.dispatch"]` (≤2000 per frame,
  `setUnowned`, permanent). With no Runloop (`Runloop.mainThread == null`: tests, jvm-main) they run
  on the reader thread.
- `series` history is recorded from every received message (numeric payloads, plus numeric JSON
  leaves as `topic|dotted.path`), only for topics something subscribed to; `track()` subscribes
  without a callback.

## Verification (2026-10-04)
- `./gradlew jvmTest --tests 'afield.io.mqtt.*'`: 21 tests at first, 25 now (+ retire, retained ordering, OSC, DMX) (codec spec bytes, §4.7 wildcard matrix,
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
- Interop with Eclipse Mosquitto 2.1.2 (Homebrew), `MqttMosquittoInteropTest`, opt-in: it skips
  through a JUnit assumption when `mosquitto` is absent (`MOSQUITTO_HOME` overrides the search),
  starts its own broker on a free port per test, and destroys every process in teardown.
  - `MqttClient` -> `mosquitto`: connect; `+` and `#` subscriptions; QoS 0 and 1 both ways
    (mosquitto logs our PUBACK); retained both ways and cleared by an empty retained publish;
    last will on a socket closed with RST and no DISCONNECT, none on `close()`; keepalive 1 s
    (>= 3 PINGREQ, no timeout, no reconnect); broker restart -> reconnect + resubscribe;
    20 kB and 3 MB payloads both ways (needs `max_packet_size` in mosquitto.conf: 2.1's default
    disconnects with "oversize packet"); unicode topics both ways; `mosquitto_pub -q 2` to a
    QoS 2 subscription arrives once and the PUBREC/PUBREL/PUBCOMP flow completes (mosquitto
    grants QoS 2 to us; the downgrade to 1 is only our broker's).
  - `mosquitto_pub`/`mosquitto_sub -V mqttv311` -> `MqttBroker`: `+`/`#`, QoS 0/1/2 (2 completes
    as PUBREC/PUBCOMP, forwarded as 1), retained + clear, will of a SIGKILLed `mosquitto_sub`,
    3 MB payload (with `maxPacketSize` raised; the default is 1 MiB), unicode topics.
    `-V mqttv5` is refused and the broker stays healthy.
  - mosquitto logs scanned for protocol errors / malformed packets: none.
  - Bugs it found, fixed with regression tests in `MqttInteropRegressionTest` (no mosquitto
    needed): the client could write a PUBLISH or SUBSCRIBE before CONNECT (now nothing goes out
    before CONNACK; it is queued); an MQTT 5 CONNECT got a 3.1.1 CONNACK that `mosquitto_pub`
    reported as "Malformed packet" (now a v5 CONNACK, reason 0x84 "unsupported protocol version").
  - Mosquitto sends one copy per matching subscription, so overlapping filters (`a/+` and `a/#`)
    fired every handler twice. A first fix (32c29e39f) swallowed identical copies within 250 ms,
    which also swallowed genuine repeated readings; it is gone. Now the client keeps its handlers
    apart from the filters it subscribes at the broker, which are a *cover*
    (`MqttTopic.subsumes` / `MqttTopic.cover`): a filter another one subsumes sends no SUBSCRIBE,
    a filter that subsumes others is subscribed first and the subsumed ones unsubscribed after
    (no gap; a message may arrive twice during the swap), a higher-QoS filter re-subscribes its
    cover at that QoS, unsubscribing recomputes the same way, and reconnecting subscribes the
    cover. Each PUBLISH goes once to every handler whose filter matches. `MqttCoverScenarios`
    runs against both brokers: one copy per overlapping handler, 10 identical payloads in ~100 ms
    all arrive, retained replay to a subsumed late subscriber, cover swap under a QoS 1 stream
    with no loss, QoS upgrade.
  - Retained replay for a subsumed filter comes from a client-side cache of retained messages
    seen (retain flag set; emptied by an empty retained payload; updated by the client's own
    retained publishes). It knows only what the cover delivered, and since brokers forward live
    messages without the retain flag, a different live payload on a cached topic evicts the entry
    rather than risk replaying a stale value — so after that, a subsumed late subscriber gets no
    replay for that topic.
  - Known limitation: filters that overlap without either subsuming the other (`a/+/c` and
    `a/b/+`) are both subscribed, and mosquitto sends `a/b/c` twice; each matching handler gets
    both. Not deduplicated: in 3.1.1 nothing identifies two copies as the same message (QoS 1/2
    packet ids differ per copy, QoS 0 has none), and a time window drops real repeats. The correct
    method is MQTT 5 subscription identifiers (each copy names its subscription), which this
    3.1.1 client does not speak. Retained messages re-replayed when a new cover filter or a QoS
    upgrade makes the broker resend them also reach existing handlers again.
- Not checked: TLS, WebSockets, persistent sessions against mosquitto (`cleanSession = false`),
  username/password against an authenticating broker, other brokers (EMQX, HiveMQ, AWS IoT).
