import afield.graphics.render.color
import afield.graphics.text.sentences.*

val d = SentenceDiagram().use(Sentences.example(3))
d.wordFont = font("Serif", 32.0)
d.heights = Heights.Wandering(seed = 17, amplitude = 20.0)
d.wordGap = 48.0
d.labelFontSize = 11.0
d.arcSpanRatio = .43
val marks = decorations {
    arc { dot(it.pointAt(.5), 3.0).also { f -> f.color = vec(.7, .3, .1, 1) } }
    underline { ring(it.left, 3.0).also { f -> f.color = vec(.7, .3, .1, 1) } }
}
val f = d.flines(decorate = marks)
val undertow = f.copy(
    treeEdges = emptyList(),
    wordEdges = emptyList(),
    texts = f.texts.filter { it.kind != TextKind.TAG },
)
val svg = afield.ui.visualization.drawToSVG(undertow.everything(style = DiagramStyle.onWhite), -1.0, -1.0)

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
