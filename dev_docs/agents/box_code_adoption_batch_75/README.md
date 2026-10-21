# Own MuScriptor and speech pipelines and retain usable result handles

Frozen findings: `d95e3a0a6eb8-01`, `2c067afda547-01`, `2c067afda547-02`, `50875a2740d1-01`, `50875a2740d1-02`, `b4a34b4f3611-01`.

MuScriptor and speech live examples now use box-owned coroutine workflows with the existing `trackAnalysis` helper. Each progress socket, await and final inspection belongs to the initiating run. Concurrent speech jobs and projection futures are awaited in a coroutine scope, so cancellation or failure cancels siblings. Errors are escaped and reported once. MuScriptor's typed notes and MIDI downloads are awaited by that same coroutine; the page publishes `transcriptionResult` as a typed box property and its follow-on explicitly awaits that durable result in the same box.

Both speech examples build projection captions from the actual start, duration, pool and hop controls. Pool/hop are explicitly nullable to retain the advertised raw-frame option. Changed controls (1.2 s, 3.6 s, 400 ms, 60 ms) were checked against each actual authored caption expression. Model selection, layer, fit distance, dimensions, seed, perplexity and visualization dimensions remain intact.

All four complete live bodies ran against the identity-verified private real audio API with an explicitly substituted six-second WAV. Real MuScriptor and speech workers are unavailable on this setup; each pipeline propagated its actual terminal worker-connection error through the owning coroutine. Exact messages are in behavior.json. These are failure-path checks, not successful neural transcription, embedding, t-SNE or MIDI-download acceptance. The complete typed follow-on compiles. Existing real trackAnalysis Stop/socket and coroutine/native cancellation evidence from batches 43 and 74 is retained rather than repeated. No model output, public capture or missing worker result was substituted.

Actual import/ktfmt and collection conversion cover all changed authored containers. Parsed source, literal, fragment and prose checks pass. Full guards belong to the lander.

Checkpoint: 387/405 addressed, 18 open. Mechanical coverage: Actual import/ktfmt and typed collection conversion across all changed containers; parsed source, literals and prose verified. Full guards are delegated to the lander.
