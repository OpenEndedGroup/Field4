import afield.stdlib.noir.audioai.*

display("guide", """
<section style="width:1040px;max-width:100%;box-sizing:border-box;background:#151a20;color:#dbe3e9;padding:18px;border:1px solid #303c46;border-radius:8px;font:13px/1.5 system-ui,sans-serif">
  <h2 style="margin:0 0 8px;color:#7fcbd7">Corpus microscope</h2>
  <p>Run box 2 for the complete path: resolve Nancy and Candlestick by filename; submit and await multi-scale segmentation and grouped descriptor jobs; query a six-second Nancy window; then audition the nearest windows and automatic source-to-neighbour splices.</p>
  <p>Every descriptor is an immutable NPZ asset. Retrieval weights remain visible and editable: harmony, pitch, texture, rhythm and dynamics. Players from different files share relative time, so hover, zoom, seek and the playback cursor line up without pretending that their file timestamps are the same.</p>
</section>
""".trimIndent())
display("all durable jobs", fieldAudioJobMonitor(heightPixels = 410))
