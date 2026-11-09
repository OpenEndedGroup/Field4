# body_one

A small, live OpenXR tracking inspector: hands, sparse WiVRn body trackers, controller input,
orientation axes, body bounds and closest-point queries. It needs no private particle runtime.

Connect the headset to WiVRn, then start a fresh Field JVM and launch boxes **1, 2, 3** in order.
The source files in this directory generate the public starting point:

```sh
uv run --group backdoor python templates/body_one/build_transcript.py \
  --transcript dev_docs/agents/transcripts/body_one.yaml
./gradlew formatBoxCode --args="dev_docs/agents/transcripts/body_one.yaml"
./gradlew regenerateAgentTranscriptStartingPoints
```

The builder also emits `create.json`, `launch.json`, `stop.json` in `/tmp/body-one-transcripts`.
Use the backdoor's macro commands to apply these to a new document. Avoid replacing an existing
user document when installing the example.

See [the guide](guides/01-body-tracking.md) and the public **Body and hand tracking** page.

## Refresh live documentation captures

The `linux-wivrn-live` profile is separate from ordinary GPU captures. Launch this document
with the Quest connected, then run:

```sh
FIELD_DOCS_CAPTURE_PROFILES=linux-wivrn-live \
FIELD_DOCS_RUNNER_MODE=always \
FIELD_DOCS_RUNNER_PORT=9092 \
FIELD_DOCS_RUNNER_WORKSPACE=/path/to/AFieldWorkspace \
uv run --group docs python tools/build_zensical_docs.py --page body_tracking.md
```

This profile attaches to the live server without adopting or restarting it. Its setup macro
requires real tracking and captures the native preview. UI drives frame the existing control
panels without changing their values. Ordinary docs builds retain the committed pictures and
need neither WiVRn nor a headset.
