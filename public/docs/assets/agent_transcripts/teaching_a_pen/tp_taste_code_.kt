import afield.ai.learning.pen.*

/*
Twelve scribbles from the handwriting pen. Click the ones you like: they turn green and are
added to a corpus in this document's files/taste folder. "next twelve" draws twelve more. With a few
hundred kept, fine-tune on them (the command is printed below) and load the result with
PenModel.load: a pen that has learned your taste from its own work.
*/
val folder = "taste".file
val kept = mutableListOf<FLine>()
var round = 0
var redraw = true
fun scribbles() = (0 until 12).map { k -> PenLine(seed = round * 12L + k).also { it.grow(120) }.drawn }
var current = scribbles()
controlGroup("taste") {
    // buttons report press and release; act on the press
    button("next twelve") { down ->
        if (down) {
            round++
            current = scribbles()
            redraw = true
        }
    }
    button("save corpus") { down ->
        if (down) {
            PenCorpus.write(folder.path, kept)
            print(
                "${kept.size} kept -> ${folder.path}\n" +
                    "uv run --project . --group pen python -m fieldpen.prepare ${folder.path}/train taste_train.npz --scale 8 (and valid), then\n" +
                    "uv run --project . --group pen python -m fieldpen.train --init src/jvmMain/resources/afield/pen/distill-b64.json --crop 256 --input-noise 0.02 ...",
            )
        }
    }
}
frames {
    while (frame()) {
        if (!redraw) continue
        redraw = false
        val groups = current.map { s ->
            val chosen = kept.any { it === s }
            // a faint frame around each scribble, so you can see which marks are one choice
            val b = s.fast2DBounds()
            val frame = fline {
                moveTo(b.x - 6, b.y - 6)
                lineTo(b.x + b.w + 6, b.y - 6)
                lineTo(b.x + b.w + 6, b.y + b.h + 6)
                lineTo(b.x - 6, b.y + b.h + 6)
                lineTo(b.x - 6, b.y - 6)
                color = vec(1, 1, 1, 0.15)
            }
            [
                frame,
                s.apply {
                    color = if (chosen) vec(0.5, 1.0, 0.6, 1.0) else vec(0.85, 0.9, 1.0, 1.0)
                    thickness = 1.5
                    hoverColor = vec(1.0, 0.85, 0.3, 1.0)
                    onClick = {
                        if (kept.none { it === s }) kept += s else kept.removeAll { it === s }
                        redraw = true
                    }
                },
            ]
        }
        val rows = groups.chunked(4).map { sideBySide(it, gap = 30.0) }
        lines(stacked(rows, gap = 30.0), channel = "scribbles", w = 520.0, h = 300.0, fitToSize = true)
        markdown("kept", "**${kept.size}** kept")
    }
}
