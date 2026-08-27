import afield.stdlib.noir.audioai.*

display("guide", """
<section style="width:1040px;max-width:100%;box-sizing:border-box;background:#151a20;color:#dbe3e9;padding:18px;border:1px solid #303c46;border-radius:8px;font:13px/1.5 system-ui,sans-serif">
  <h2 style="margin:0 0 8px;color:#7fcbd7">Speech-reactive learned spaces</h2>
  <p><b>emotion2vec</b> emphasizes vocal affect, prosody and texture. <b>WavLM Base+</b> supplies a broader self-supervised speech representation; layer 6 is a useful acoustic/phonetic compromise. Both produce 50 Hz frame vectors, explicit WebRTC voice activity, energy, novelty, and a compact binary MessagePack artifact.</p>
  <p>Run box 2 to inspect the synchronized visualizer without a worker. Run box 3 for the complete local path: find or import <code>~/Downloads/near_drowning.wav</code> → submit two durable jobs → watch progress → retrieve cached results → pool 500 ms of acoustic evidence every 40 ms (25 Hz) → run fixed-seed cosine t-SNE in Field through Smile → play the recording through axes 0×1 and 2×3. Hover a path to inspect its time; click it to seek the audio.</p>
</section>
""".trimIndent())
display("all durable jobs", fieldAudioJobMonitor(heightPixels = 430))
