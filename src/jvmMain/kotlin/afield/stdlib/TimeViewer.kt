@file:afield.CompletionCategory("Visualization")

package afield.stdlib

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * The HTML shell of Field's one time-axis viewer, shared by [SignalViewer], `Fn.plot`,
 * [AudioVisualizer] and the Audio AI analysis views. It writes the chrome — title, status chip,
 * zoom/fit buttons, a rounded plot frame, a readout line and an overview strip — plus a JSON
 * config, and inlines `signal-viewer.js`, which draws with Field's bundled Plotly.
 *
 * Callers choose a `source` in [render]'s config: `range` (a `/signal-viewer/{id}` query per
 * visible window), `embedded` (a static window in the page), `audio` (an `<audio>` element and
 * its envelope) or `analysis` (a Field Audio preview). Everything else is shared: the view window,
 * the time bus (`field-time-window`, `-cursor`, `-seek`, `-transport`), `viewKey` persistence and
 * the DOM-overlay playhead. See `dev_docs/SIGNAL_VIEWER.md`.
 */
internal object TimeViewerShell {
    private val mapper = jacksonObjectMapper()
    private val runtime: String by lazy {
        checkNotNull(TimeViewerShell::class.java.getResourceAsStream("/signal-viewer.js")) { "signal-viewer.js is missing" }
            .bufferedReader().use { it.readText() }
    }

    /** JSON safe to place inside a `<script>` element. */
    fun scriptJson(value: Any?): String = mapper.writeValueAsString(value)
        .replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")

    /**
     * A `SignalWindow`-shaped payload with gaps: non-finite values become JSON `null`, which Plotly
     * draws as a break in the line rather than a spike.
     */
    fun embeddedWindow(start: Double, end: Double, lanes: List<Pair<String, DoubleArray>>, minimum: Double, maximum: Double): Map<String, Any?> {
        val n = lanes.firstOrNull()?.second?.size ?: 0
        return mapOf(
            "start" to start, "end" to end, "mode" to "waveform",
            "sampleSpacing" to if (n > 1) (end - start) / (n - 1) else 0.0,
            "lanes" to lanes.map { (name, values) ->
                val list = values.map { v -> v.takeIf { it.isFinite() } }
                mapOf("name" to name, "minimum" to minimum, "maximum" to maximum, "current" to mapOf("low" to list, "high" to list))
            },
        )
    }

