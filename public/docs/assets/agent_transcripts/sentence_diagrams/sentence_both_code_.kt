import afield.graphics.text.sentences.*

val d = SentenceDiagram().use(Sentences.example(2))
d.wordFont = font("Serif", 30.0)
d.heights = Heights.Flat()
d.wordGap = 50.0
d.layerGap = 50.0
d.tagFontSize = 12.0
d.labelFontSize = 11.0
// Constituents above, collapsed dependencies below; both share the same token row.
val svg = d.svg()

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
