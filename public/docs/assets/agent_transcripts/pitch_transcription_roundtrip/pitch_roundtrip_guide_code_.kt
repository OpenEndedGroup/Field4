import afield.four.synth.*

display("guide", """
<section style="width:1040px;max-width:100%;box-sizing:border-box;background:#151a20;color:#dbe3e9;padding:18px;border:1px solid #303c46;border-radius:8px;font:13px/1.55 system-ui,sans-serif">
  <h2 style="margin:0 0 8px;color:#7fcbd7">notation → sound → notation</h2>
  <p>Field already rendered notation to sound exactly, and already heard <b>rhythm</b> back
  (<code>PatOnsetDetection.kt</code>). What it could not do was hear <b>pitch</b>, or write
  what it heard back out as something it can re-render. These boxes are that half.</p>
  <p>Every box below is pure CPU: <code>patRenderStereo</code> or <code>doughRenderMono</code>
  makes a finite buffer, <code>patTranscribe</code> reads it, and the figure overlays the
  notation Field authored (hollow outline) on what the detector heard (solid bar) on one
  shared MIDI axis. Nothing here talks to <code>services/field-audio-ai</code>, opens an audio
  device, or needs model weights.</p>
  <p>The measurement is unusually cheap to trust: the reference is not somebody's annotation
  of the audio, it <i>is</i> the audio's cause. Box 2 is the clean case. Boxes 3 and 5 are
  the two ways this fails on purpose. <b>Box 4 is the one to poke</b> — it has the single
  parameter that matters most, and a comment saying what should happen when you change it.</p>
  <p style="margin:12px 0 0;color:#98a6b3">Pitch error is reported in <b>cents</b>, not in note
  names. One of the corpus cases is exactly 50 cents from equal temperament, where the correct
  note name genuinely does not exist — a measurement 0.1 cents out flips it a semitone.</p>
</section>
""".trimIndent())

display("resolution", """
    the analysis window is 2·(sampleRate/minHz) of frame plus one longest lag,
    so the register you admit sets the shortest note you can resolve:
      minHz  65 Hz (the wide default) → ${patPitchFrameSpan(PatSoundDocs.SR, 65.0)} samples = ${"%.1f".format(1000.0 * patPitchFrameSpan(PatSoundDocs.SR, 65.0) / PatSoundDocs.SR)} ms
      minHz 261 Hz (a treble line)    → ${patPitchFrameSpan(PatSoundDocs.SR, 261.0)} samples = ${"%.1f".format(1000.0 * patPitchFrameSpan(PatSoundDocs.SR, 261.0) / PatSoundDocs.SR)} ms
    That ratio is what box 4 is about.
""".trimIndent())
