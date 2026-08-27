import afield.stdlib.noir.audioai.*

display("guide", """
<section style="width:1040px;max-width:100%;box-sizing:border-box;background:#151a20;color:#dbe3e9;padding:18px;border:1px solid #303c46;border-radius:8px;font:13px/1.5 system-ui,sans-serif">
  <h2 style="margin:0 0 8px;color:#7fcbd7">Short-time spectral spaces</h2>
  <p>One lightweight <code>analysis.embed_spectral</code> job records a 25 Hz trajectory as normalized mel mass, absolute log-mel, MFCC, energy, centroid, flatness, rolloff, and flux. Field owns temporal pooling, distances, MDS/t-SNE, and audition.</p>
  <p><b>Mel Wasserstein</b> measures how far spectral mass moves along a perceptual frequency axis. <b>Log-mel</b> retains detailed band shape. <b>MFCC</b> gives a compact broad spectral envelope. Run box 2 without any service; run box 3 for import → submit → monitor → await → decode → three Smile t-SNE fits → synchronized playback.</p>
</section>
""".trimIndent())
display("all durable jobs", fieldAudioJobMonitor(heightPixels = 430))
