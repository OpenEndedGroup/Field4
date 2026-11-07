import afield.graphics.text.sentences.*

val d = SentenceDiagram().use(Sentences.example(4))
d.wordFont = font("Serif", 32.0)
// Here grammar places words on shelves/slants; it is a different layout, not a style on the arc tree.
val config = KelloggConfig(
    slantAngle = 30.0,
    nestAngleStep = 10.0,
    maxSlantAngle = 48.0,
    fontSize = 32.0,
    metrics = Java2DMetrics(d.wordFont!!),
    modifierSize = 23.0,
    modifierMetrics = Java2DMetrics(d.wordFont!!.deriveFont(23f)),
)
val svg = d.kelloggSvg(kelloggConfig = config)

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
