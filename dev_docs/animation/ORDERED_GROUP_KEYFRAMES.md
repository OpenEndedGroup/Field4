# Ordered group keyframes

`OrderedKeyframeGroup` extends native keyframes to numeric rules over an ordered collection.
It has no video, graphics or fixed-eight dependency. `StereoVideoGroupLooks` is its first adapter.

- Lane values are numbers or `SourceBackedFunction<Int, Number>` callables. Box syntax is
  `sourceBlock { index: Int -> expression }`; plain function objects are refused because they
  cannot survive persistence.
- `KValue.SourceFunction` stores the complete callable expression and owning box. Existing
  zero-argument `SourceBackedBlock` values keep their separate representation and code-blend rules.
- `group.capture()` keeps the rules. `group.resolve(key)` returns immutable sampled lists without
  mutation. `group.apply(key)` uses the scope's batch preparation route, also used by browser Apply.
- `scope.applicationBatch(id, names, prepare)` prepares all participating batches before any
  commits or ordinary setter writes. A commit must not fail. This is validation atomicity, not a
  rollback system for arbitrary user side effects. Unbatched setters retain their original contract.
- `scope.interpolators` provides per-lane interpolation. Group lanes blend function outputs, not
  program syntax. Generated intermediate rules remain persistent and retain each endpoint's owner.
- `transition(a,b,seconds,durationSeconds,staggerSeconds)` supplies indexed progress. Zero duration
  cuts. Negative stagger is rejected; reverse the application's member order for reverse travel.
- `sampledRule(boxId, values)` imports old per-member values: constants collapse; differences become
  explicit indexed lookups. Sampled and intermediate rules are tied to the sampled group size.
- Functions restore once through the existing source-block compiler. The side engine now receives
  the gathered inherited preamble and refreshes when its declarations change. Keep compilations
  off graphics/audio threads. Live functions retain normal closure behaviour; restored source
  requires durable properties or self-contained literals, not vanished local captures.

The video adapter registers appearance and shutter lanes, validates complete shutters before
writes and registers shared speed as an independently prepared global batch. Recalling compiles
and validates its destination before starting a control-loop transition; no decoder or GPU waits
are involved. Auto-exposure global settings, ring geometry and transport controls remain available
as in video_two. Inspector changes need an explicit Import before shared-rule capture.

Verification:

```sh
./gradlew jvmTest --tests 'afield.animation.keyframes.OrderedKeyframeGroupTest' \
  --tests 'afield.animation.keyframes.KeyframerTest' \
  --tests 'afield.animation.keyframes.KeyframeBrowserTest' \
  --tests 'afield.app.server.KeyframeBrowserProtocolTest' checkKDocAttachment
uv run --project . --group backdoor python tools/field_backdoor.py --json \
  --document ordered_group_test \
  --command 'macro write ordered_group_keyframes --file dev_docs/agents/transcripts/ordered_group_keyframes.yaml' \
  --command 'macro run ordered_group_keyframes'
```

The transcript exercises the real K2 macro and source-only rehydration, including inherited
properties and saved midpoint evaluation. Unit tests cover native batch ordering, validation,
scalar/function interpolation, persistence metadata, arbitrary member counts and source parsing.

`templates/video_three/build_transcript.py` also emits `verify.json`. Replay it after launching a
disposable carousel: it checks function application on every real surface, native save/reload,
complete shutter validation before writes and unchanged global speed, then restores the original
look in a finally block. Stop with the generated `stop.json`. No headset is needed in desktop mode.
