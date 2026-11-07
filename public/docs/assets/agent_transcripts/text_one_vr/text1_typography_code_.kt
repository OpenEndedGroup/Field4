import afield.geometry.path.FLine
import afield.graphics.scene.reading.*
import afield.graphics.text.*

// 5 · A room for words. One Slug run can contain multiple real faces and inline styles.
val serif = Typefaces.named("Noto Serif", 100, strict = true)
val sans = Typefaces.named("Noto Sans", 100, strict = true)
val graph = readingDiagram {
    val peg = point("peg", 0, .25, pinned = true)
    val mobile = point("mobile", 0, .12)
    link(peg, mobile, .35f)
    val sentence = text("sentence", "The quiet water carries the light", mobile) {
        font = serif
        scale = .7f
        word("quiet").style { italic = true }
        word("water").style { bold = true }
        word("light").style {
            font = sans
            weight = 300.0
        }
    }
    // Each selection stays in the full shaped run, including kerning, inline fonts and tracking.
    rectangle("whole-ink", sentence.inkBounds, padding = .012f) {
        width = .0008f
        color = vec(1f, 1f, 1f, .20f)
    }
    for ((i, word) in sentence.words.withIndex()) {
        rectangle("word-box-$i", word.bodyBounds, padding = .003f) {
            width = .0008f
            color = vec(1f, 1f, 1f, .35f)
        }
    }
    underline("water", sentence.word("water"), gap = .018f) {
        width = .0013f
        color = vec(1f, 1f, 1f, .45f)
    }
    dot("caret", sentence.caret(4).alongText(sentence, y = -.025f)) {
        shape = DiagramPointShape.DIAMOND
        radius = .0035f
        color = vec(1f, 1f, 1f, .55f)
    }
    // Author arbitrary curves in font units. The frame supplies the glyphs' exact live transform.
    textPath(
        "cap-ruler",
        sentence,
        shape = {
            FLine().moveTo(advance.left, capHeight + 12f).lineTo(advance.right, capHeight + 12f)
        },
    ) {
        dashed(.015f, .35f)
        width = .0007f
        color = vec(1f, 1f, 1f, .30f)
    }
    // Equal strings with different faces must own distinct prepared outlines and GPU data.
    for (i in 0..2) {
        val p = point("face-$i", -.62 + i * .62, -.18, pinned = true)
        val word = text("face-word-$i", "water", p) {
            font = if (i == 2) sans else serif
            italic = i == 1
            bold = i == 2
            scale = .62f
        }
        rectangle("face-box-$i", word.inkBounds, padding = .008f) {
            width = .0007f
            color = vec(1f, 1f, 1f, .25f)
        }
    }
    // Numeric weight requests are prepared as real outlines. AWT chooses or synthesizes the face;
    // they are not a fragment-shader dilation and do not claim arbitrary variable-font axes.
    for (i in 0..4) {
        val p = point("weight-$i", -.8 + i * .4, -.46, pinned = true)
        text("weight-word-$i", "wave", p) {
            font = serif
            weight = 300.0 + 100 * i
            scale = .48f
        }
        text("weight-label-$i", (300 + 100 * i).toString(), p.anchor.offset(y = -.075f)) {
            font = sans
            scale = .16f
            color = vec(1f, 1f, 1f, .45f)
        }
    }
}
val progress = progressReporter("Prepare typography playground", showDelayMillis = 0)
val player = try {
    ReadingDiagramPlayer.prepare(graph, serif, "field-slug".file, diagramScore(graph, 20.0) {}) { n, total, text ->
        progress.update(n.toDouble() / total, "$n / $total: $text")
    }
} finally {
        progress.close()
    }
prop<ReadingExample>(exampleFive) = ReadingExample(
    "5 · A room for words",
    "Inline italic, bold and alternate faces; repeated words in different fonts; numeric weights; live ink and body boxes, baseline attachments and a cap-height ruler.",
    player,
)
