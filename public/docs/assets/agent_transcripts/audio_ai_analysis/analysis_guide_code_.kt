import afield.stdlib.noir.audioai.*

display("guide", """
<section style="width:1040px;max-width:100%;box-sizing:border-box;background:#151a20;color:#dbe3e9;padding:18px;border:1px solid #303c46;border-radius:8px;font:13px/1.5 system-ui,sans-serif">
  <h2 style="margin:0 0 8px;color:#7fcbd7">Beat and pitch analysis</h2>
  <p><b>librosa</b> supplies the onset envelope, multi-scale tempograms, beat grid and monophonic pYIN track. <b>Basic Pitch</b> supplies polyphonic note events, activations, pitch bends and MIDI. Essentia is not loaded by this worker.</p>
  <p>Run box 2 for a model-free visualizer tour. Run box 3 for the complete live path: filename lookup → source window → three submitted jobs → WebSocket progress → durable results → synchronized analysis board. The source player and every analysis lane share hover, zoom and playback time; click any plot to seek the recording. Re-running unchanged requests is a cache hit.</p>
</section>
""".trimIndent())
display("all durable jobs", fieldAudioJobMonitor(heightPixels = 430))
