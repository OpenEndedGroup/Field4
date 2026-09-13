import afield.stdlib.noir.audioai.*

display("guide", """
  <section style="width:1040px;max-width:100%;box-sizing:border-box;background:#151a20;color:#dbe3e9;padding:18px;border:1px solid #303c46;border-radius:8px;font:13px/1.5 system-ui,sans-serif">
    <h2 style="margin:0 0 8px;color:#7fcbd7">Experiment in Terror · Underfit LoRA</h2>
    <p>This request uses 165 twelve-second chunks from the complete Henry Mancini album as training material. The pinned rank-16 DoRA checkpoint is applied to Stable Audio 3's small-music base model, with a 12-second source window extended to a 30-second candidate.</p>
    <p>Set <code>FIELD_AUDIO_UNDERFIT_LORA_ROOT</code> and start the Underfit worker before running box 2. The worker must have the staged base model and the checkpoint beneath that root. The request retains its model revisions, checkpoint digest, seed, and sampling controls in the durable candidate provenance.</p>
  </section>
""".trimIndent())
