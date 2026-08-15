@file:afield.CompletionCategory("Audio AI")

package afield.stdlib.noir.audioai

import afield.stdlib.AudioVisualizer
import afield.stdlib.AudioVisualizerOptions
import afield.stdlib.AudioVisualizerSource
import java.util.Locale

/**
 * The candidate board: the instrument for choosing between the alternatives a generation produced.
 *
 * One function, one HTML fragment, three things a listener actually needs — a player per candidate,
 * the source-into-candidate splice preview beside it so a take can be judged *in place* rather than
 * in isolation, and a rating, selection flag and note that are written straight back to the service.
 * The provenance line under each player states the sample rates and the conversion, because whether
 * you are hearing the model or a resampler is not a detail.
 */

private fun candidateNumber(value: Double): String = String.format(Locale.US, "%.3f", value)
private fun candidatePercent(value: Double): String = String.format(Locale.US, "%.1f", value * 100)

private fun String.htmlEscaped(): String = buildString(length) {
    for (character in this@htmlEscaped) append(
        when (character) {
            '&' -> "&amp;"
            '<' -> "&lt;"
            '>' -> "&gt;"
            '"' -> "&quot;"
            '\'' -> "&#39;"
            else -> character
        }
    )
}

/**
 * A self-contained Field output-area candidate browser.
 *
 * Use `display("audio-ai", candidateBoardHtml(set))`. It draws decoded waveform
 * summaries in the browser, provides candidate and transition audition, and writes
 * ratings/selection/annotations back to the service. Field project persistence is
 * still handled separately by [FieldAudioPersistence].
 */