    fun render(
        config: Map<String, Any?>,
        widthPx: Int?,
        // Extra markup after the footer: the <audio> element for the audio source.
        tail: String = "",
        // Extra JSON payload for the analysis source.
        preview: String? = null,
        accent: String = "#63c7dc",
        background: String = "#000000",
        fontFamily: String = "\"Iosevka Web\", \"IBM Plex Mono\", ui-monospace, monospace",
        showNavigation: Boolean = true,
        // Compatibility class for callers that style the old component (e.g. field-audio-visualizer).
        extraClass: String = "",
    ): String {
        val interactive = config["interactive"] != false
        val source = config["source"] as? String ?: "range"
        val size = if (widthPx != null) "width:${widthPx}px;max-width:100%" else "width:100%;min-width:320px"
        val intrinsic = widthPx ?: 760
        val tools = if (!interactive) "" else """
            <div class="fsv-tools" role="toolbar" aria-label="View">
            <button type="button" data-action="out" title="Zoom out (−)" aria-label="Zoom out">−</button><button type="button" data-action="in" title="Zoom in (+)" aria-label="Zoom in">+</button><button type="button" data-action="fit" title="Fit values to the visible window (f)" aria-label="Fit values">&#8597;</button><button type="button" data-action="all" title="Show everything (Home, double-click)" aria-label="Show all">&#8634;</button>
            </div>""".trimIndent()
        val navigation = if (!interactive || !showNavigation) "" else
            """<div class="fsv-navigation" tabindex="0" role="scrollbar" aria-label="Whole domain; click or drag to move the view"><svg viewBox="0 0 1000 24" preserveAspectRatio="none" aria-hidden="true"><path class="fsv-overview" d=""/></svg><div class="fsv-window"></div></div>"""
        val safeAccent = accent.cssValue()
        val safeBackground = background.cssValue()
        return """
<div class="field-signal-viewer fsv-$source${if (extraClass.isBlank()) "" else " " + extraClass.filter { it.isLetterOrDigit() || it == '-' }}" data-afield-intrinsic-width="$intrinsic" style="$size;box-sizing:border-box;--fsv-accent:$safeAccent;--fsv-bg:$safeBackground" aria-busy="true">
<style>
.field-signal-viewer{background:var(--fsv-bg);color:rgba(255,255,255,.82);font:12px/1.4 $fontFamily;padding:10px 12px 10px;border-radius:4px}
.field-signal-viewer .fsv-top{display:flex;align-items:center;gap:8px;min-height:28px;margin-bottom:6px}
.field-signal-viewer .fsv-title{flex:1;min-width:0;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;color:rgba(255,255,255,.92);font-size:13px;font-weight:600}
.field-signal-viewer .fsv-legend{color:rgba(255,255,255,.5);font-size:11px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:40%}
.field-signal-viewer .fsv-chip{color:rgba(255,255,255,.6);border:1px solid rgba(255,255,255,.13);border-radius:6px;padding:3px 7px;font-size:11px;line-height:16px;white-space:nowrap;background:rgba(255,255,255,.035)}
.field-signal-viewer .fsv-chip:empty{display:none}
.field-signal-viewer .fsv-error{color:#e99a9a}
.field-signal-viewer .fsv-tools{display:flex;gap:4px}
.field-signal-viewer button{min-width:26px;height:24px;border-radius:6px;border:1px solid rgba(255,255,255,.16);background:rgba(255,255,255,.04);color:rgba(255,255,255,.78);font:13px/20px $fontFamily;cursor:pointer;padding:0 6px}
.field-signal-viewer button:hover{border-color:var(--fsv-accent);color:#fff}
.field-signal-viewer :focus-visible{outline:1px solid var(--fsv-accent);outline-offset:1px}
.field-signal-viewer .fsv-frame{border:1px solid rgba(255,255,255,.12);border-radius:8px;background:#050505;overflow:hidden}
.field-signal-viewer .fsv-stage{position:relative;touch-action:none;user-select:none;min-height:60px}
.field-signal-viewer .fsv-plot{cursor:crosshair;outline:none}
.field-signal-viewer .fsv-plot .nsewdrag{cursor:crosshair!important}
.field-signal-viewer .fsv-overlay{position:absolute;inset:0;pointer-events:none;overflow:hidden}
.field-signal-viewer .fsv-line{position:absolute;left:0;width:0;display:none;border-left:1px solid rgba(255,255,255,.5);will-change:transform}
.field-signal-viewer .fsv-line[data-kind=playhead]{border-left:1.5px solid #ffdf73}
.field-signal-viewer .fsv-cursor[data-kind=hover]{border-left-color:rgba(99,199,220,.85)}
.field-signal-viewer .fsv-busy{position:absolute;top:8px;right:10px;display:flex;align-items:center;gap:6px;padding:3px 8px;color:rgba(255,255,255,.62);background:rgba(10,10,10,.9);border:1px solid rgba(255,255,255,.14);border-radius:12px;font-size:10px;opacity:0;transition:opacity 90ms ease;pointer-events:none}
.field-signal-viewer.fsv-loading .fsv-busy{opacity:1}
.field-signal-viewer .fsv-spinner{width:11px;height:11px;box-sizing:border-box;border:1px solid rgba(255,255,255,.25);border-top-color:var(--fsv-accent);border-radius:50%;animation:fsv-spin 760ms linear infinite}
@keyframes fsv-spin{to{transform:rotate(360deg)}}
.field-signal-viewer .fsv-foot{display:flex;gap:12px;align-items:baseline;margin-top:6px;min-height:17px;font-size:11px}
.field-signal-viewer .fsv-readout{flex:1;min-width:0;color:rgba(255,255,255,.72);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.field-signal-viewer .fsv-range{color:rgba(255,255,255,.45);white-space:nowrap}
.field-signal-viewer .fsv-navigation{position:relative;height:20px;margin-top:6px;border:1px solid rgba(255,255,255,.1);border-radius:5px;background:#050505;cursor:pointer;overflow:hidden}
.field-signal-viewer .fsv-navigation svg{position:absolute;inset:2px 0;width:100%;height:calc(100% - 4px)}
.field-signal-viewer .fsv-overview{fill:none;stroke:rgba(255,255,255,.34);stroke-width:1;vector-effect:non-scaling-stroke}
.field-signal-viewer .fsv-overview.fsv-filled{fill:rgba(255,255,255,.2);stroke:none}
.field-signal-viewer .fsv-window{position:absolute;top:0;bottom:0;border:1px solid var(--fsv-accent);border-radius:4px;background:rgba(255,255,255,.06);box-sizing:border-box}
.field-signal-viewer .fsv-navigation.fsv-whole{cursor:default;border-color:rgba(255,255,255,.07)}
.field-signal-viewer .fsv-navigation.fsv-whole .fsv-window{border-color:rgba(255,255,255,.1);background:none}
.field-signal-viewer .fsv-navigation.fsv-whole .fsv-overview{stroke:rgba(255,255,255,.16)}
.field-signal-viewer .fsv-navigation.fsv-whole .fsv-overview.fsv-filled{fill:rgba(255,255,255,.09)}
.field-signal-viewer audio{width:100%;height:30px;margin-top:6px;display:block}
</style>
<div class="fsv-top"><div class="fsv-title"></div><span class="fsv-legend"></span><span class="fsv-status fsv-chip" role="status" aria-live="polite"></span>$tools</div>
<div class="fsv-frame"><div class="fsv-stage"><div class="fsv-busy" aria-hidden="true"><span class="fsv-spinner"></span><span>loading</span></div></div></div>
<div class="fsv-foot"><span class="fsv-readout"></span><span class="fsv-range"></span></div>
$navigation
$tail
<script type="application/json" class="fsv-config">${scriptJson(config)}</script>
${preview?.let { "<script type=\"application/json\" class=\"fsv-preview\">${it.replace("<", "\\u003c")}</script>" }.orEmpty()}
<script>$runtime</script>
</div>
""".trim()
    }

    private fun String.cssValue(): String = filter { it.isLetterOrDigit() || it in "#(),.% -" }
}
