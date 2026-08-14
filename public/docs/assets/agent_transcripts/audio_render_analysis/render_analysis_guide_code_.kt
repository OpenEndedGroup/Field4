import afield.stdlib.noir.audioai.*

display("guide", """
<section style="width:1040px;max-width:100%;box-sizing:border-box;background:#151a20;color:#dbe3e9;padding:18px;border:1px solid #303c46;border-radius:8px;font:13px/1.5 system-ui,sans-serif">
  <h2 style="margin:0 0 8px;color:#7fcbd7">Field synthesis → Field Audio analysis</h2>
  <p>Each box below contains the whole loop: make sound, write WAV, import immutable bytes, submit named jobs, stream progress, await results, and draw a synchronized waveform/beat/pitch board. Re-run unchanged code and the content-addressed import plus operation cache return the same results.</p>
  <p><b>Pat, Dirt and Dough</b> render offline. <b>Faust</b> compiles and renders on a daemon future because its first compiler warm-up is slow. <b>Sonic Pi</b> records in real time on that same background executor. None blocks Field's run loop.</p>
  <p>The local <code>RenderedAudioFile.provenance</code> records the producing box and render parameters. The server asset id remains a hash of the WAV bytes; the Field document remains the authority for source code and arrangement.</p>
</section>
""".trimIndent())
display("all durable jobs", fieldAudioJobMonitor(heightPixels = 390))