fun candidateBoardHtml(
    candidateSet: CandidateSet,
    serviceBaseUrl: String = "/field-audio",
): String {
    val base = serviceBaseUrl.trimEnd('/').htmlEscaped()
    val setId = candidateSet.id.htmlEscaped()
    val cards = candidateSet.candidates.joinToString("\n") { candidate ->
        val gesture = candidate.gestureEvaluation
        val fit = gesture?.overallScore ?: candidate.score
        val fitDetails = gesture?.laneScores?.entries?.sortedBy { it.key }?.joinToString("") { (name, score) ->
            """<span title="${name.htmlEscaped()}: ${candidateNumber(score)}"><b>${name.htmlEscaped()}</b><i><em style="width:${candidatePercent(score)}%"></em></i></span>"""
        }.orEmpty()
        val proxy = requireNotNull(candidate.proxyRepresentation) {
            "candidate ${candidate.index} has no inline proxy representation metadata"
        }
        val splice = candidate.splicePreviewRepresentation
        val native = candidate.nativeRepresentation
        val candidateUrl = "$base${proxy.binaryUrl.htmlEscaped()}"
        val candidatePlayer = AudioVisualizer.renderHtml(
            AudioVisualizerSource(
                audioUrl = candidateUrl,
                waveformUrl = AudioVisualizer.fieldAudioWaveformUrl(proxy.id, base),
                durationSeconds = proxy.durationSeconds,
                label = "candidate",
            ),
            AudioVisualizerOptions(width = 480, waveformHeight = 92, showPlayhead = true),
        )
        val splicePlayer = splice?.let {
            AudioVisualizer.renderHtml(
                AudioVisualizerSource(
                    audioUrl = "$base${it.binaryUrl.htmlEscaped()}",
                    waveformUrl = AudioVisualizer.fieldAudioWaveformUrl(it.id, base),
                    durationSeconds = it.durationSeconds,
                    label = "source → candidate",
                ),
                AudioVisualizerOptions(width = 480, waveformHeight = 72, showPlayhead = true),
            )
        }.orEmpty()
        """
        <article class="fa-card" data-index="${candidate.index}">
          <header><strong>${candidate.index + 1}</strong><span>seed ${candidate.seed}${fit?.let { " · gesture ${candidateNumber(it)}" } ?: ""}</span></header>
          $candidatePlayer
          $splicePlayer
          ${if (fitDetails.isEmpty()) "" else """<div class="fa-gesture-fit">$fitDetails</div>"""}
          <div class="fa-provenance">
            native ${native?.sampleRate ?: "?"} Hz / ${native?.channels ?: "?"} ch
            → proxy ${proxy.sampleRate ?: "?"} Hz / ${proxy.channels ?: "?"} ch
            · ${proxy.resamplingMethod ?: "unknown conversion"}
          </div>
          <div class="fa-judgement">
            <label>rating <input class="fa-rating" type="range" min="-1" max="1" step="0.1" value="${candidate.rating ?: 0.0}"></label>
            <label><input class="fa-selected" type="checkbox" ${if (candidate.selected) "checked" else ""}> selected</label>
            <input class="fa-note" type="text" placeholder="annotation" value="${candidate.annotation.orEmpty().htmlEscaped()}">
            <button class="fa-save">save</button><span class="fa-status"></span>
          </div>
        </article>
        """.trimIndent()
    }
    return """
    <section class="field-audio-board" data-set="$setId" data-base="$base">
      <style>
        .field-audio-board { color:#d8d8dc; background:#17181b; padding:14px; font:12px/1.35 system-ui,sans-serif; }
        .field-audio-board h3 { margin:0 0 12px; font-size:14px; font-weight:550; }
        .fa-grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(310px,1fr)); gap:10px; }
        .fa-card { background:#202227; border:1px solid #353840; border-radius:7px; padding:10px; }
        .fa-card header { display:flex; justify-content:space-between; color:#aeb2bd; margin-bottom:6px; }
        .fa-card header strong { color:#f0c674; font-size:16px; }
        .fa-card > .field-audio-visualizer { margin-top:8px }
        .fa-provenance { color:#878c98; margin:9px 0; font-family:ui-monospace,monospace; }
        .fa-gesture-fit { display:grid; grid-template-columns:repeat(2,minmax(100px,1fr)); gap:4px 10px; margin:9px 0; }
        .fa-gesture-fit span { display:grid; grid-template-columns:72px 1fr; gap:5px; align-items:center; color:#8f949f; font-size:9px; }
        .fa-gesture-fit b { overflow:hidden; text-overflow:ellipsis; font-weight:500; white-space:nowrap; }
        .fa-gesture-fit i { height:3px; background:#32353c; overflow:hidden; } .fa-gesture-fit em { display:block; height:100%; background:#f0c674; }
        .fa-judgement { display:grid; grid-template-columns:1fr auto; gap:6px 10px; align-items:center; }
        .fa-note { grid-column:1 / -1; background:#18191d; border:1px solid #3b3e46; color:#ddd; padding:5px 7px; }
        .fa-save { justify-self:start; background:#323641; color:#ddd; border:1px solid #4a4f5d; border-radius:4px; padding:4px 12px; }
        .fa-status { color:#8fbc8f; }
      </style>
      <h3>Candidate set $setId · ${candidateSet.candidates.size} alternatives</h3>
      <div class="fa-grid">$cards</div>
      <script>
      (() => {
        const root = document.currentScript.parentElement;
        root.querySelectorAll('.fa-save').forEach(button => button.addEventListener('click', async () => {
          const card=button.closest('.fa-card'), status=card.querySelector('.fa-status');
          const payload={rating:Number(card.querySelector('.fa-rating').value),
                         selected:card.querySelector('.fa-selected').checked,
                         annotation:card.querySelector('.fa-note').value || null};
          status.textContent='saving…';
          try {
            const response=await fetch(root.dataset.base+'/v1/candidate-sets/'+root.dataset.set+
              '/candidates/'+card.dataset.index,
              {method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)});
            if (!response.ok) throw new Error(await response.text());
            status.textContent='saved';
          } catch(error) { status.textContent='error: '+error.message; }
        }));
      })();
      </script>
    </section>
    """.trimIndent()
}
