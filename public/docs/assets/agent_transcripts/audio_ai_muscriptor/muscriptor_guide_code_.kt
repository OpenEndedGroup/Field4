import afield.stdlib.noir.audioai.*

display("guide", """
<section style="width:1040px;max-width:100%;box-sizing:border-box;background:#151a20;color:#dbe3e9;padding:18px;border:1px solid #303c46;border-radius:8px;font:13px/1.5 system-ui,sans-serif">
  <h2 style="margin:0 0 8px;color:#7fcbd7">MuScriptor · recording to an instrument-labelled score</h2>
  <p><code>analysis.transcribe_music</code> is one model-neutral operation with three revision-pinned worker capabilities: small, medium, and large. It retains onset, offset, MIDI pitch, instrument group, activity curves, MessagePack events, performance-timed MIDI, and optional beat-quantized MIDI.</p>
  <p>Run box 2 without a server to explore the synchronized piano roll. Replace box 3's <code>sourcePattern</code> with a recording you have the rights to transcribe, then run it for asset lookup → submit → progress → durable result → source/score audition → MIDI download. Unchanged operations are cache hits.</p>
  <p style="color:#e5bd77">The code is MIT, but the gated model weights are CC BY-NC 4.0. Accept the selected model's Hugging Face terms before starting the worker.</p>
</section>
""".trimIndent())
display("all durable jobs", fieldAudioJobMonitor(heightPixels = 430))
