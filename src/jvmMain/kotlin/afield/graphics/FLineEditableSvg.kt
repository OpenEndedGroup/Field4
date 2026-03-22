package afield.graphics

import afield.stdlib.color
import afield.stdlib.filled
import afield.stdlib.pointSize
import afield.stdlib.pointed
import afield.stdlib.stroked
import afield.stdlib.thickness
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class EditableSvgScene(
    val sessionId: String,
    val width: Double,
    val height: Double,
    val lines: List<EditableSvgLine>
)

@Serializable
private data class EditableSvgLine(
    val lineIndex: Int,
    val color: String,
    val thickness: Double,
    val stroked: Boolean,
    val filled: Boolean,
    val pointed: Boolean,
    val pointSize: Double,
    val nodes: List<EditableSvgNode>
)

@Serializable
private data class EditableSvgNode(
    val nodeIndex: Int,
    val kind: String,
    val to: DoubleArray? = null,
    val c1: DoubleArray? = null,
    val c2: DoubleArray? = null
)

private val editableSvgJson = Json {
    encodeDefaults = true
}

internal fun buildEditableSVGWidget(
    pairs: List<Pair<FLine, FLine>>,
    fitTransform: FitTransform?,
    w: Double,
    h: Double
): String {
    val scaledLines = pairs.map { it.second }
    if (scaledLines.isEmpty()) {
        return """
            <div class="field-fline-editor-shell">
                <svg xmlns="http://www.w3.org/2000/svg" width="1" height="1"></svg>
            </div>
        """.trimIndent()
    }

    val (we, he) = resolveEditorSize(scaledLines, w, h)
    val session = registerFLineEditSession(pairs.map { it.first }, fitTransform)
    val scene = EditableSvgScene(
        sessionId = session.sessionId,
        width = we,
        height = he,
        lines = scaledLines.mapIndexed { lineIndex, line ->
            EditableSvgLine(
                lineIndex = lineIndex,
                color = vec4ToSvgColor(line.color),
                thickness = line.thickness ?: 1.0,
                stroked = line.stroked,
                filled = line.filled,
                pointed = line.pointed,
                pointSize = line.pointSize,
                nodes = line.nodes.mapIndexed { nodeIndex, node ->
                    when (node) {
                        is FLine.MoveTo -> EditableSvgNode(
                            nodeIndex = nodeIndex,
                            kind = "move",
                            to = node.to.toSceneArray()
                        )

                        is FLine.LineTo -> EditableSvgNode(
                            nodeIndex = nodeIndex,
                            kind = "line",
                            to = node.to.toSceneArray()
                        )

                        is FLine.CubicTo -> EditableSvgNode(
                            nodeIndex = nodeIndex,
                            kind = "cubic",
                            to = node.to.toSceneArray(),
                            c1 = node.c1.toSceneArray(),
                            c2 = node.c2.toSceneArray()
                        )

                        is FLine.Close -> EditableSvgNode(
                            nodeIndex = nodeIndex,
                            kind = "close"
                        )
                    }
                }
            )
        }
    )
    val encodedScene = editableSvgJson.encodeToString(scene).replace("</", "<\\/")

    return """
        <div class="field-fline-editor-shell" data-field-fline-editor="true">
            <style>
                .field-fline-editor-shell {
                    --fline-editor-accent: #62d7ff;
                    --fline-editor-muted: rgba(194, 234, 255, 0.62);
                    --fline-editor-panel-border: rgba(98, 215, 255, 0.16);
                    --fline-editor-selection: rgba(255, 211, 92, 0.95);
                    --fline-editor-selection-soft: rgba(255, 211, 92, 0.2);
                    position: relative;
                    display: block;
                    width: 100%;
                }

                .field-fline-editor-shell:focus,
                .field-fline-editor-shell:focus-visible {
                    outline: none;
                }

                .field-fline-editor-overlay {
                    position: absolute;
                    top: 6px;
                    left: 6px;
                    right: 6px;
                    z-index: 4;
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                    gap: 10px;
                    font-family: "Iosevka Web", monospace;
                    font-size: 11px;
                    color: #c6ecff;
                    pointer-events: auto;
                }

                .field-fline-editor-overlay.is-hidden {
                    display: none;
                }

                .field-fline-editor-maskgroup {
                    display: inline-flex;
                    align-items: center;
                    gap: 6px;
                    pointer-events: auto;
                    padding: 2px;
                    border-radius: 6px;
                    border: 1px solid var(--fline-editor-panel-border);
                    background: transparent;
                }

                .field-fline-editor-maskbutton {
                    appearance: none;
                    border: 1px solid rgba(98, 215, 255, 0.16);
                    border-radius: 5px;
                    background: transparent;
                    color: #a7dfff;
                    font: inherit;
                    width: 24px;
                    height: 24px;
                    display: inline-flex;
                    align-items: center;
                    justify-content: center;
                    cursor: pointer;
                }

                .field-fline-editor-maskbutton svg {
                    width: 14px;
                    height: 14px;
                    stroke: currentColor;
                    fill: none;
                    stroke-width: 1.6;
                }

                .field-fline-editor-maskbutton.is-active {
                    color: #071015;
                    background: var(--fline-editor-accent);
                    border-color: rgba(255, 255, 255, 0.08);
                }

                .field-fline-editor-status {
                    color: var(--fline-editor-muted);
                    min-width: 110px;
                    text-align: right;
                    pointer-events: none;
                    text-shadow: 0 1px 2px rgba(0, 0, 0, 0.45);
                }

                .field-fline-editor-status.is-empty {
                    visibility: hidden;
                }

                .field-fline-editor-canvas {
                    display: block;
                    overflow: visible;
                    touch-action: none;
                    user-select: none;
                    background: none;
                    border: none;
                    width: auto;
                    height: auto;
                    max-width: none;
                }
            </style>

            <div class="field-fline-editor-overlay">
                <div class="field-fline-editor-maskgroup">
                    <button type="button" class="field-fline-editor-maskbutton is-active" data-mask-button="point" aria-label="Toggle vertex selection" title="Vertex selection">
                        <svg viewBox="0 0 16 16" aria-hidden="true"><circle cx="8" cy="8" r="4.4"></circle></svg>
                    </button>
                    <button type="button" class="field-fline-editor-maskbutton is-active" data-mask-button="edge" aria-label="Toggle edge selection" title="Edge selection">
                        <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M2.5 12.5 L13.5 3.5"></path></svg>
                    </button>
                    <button type="button" class="field-fline-editor-maskbutton is-active" data-mask-button="face" aria-label="Toggle fill selection" title="Fill selection">
                        <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M8 2.4 L13.2 12.6 L2.8 12.6 Z"></path></svg>
                    </button>
                </div>
                <div class="field-fline-editor-status" data-role="status">No selection</div>
            </div>

            <svg class="field-fline-editor-canvas"
                 xmlns="http://www.w3.org/2000/svg"
                 width="$we"
                 height="$he"
                 viewBox="0 0 $we $he"></svg>
            <script type="application/json" class="field-fline-editor-scene">$encodedScene</script>
            <script>
                window.initializeFLineEditors && window.initializeFLineEditors(document.currentScript.parentElement);
            </script>
        </div>
    """.trimIndent()
}

private fun resolveEditorSize(lines: List<FLine>, w: Double, h: Double): Pair<Double, Double> {
    if (w > 0.0 && h > 0.0) return w to h
    val bounds = lines.map { it.fast2DBounds() }.reduce { a, b -> a.union(b) }
    return (bounds.w + bounds.x).coerceAtLeast(1.0) to (bounds.h + bounds.y).coerceAtLeast(1.0)
}

private fun afield.stdlib.Vec3.toSceneArray(): DoubleArray = doubleArrayOf(x.toDouble(), y.toDouble(), z.toDouble())
