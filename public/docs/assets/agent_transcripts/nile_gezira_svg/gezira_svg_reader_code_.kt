import afield.graphics.nile.*

// A group with a transform and inherited paint, an arc, and an even-odd path with a hole.
val svg =
    """
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 120 60">
      <g fill="#e8a33c" stroke="#3a2a1a" stroke-width="2" transform="translate(10 10)">
        <path d="M0 40 A20 20 0 0 1 40 40 Z"/>
        <path fill-rule="evenodd" fill="#4a7fd0" d="M50 0h40v40h-40z M60 10h20v20h-20z"/>
        <circle cx="20" cy="30" r="6" style="fill:white; stroke:none" opacity="0.8"/>
      </g>
    </svg>""".trimIndent()
val doc = GeziraSvg.parse(svg)
println(doc)
for (l in doc.lines) {
    val what = if (l.filled) "fill ${l.fillRule}" else "stroke ${l.thickness}"
    println("${l.svgId ?: "-"}: ${Gezira.subpaths(l).size} subpath(s), $what, colour ${l.color}")
}
val r = GeziraSvg.render(doc, 360, 180)
display("gezira", GeziraSvg.png(r.canvas.rgba, 360, 180))
