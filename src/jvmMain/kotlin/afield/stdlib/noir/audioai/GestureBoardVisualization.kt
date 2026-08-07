package afield.stdlib.noir.audioai

import java.util.Locale

/** Interactive, dependency-free timeline renderer for a [GestureBoard]. */
object GestureBoardVisualization {
    private val colors = mapOf(
        GestureCurveKind.DENSITY to "#f0c674",
        GestureCurveKind.ACTIVITY to "#8fbc8f",
        GestureCurveKind.REGISTER to "#81a2be",
        GestureCurveKind.BRIGHTNESS to "#c586c0",
    )

    fun renderHtml(
        board: GestureBoard,
        compilation: GestureCompilation = compileGestureBoard(board),
        candidates: CandidateSet? = null,
        width: Int = 1080,
    ): String {
        validateGestureBoard(board)
        require(width >= 640) { "gesture board width must be at least 640" }
        val left = 132.0
        val right = 24.0
        val plotWidth = width - left - right
        val promptTop = 68.0
        val promptHeight = if (board.promptRegions.isEmpty()) 24.0 else 36.0
        val laneHeight = 82.0
        val curveTop = promptTop + promptHeight + 30.0
        val curveRows = board.curves.size.coerceAtLeast(1)
        val plotBottom = curveTop + curveRows * laneHeight
        val svgHeight = (plotBottom + 46).toInt()
        fun x(time: Double) = left + (time / board.durationSeconds).coerceIn(0.0, 1.0) * plotWidth
        fun f(number: Double) = String.format(Locale.US, "%.2f", number)
        val id = "gesture-${board.hashCode().toString().replace('-', 'n')}"

        val ticks = (0..8).joinToString("") { index ->
            val time = board.durationSeconds * index / 8.0
            val tx = x(time)
            """<line x1="${f(tx)}" y1="42" x2="${f(tx)}" y2="${f(plotBottom)}" class="gb-grid"/><text x="${f(tx)}" y="31" class="gb-tick">${f(time)}s</text>"""
        }
        val prompts = if (board.promptRegions.isEmpty()) {
            """<rect x="${f(left)}" y="$promptTop" width="${f(plotWidth)}" height="$promptHeight" rx="5" class="gb-empty"/><text x="${f(left + 10)}" y="${f(promptTop + 17)}" class="gb-muted">global prompt only</text>"""
        } else board.promptRegions.joinToString("") { prompt ->
            val px = x(prompt.startSeconds)
            val pw = (prompt.durationSeconds / board.durationSeconds * plotWidth).coerceAtLeast(4.0)
            """<g class="gb-prompt"><rect x="${f(px)}" y="$promptTop" width="${f(pw)}" height="$promptHeight" rx="5"/><title>${prompt.text.escaped()}</title><text x="${f(px + 8)}" y="${f(promptTop + 22)}">${prompt.text.escaped()}</text></g>"""
        }
        val events = board.events.joinToString("") { event ->
            val ex = x(event.timeSeconds)
            """<g class="gb-event"><line x1="${f(ex)}" y1="${f(promptTop - 8)}" x2="${f(ex)}" y2="${f(plotBottom)}"/><circle cx="${f(ex)}" cy="${f(promptTop - 8)}" r="5"/><text x="${f(ex + 7)}" y="${f(promptTop - 10)}">${event.label.escaped()}</text><title>${event.label.escaped()} · strength ${f(event.strength)} · evaluated after generation</title></g>"""
        }
        val lanes = if (board.curves.isEmpty()) {
            """<text x="${f(left)}" y="${f(curveTop + 28)}" class="gb-muted">No evaluative curves — add density, activity, register, or brightness.</text>"""
        } else board.curves.mapIndexed { index, curve ->
            val top = curveTop + index * laneHeight
            val bottom = top + 58
            val color = colors.getValue(curve.kind)
            val path = curve.points.joinToString(" ") { point ->
                "${f(x(point.timeSeconds))},${f(bottom - point.value * 52)}"
            }
            val points = curve.points.joinToString("") { point ->
                """<circle cx="${f(x(point.timeSeconds))}" cy="${f(bottom - point.value * 52)}" r="4"><title>${f(point.timeSeconds)}s · ${f(point.value)}</title></circle>"""
            }
            """
              <g class="gb-lane" style="--lane:$color">
                <text x="12" y="${f(top + 20)}" class="gb-lane-name">${curve.name.escaped()}</text>
                <text x="12" y="${f(top + 39)}" class="gb-lane-kind">${curve.kind.name.lowercase()} · score ×${f(curve.weight)}</text>
                <line x1="${f(left)}" y1="${f(bottom)}" x2="${f(left + plotWidth)}" y2="${f(bottom)}" class="gb-baseline"/>
                <polyline points="$path"/>
                $points
              </g>
            """.trimIndent()
        }.joinToString("")

        val bindings = compilation.bindings.joinToString("") { binding ->
            """<span class="gb-binding ${binding.delivery.name.lowercase()}" title="${binding.explanation.escaped()}">${binding.name.escaped()} <b>${binding.delivery.name.lowercase()}</b></span>"""
        }
        val rankings = candidates?.candidates
            ?.sortedByDescending { it.gestureEvaluation?.overallScore ?: it.score ?: -1.0 }
            ?.joinToString("") { candidate ->
                val evaluation = candidate.gestureEvaluation
                val score = evaluation?.overallScore ?: candidate.score
                val laneBars = evaluation?.laneScores?.entries?.joinToString("") { (name, value) ->
                    """<span class="gb-score-lane" title="${name.escaped()} ${f(value)}"><i style="width:${f(value * 100)}%"></i></span>"""
                }.orEmpty()
                """<div class="gb-candidate"><b>#${candidate.index + 1}</b><span>seed ${candidate.seed}</span><div class="gb-score"><i style="width:${f((score ?: 0.0) * 100)}%"></i></div><strong>${score?.let(::f) ?: "—"}</strong><div class="gb-mini-lanes">$laneBars</div></div>"""
            }.orEmpty()
        val rankingSection = if (candidates == null) "" else """
            <section class="gb-rankings"><h4>candidate fit</h4>$rankings</section>
        """.trimIndent()

        return """
        <section id="$id" class="gesture-board" style="--board-width:${width}px">
          <style>
            #$id { width:min(100%,var(--board-width)); box-sizing:border-box; color:#dddfe5; background:linear-gradient(145deg,#181a1f,#121317); border:1px solid #343842; border-radius:12px; padding:16px 18px 18px; font:12px/1.35 Inter,system-ui,sans-serif; box-shadow:0 12px 30px #0005; }
            #$id * { box-sizing:border-box } #$id header { display:flex; align-items:flex-start; gap:14px; margin-bottom:9px } #$id h3 { margin:0; font-size:17px; font-weight:560; letter-spacing:.01em } #$id .gb-duration { color:#9ba1ad; font:11px ui-monospace,monospace; padding-top:4px }
            #$id .gb-prompt-text { color:#aeb4c0; margin:4px 0 11px; max-width:96%; } #$id .gb-prompt-text b { color:#e6d7a8; font-weight:520 }
            #$id .gb-bindings { display:flex; flex-wrap:wrap; gap:5px; margin-bottom:8px } #$id .gb-binding { border:1px solid #434751; border-radius:20px; padding:3px 8px; color:#b9bec9 } #$id .gb-binding b { font-size:9px; text-transform:uppercase; letter-spacing:.08em; margin-left:5px } #$id .gb-binding.direct b{color:#8fbc8f} #$id .gb-binding.compiled b{color:#81a2be} #$id .gb-binding.evaluative b{color:#f0c674}
            #$id .gb-scroll { overflow-x:auto; border-top:1px solid #2e3139; margin-top:10px } #$id svg { display:block; min-width:${width}px; background:radial-gradient(circle at 25% 0,#242731 0,#191b20 48%,#16171b 100%); user-select:none }
            #$id .gb-grid { stroke:#353943; stroke-width:1; stroke-dasharray:2 5 } #$id .gb-tick { fill:#808692; text-anchor:middle; font:10px ui-monospace,monospace } #$id .gb-empty { fill:#20232a; stroke:#3a3e48 } #$id .gb-muted { fill:#777e8b; font-style:italic }
            #$id .gb-prompt rect { fill:#35465a; stroke:#607c9b; opacity:.9 } #$id .gb-prompt text { fill:#d9e5f2; font-size:11px; pointer-events:none } #$id .gb-event line { stroke:#e3a96b; stroke-width:1.5; stroke-dasharray:4 4 } #$id .gb-event circle { fill:#e3a96b } #$id .gb-event text { fill:#e8c08f; font-size:10px }
            #$id .gb-lane-name { fill:#e0e2e7; font-size:12px } #$id .gb-lane-kind { fill:#7e8490; font-size:9px; text-transform:uppercase } #$id .gb-lane .gb-baseline { stroke:#373b45 } #$id .gb-lane polyline { fill:none; stroke:var(--lane); stroke-width:3; stroke-linejoin:round; filter:drop-shadow(0 0 4px color-mix(in srgb,var(--lane) 45%,transparent)) } #$id .gb-lane circle { fill:#1b1d22; stroke:var(--lane); stroke-width:2 }
            #$id .gb-cursor { stroke:#f2f2f2; stroke-width:1; opacity:0; pointer-events:none } #$id .gb-time { position:absolute; display:none; background:#0d0e11e8; border:1px solid #565b67; border-radius:4px; padding:3px 7px; font:10px ui-monospace,monospace; pointer-events:none }
            #$id .gb-rankings { border-top:1px solid #30333c; padding-top:12px } #$id .gb-rankings h4 { margin:0 0 8px; color:#9da3af; font-size:10px; text-transform:uppercase; letter-spacing:.1em } #$id .gb-candidate { display:grid; grid-template-columns:28px 76px 1fr 42px; gap:8px; align-items:center; margin:5px 0 } #$id .gb-candidate>b { color:#f0c674 } #$id .gb-candidate>span { color:#8e949f; font:10px ui-monospace,monospace } #$id .gb-score { height:7px; background:#292c33; border-radius:4px; overflow:hidden } #$id .gb-score i { display:block; height:100%; background:linear-gradient(90deg,#81a2be,#8fbc8f,#f0c674) } #$id .gb-candidate>strong { text-align:right; font:11px ui-monospace,monospace } #$id .gb-mini-lanes { grid-column:3/5; display:flex; gap:3px } #$id .gb-score-lane { flex:1; height:3px; background:#292c33 } #$id .gb-score-lane i { display:block; height:100%; background:#7f8490 }
          </style>
          <header><h3>gesture board</h3><span class="gb-duration">${f(board.durationSeconds)} seconds · ${board.curves.size} curves · ${board.events.size} events</span></header>
          <div class="gb-prompt-text"><b>effective prompt</b> · ${compilation.effectivePrompt.ifBlank { "(unconditioned)" }.escaped()}</div>
          <div class="gb-bindings">$bindings</div>
          $rankingSection
          <div class="gb-scroll"><svg viewBox="0 0 $width $svgHeight" width="$width" height="$svgHeight" data-duration="${board.durationSeconds}">
            $ticks $prompts $events $lanes
            <line class="gb-cursor" x1="$left" y1="38" x2="$left" y2="${f(plotBottom)}"/>
          </svg></div>
          <div class="gb-time"></div>
          <script>(()=>{const root=document.getElementById('$id'),svg=root.querySelector('svg'),line=svg.querySelector('.gb-cursor'),tip=root.querySelector('.gb-time');svg.addEventListener('pointermove',e=>{const r=svg.getBoundingClientRect(),vx=(e.clientX-r.left)*${width}/r.width,x=Math.max($left,Math.min(${f(left + plotWidth)},vx)),t=(x-$left)/${f(plotWidth)}*${board.durationSeconds};line.setAttribute('x1',x);line.setAttribute('x2',x);line.style.opacity=1;tip.style.display='block';tip.textContent=t.toFixed(2)+' s';tip.style.left=(e.clientX-root.getBoundingClientRect().left+10)+'px';tip.style.top=(e.clientY-root.getBoundingClientRect().top-8)+'px'});svg.addEventListener('pointerleave',()=>{line.style.opacity=0;tip.style.display='none'})})()</script>
        </section>
        """.trimIndent()
    }
}

fun gestureBoardHtml(
    board: GestureBoard,
    compilation: GestureCompilation = compileGestureBoard(board),
    candidates: CandidateSet? = null,
    width: Int = 1080,
): String = GestureBoardVisualization.renderHtml(board, compilation, candidates, width)

private fun String.escaped(): String = buildString(length) {
    for (character in this@escaped) append(when (character) {
        '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '"' -> "&quot;"; '\'' -> "&#39;"; else -> character
    })
}
