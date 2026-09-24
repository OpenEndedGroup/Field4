# Direct-lighting laboratory

Launch `dev_docs/agents/transcripts/direct_lighting.yaml` in Field, or open the generated starting
point at `public/docs/assets/agent_transcripts/direct_lighting/metadata.edn`.

`example.kt` is the editable scene box, beneath a renderer parent. Launch it (rather than evaluating
only declarations). Its `frames` expression must remain last: a later expression would prevent Field
from starting the animation. Relaunching closes the old experiment and installs a new one.

Controls select source/unshadowed/ray-query lighting, animation, sample count, radiance, effect weight
and diagnostics. Arrow keys move the native-window camera through `keyCamera`.

See `public/docs/direct_lighting.md` for API contracts, captures, tests and limitations; see
`dev_docs/lighting/IMPLEMENTATION.md` for the local private-layer compilation workaround.

Run `uv run python tools/lighting/build_rtxdi.py --prepare` once for optional RTXDI. The same box
then exposes RTXDI/reference, temporal reuse, spatial neighbors, light count and history controls.
Without that cache or ray-query support, it retains the baseline controls. Source output is always
available. RTXDI samples stay raw: there is no color-history filter or denoiser.
