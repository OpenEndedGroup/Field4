import afield.graphics.text.sentences.*

val d = SentenceDiagram().use(Sentences.example(2))
d.wordFont = font("Serif", 32.0)
d.heights = Heights.ByTreeDepth(jitter = 0.0)
d.layerGap = 55.0
d.wordGap = 36.0
d.tagFontSize = 13.0
val marks = decorations {
    node { if (!it.node.leaf) ring(it.position, 3.5).also { f -> f.color = vec(.7, .3, .1, 1) } else null }
}
val f = d.flines(decorate = marks)
val canopy = f.copy(arcs = emptyList(), texts = f.texts.filter { it.kind != TextKind.RELATION })
val svg = afield.ui.visualization.drawToSVG(canopy.everything(style = DiagramStyle.onWhite), -1.0, -1.0)

val figureId = "sentence-figure-${thisBox.id}"
display(
    "diagram",
    """
    <div id="$figureId" style="width:760px;background:#f4f0e6;padding:24px;box-sizing:border-box">
    <style>#$figureId svg { width:100%; height:auto; display:block; }</style>
    $svg
    </div>
    <script>
    (() => {
        const svg = document.getElementById("$figureId")?.querySelector("svg");
        if (svg) svg.setAttribute("viewBox", "0 0 " + svg.getAttribute("width") + " " + svg.getAttribute("height"));
    })();
    </script>
""",
)
displayOpen("diagram")
