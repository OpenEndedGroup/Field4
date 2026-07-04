package afield.graphics

import afield.FLineDrawStyle
import afield.stdlib.color
import afield.stdlib.filled
import afield.stdlib.pointSize
import afield.stdlib.pointed
import afield.stdlib.stroked
import afield.stdlib.thickness
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class EditableSvgWidget(
    val html: String,
    val viewState: EditableFLineViewState
)

@Serializable
private data class EditableSvgScene(
    val sessionId: String,
    val width: Double,
    val height: Double,
    val viewX: Double,
    val viewY: Double,
    val viewWidth: Double,
    val viewHeight: Double,
    val drawingEnabled: Boolean,
    val liveDrawingEnabled: Boolean,
    val drawStyle: FLineDrawStyle,
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
    h: Double,
    drawingEnabled: Boolean = false,
    liveDrawingEnabled: Boolean = false
): EditableSvgWidget {
    val scaledLines = pairs.map { it.second }
    val session = registerFLineEditSession(pairs.map { it.first }, fitTransform)
    val frame = resolveEditorFrame(scaledLines, w, h)
    val scene = EditableSvgScene(
        sessionId = session.sessionId,
        width = frame.width,
        height = frame.height,
        viewX = frame.viewX,
        viewY = frame.viewY,
        viewWidth = frame.viewWidth,
        viewHeight = frame.viewHeight,
        drawingEnabled = drawingEnabled,
        liveDrawingEnabled = drawingEnabled && liveDrawingEnabled,
        drawStyle = session.drawStyle(),
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

    return EditableSvgWidget(
        html = """
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

                    .field-fline-editor-controls {
                        display: inline-flex;
                        align-items: center;
                        gap: 8px;
                        flex-wrap: wrap;
                    }

                    .field-fline-editor-maskgroup,
                    .field-fline-editor-drawgroup,
                    .field-fline-editor-modegroup {
                        display: inline-flex;
                        align-items: center;
                        gap: 6px;
                        pointer-events: auto;
                        padding: 2px;
                        border-radius: 6px;
                        border: 1px solid var(--fline-editor-panel-border);
                        background: transparent;
                    }

                    .field-fline-editor-maskbutton,
                    .field-fline-editor-drawbutton,
                    .field-fline-editor-modebutton {
                        appearance: none;
                        border: 1px solid rgba(98, 215, 255, 0.16);
                        border-radius: 5px;
                        background: transparent;
                        color: #a7dfff;
                        font: inherit;
                        display: inline-flex;
                        align-items: center;
                        justify-content: center;
                        cursor: pointer;
                        min-height: 24px;
                    }

                    .field-fline-editor-maskbutton {
                        width: 24px;
                        height: 24px;
                    }

                    .field-fline-editor-modebutton {
                        padding: 0 10px;
                    }

                    .field-fline-editor-drawbutton {
                        padding: 0 12px;
                    }

                    .field-fline-editor-drawbutton:disabled {
                        opacity: 0.45;
                        cursor: default;
                    }

                    .field-fline-editor-maskbutton svg {
                        width: 14px;
                        height: 14px;
                        stroke: currentColor;
                        fill: none;
                        stroke-width: 1.6;
                    }

                    .field-fline-editor-maskbutton.is-active,
                    .field-fline-editor-modebutton.is-active {
                        color: #071015;
                        background: var(--fline-editor-accent);
                        border-color: rgba(255, 255, 255, 0.08);
                    }

                    .field-fline-editor-modegroup.is-hidden,
                    .field-fline-editor-maskgroup.is-hidden,
                    .field-fline-editor-drawgroup.is-hidden {
                        display: none;
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
                    <div class="field-fline-editor-controls">
                        <div class="field-fline-editor-modegroup${if (drawingEnabled) "" else " is-hidden"}">
                            <button type="button" class="field-fline-editor-modebutton is-active" data-mode-button="edit" aria-label="Edit lines" title="Edit lines">Edit</button>
                            <button type="button" class="field-fline-editor-modebutton" data-mode-button="draw" aria-label="Draw a new line" title="Draw a new line">Draw</button>
                        </div>
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
                        <div class="field-fline-editor-drawgroup is-hidden">
                            <button type="button" class="field-fline-editor-drawbutton" data-draw-action="clear" aria-label="Clear drawn lines" title="Clear drawn lines">Clear</button>
                        </div>
                    </div>
                    <div class="field-fline-editor-status" data-role="status">No selection</div>
                </div>

                <svg class="field-fline-editor-canvas"
                     xmlns="http://www.w3.org/2000/svg"
                     width="${frame.width}"
                     height="${frame.height}"
                     viewBox="${frame.viewX} ${frame.viewY} ${frame.viewWidth} ${frame.viewHeight}"
                     preserveAspectRatio="xMidYMid meet"></svg>
                <script type="application/json" class="field-fline-editor-scene">$encodedScene</script>
                <script>
                    window.initializeFLineEditors && window.initializeFLineEditors(document.currentScript.parentElement);
                </script>
            </div>
        """.trimIndent(),
        viewState = EditableFLineViewState(session.sessionId)
    )
}

private data class EditableSvgFrame(
    val width: Double,
    val height: Double,
    val viewX: Double,
    val viewY: Double,
    val viewWidth: Double,
    val viewHeight: Double
)

private fun resolveEditorFrame(lines: List<FLine>, w: Double, h: Double): EditableSvgFrame {
    if (lines.isEmpty()) {
        val width = if (w > 0.0) w else 320.0
        val height = if (h > 0.0) h else 240.0
        return EditableSvgFrame(width, height, 0.0, 0.0, width, height)
    }
    val bounds = lines.map { it.fast2DBounds() }.reduce { a, b -> a.union(b) }
    val viewPad = maxOf(2.0, maxOf(bounds.w, bounds.h) * 0.04)
    val viewWidth = (bounds.w + viewPad * 2.0).coerceAtLeast(1.0)
    val viewHeight = (bounds.h + viewPad * 2.0).coerceAtLeast(1.0)
    val aspect = (viewWidth / viewHeight).takeIf { it.isFinite() && it > 0.0 } ?: (4.0 / 3.0)
    val width = when {
        w > 0.0 -> w
        h > 0.0 -> h * aspect
        else -> viewWidth.coerceIn(320.0, 900.0)
    }
    val height = when {
        h > 0.0 -> h
        w > 0.0 -> w / aspect
        else -> (width / aspect).coerceIn(180.0, 700.0)
    }
    return EditableSvgFrame(
        width = width.coerceAtLeast(1.0),
        height = height.coerceAtLeast(1.0),
        viewX = bounds.x - viewPad,
        viewY = bounds.y - viewPad,
        viewWidth = viewWidth,
        viewHeight = viewHeight
    )
}

private fun afield.stdlib.Vec3.toSceneArray(): DoubleArray = doubleArrayOf(x.toDouble(), y.toDouble(), z.toDouble())
