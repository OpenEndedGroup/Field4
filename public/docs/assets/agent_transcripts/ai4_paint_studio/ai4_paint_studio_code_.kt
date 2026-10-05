// A studio for AI4: open this box's AI panel and ask for a painting ("a quiet harbour at dusk").
// The agent paints with the paint_* tools below, looks at the canvas as it goes, and hangs what it
// finishes in the gallery. The canvas belongs to the conversation: rewind the chat to a request and
// the canvas goes back to how it was before it; /branches and /branch switch between versions.
// /canvas, /palette and /gallery show the easel, the tube paints and the hung paintings.
// Needs the paint library: ./gradlew buildPaintNative.
import afield.paint.*
import java.io.File
import java.util.Base64

// The canvas is kept on the box, not in a script variable, so re-running this box keeps the painting.
fun fresh(ground: PaintGround = PaintGround.Oil, aspect: Double = 1.25, seed: Long = 7) =
    PaintCanvas(900, aspect = aspect, ground = ground, seed = seed)
fun canvas(): PaintCanvas = thisBox.properties.getOrPut("studio.canvas") { fresh() } as PaintCanvas
fun replace(c: PaintCanvas) { thisBox.properties["studio.canvas"] = c }

fun pngUrl(bytes: ByteArray) = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)
fun show() = display("main", canvas().png())
// Another box's tool can paint on this easel: it finds the canvas here and calls this to show the result.
thisBox.properties["studio.refresh"] = { show() }

// "#rrggbb", one tube ("ultramarine blue"), or a pile of tubes by parts ("lead white:3, yellow ochre:1").
class Paint(val color: Vec4, val tubes: Map<String, Double>?)
fun paintOf(spec: String): Paint {
    val s = spec.trim()
    if (s.startsWith("#") && (s.length == 7 || s.length == 4)) {
        val hex = if (s.length == 4) s.drop(1).map { "$it$it" }.joinToString("") else s.drop(1)
        val v = hex.chunked(2).map { it.toInt(16) / 255.0 }
        return Paint(rgb(v[0], v[1], v[2]), null)
    }
    val tubes: Map<String, Double> = s.split(',').filter { it.isNotBlank() }.associate { part ->
        val (name, parts) = part.split(':').let { it[0].trim() to (it.getOrNull(1)?.trim()?.toDoubleOrNull() ?: 1.0) }
        val tube = Tubes.all.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: throw IllegalArgumentException("no tube \"$name\": colours are #rrggbb or tube names from the palette (${Tubes.names.joinToString()})")
        tube.name to parts
    }
    require(tubes.isNotEmpty()) { "no colour given" }
    return Paint(Tubes.mix(tubes), tubes)
}

// A smooth curve through flat [x0, y0, x1, y1, ...] (Catmull-Rom as cubics), closed when asked.
fun through(xy: List<Double>, closed: Boolean = false): FLine {
    val p = xy.chunked(2).filter { it.size == 2 }
    require(p.size >= 2) { "a path needs at least two points, as [x0, y0, x1, y1, ...]" }
    val pts = if (closed) p + listOf(p[0]) else p
    val line = FLine().moveTo(pts[0][0], pts[0][1])
    if (pts.size == 2) return line.lineTo(pts[1][0], pts[1][1])
    for (i in 0 until pts.size - 1) {
        val a = if (i > 0) pts[i - 1] else if (closed) p[p.size - 1] else pts[i]
        val b = pts[i]; val c = pts[i + 1]
        val d = if (i + 2 < pts.size) pts[i + 2] else if (closed) p[1] else c
        line.cubicTo(b[0] + (c[0] - a[0]) / 6, b[1] + (c[1] - a[1]) / 6,
            c[0] - (d[0] - b[0]) / 6, c[1] - (d[1] - b[1]) / 6, c[0], c[1])
    }
    return line
}
fun region(xy: List<Double>) = through(xy, closed = true)
fun rad(degrees: Double) = degrees * PI / 180

fun brushOf(name: String) = BrushKind.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    ?: throw IllegalArgumentException("no brush \"$name\": ${BrushKind.entries.joinToString { it.name }}")
fun handlingOf(name: String) = Handling.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    ?: throw IllegalArgumentException("no handling \"$name\": ${Handling.entries.joinToString { it.name }}")

fun pct(x: Double) = "${(x * 100).roundToInt()}%"
fun state(): String = canvas().stats.let {
    "about ${it.handMinutes.roundToInt()} minutes of a painter's work so far; " +
        "paint open ${pct(it.open)}, setting ${pct(it.setting)}, tacky ${pct(it.tacky)}, dry or bare ${pct(it.dry)}"
}
// The hung paintings, newest first, as a strip of thumbnails in the "gallery" channel.
fun galleryDir() = File(thisBox.inside.filename, "gallery")
fun showGallery() {
    val files = galleryDir().listFiles { f -> f.name.endsWith(".png") }.orEmpty().sortedByDescending { it.lastModified() }
    if (files.isEmpty()) return
    display("gallery", files.joinToString("", "<div style='display:flex;flex-wrap:wrap;gap:10px'>", "</div>") { f ->
        val img = javax.imageio.ImageIO.read(f)
        val w = 220; val h = img.height * w / img.width
        val small = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        small.createGraphics().apply {
            setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(img, 0, 0, w, h, null); dispose()
        }
        val out = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(small, "jpg", it) }
        "<figure style='margin:0'><img src='data:image/jpeg;base64,${Base64.getEncoder().encodeToString(out.toByteArray())}' width='$w'>" +
            "<figcaption style='font-style:italic;text-align:center'>${f.nameWithoutExtension}</figcaption></figure>"
    })
}

fun done(what: String): Map<String, Any> { show(); return mapOf("ok" to true, "did" to what, "canvas" to state()) }

if (!PaintCanvas.isAvailable) markdown(PaintLibrary.describe()) else thisBox.agentExtension("studio") {

    tool("paint.look") {
        describe("Look at the painting as it stands: an image of the canvas (dried, lit from the upper left), and how wet it is.")
        effects(ToolEffect.CAPTURE_IMAGE)
        val wet by param<Boolean>("show the paint as it is now, wet, without relief", default = false)
        action {
            val png = canvas().png(relief = if (wet()) 0.0 else 0.6, wet = wet())
            // Every look is also kept, numbered, in the document's progress folder: the painting's stages.
            val dir = File(thisBox.inside.filename, "progress").apply { mkdirs() }
            File(dir, "%03d.png".format((dir.list()?.size ?: 0) + 1)).writeBytes(png)
            image(pngUrl(png), state())
        }
    }

    tool("paint.stroke") {
        describe("Drag a loaded brush along one or more paths. Each path is one movement of the hand, a smooth curve through " +
            "[x0, y0, x1, y1, ...]; the brush is loaded once, before the first, and runs out along the rest the way a real one does.")
        effects(ToolEffect.CONTROL_RUNTIME)
        val paths by param<List<List<Double>>>("paths, each [x0, y0, x1, y1, ...] in canvas units")
        val color by param<String>("#rrggbb, a tube name, or tubes by parts: \"lead white:3, yellow ochre:1\"")
        val brush by param<String>("Round, HogFlat, Filbert, Fan, Rigger or Badger", default = "Filbert")
        val width by param<Double>("brush width in canvas units", default = 20.0)
        val pressure by param<List<Double>>("pressure at the start and end of each movement, 0..1", default = listOf(0.7, 0.7))
        val thinness by param<Double>("0 paint from the tube, 1 thinned with oil (tube mixes only)", default = 0.0)
        val wipe by param<Double>("how much of the brush's last paint to wipe off first; 0 mixes into it", default = 0.85)
        val hiding by param<Double>("0.05 a glaze, 0.5 a scumble, 0.92 body colour (hex colours only)", default = 0.92)
        action {
            val c = canvas(); val p = paintOf(color())
            val b = c.brush(brushOf(brush()), width = width())
            if (p.tubes != null) b.load(p.tubes, medium = thinness(), wipe = wipe()) else b.load(p.color, hiding = hiding(), wipe = wipe())
            val line = FLine()
            paths().forEach { line += through(it) }
            val pr = pressure()
            c.stroke(line, b, pressure = pr.first()..(pr.getOrNull(1) ?: pr.first()))
            done("${paths().size} stroke(s), ${brush()} ${width()}")
        }
    }

    tool("paint.dab") {
        describe("Touch the brush down at points and lift it: dabs, petals, leaves, highlights. One load for all of them.")
        effects(ToolEffect.CONTROL_RUNTIME)
        val points by param<List<Double>>("[x0, y0, x1, y1, ...]")
        val color by param<String>("#rrggbb, a tube name, or tubes by parts")
        val brush by param<String>("Round, HogFlat, Filbert, Fan, Rigger or Badger", default = "Round")
        val width by param<Double>("brush width in canvas units", default = 12.0)
        val pressure by param<Double>("harder makes a bigger, fuller mark", default = 0.6)
        val angle by param<Double>("a flat's wide axis, degrees", default = 0.0)
        action {
            val c = canvas(); val p = paintOf(color())
            val b = c.brush(brushOf(brush()), width = width())
            if (p.tubes != null) b.load(p.tubes) else b.load(p.color)
            points().chunked(2).filter { it.size == 2 }.forEach { c.touch(b, it[0], it[1], pressure = pressure(), angle = rad(angle())) }
            done("${points().size / 2} dab(s)")
        }
    }

    tool("paint.work") {
        describe("Cover a region with many strokes planned like a painter's: Broad to lay in, Body for the main painting, Detail " +
            "(clipped), Hatch, Glaze (thin, soft), Scumble (broken), or Blend (a clean brush that softens wet paint, lays nothing).")
        effects(ToolEffect.CONTROL_RUNTIME)
        val outline by param<List<Double>>("the region's outline, [x0, y0, x1, y1, ...], closed automatically")
        val color by param<String>("#rrggbb, a tube name, or tubes by parts", default = "#ffffff")
        val handling by param<String>("Broad, Body, Detail, Hatch, Glaze, Scumble or Blend", default = "Body")
        val angle by param<Double?>("stroke direction in degrees (0 left to right, 90 top to bottom); null for the handling's own", default = null)
        val width by param<Double?>("brush width in canvas units; null for the handling's own", default = null)
        val coverage by param<Double?>("layers of strokes over each point; null for the handling's own", default = null)
        val hiding by param<Double>("how much one coat hides: 0.05 glaze .. 0.92 body colour", default = 0.85)
        val feather by param<Double>("soften the region's edge by this many units", default = 0.0)
        action {
            canvas().work(region(outline()), paintOf(color()).color, handling = handlingOf(handling()), hiding = hiding(),
                angle = angle()?.let(::rad), width = width(), coverage = coverage(), feather = feather())
            done("worked a region, ${handling()}")
        }
    }

    tool("paint.glaze") {
        describe("A thin transparent glaze over a region or the whole canvas, to warm, cool or deepen. Dries the canvas first.")
        effects(ToolEffect.CONTROL_RUNTIME)
        val color by param<String>("#rrggbb, a tube name, or tubes by parts")
        val outline by param<List<Double>?>("the region, [x0, y0, ...]; null for the whole canvas", default = null)
        val coats by param<Double>("how thick", default = 1.0)
        val feather by param<Double>("soften the edge by this many units", default = 20.0)
        action {
            canvas().dry()
            canvas().glaze(paintOf(color()).color, outline()?.let(::region), coats = coats(), feather = feather())
            done("glazed")
        }
    }

    tool("paint.draw") {
        describe("Draw lines in graphite or chalk: underdrawing, contours, a ruled horizon. 'sketch' restates each line a few times, the way a hand searches for it.")
        effects(ToolEffect.CONTROL_RUNTIME)
        val paths by param<List<List<Double>>>("paths, each [x0, y0, x1, y1, ...]")
        val pencil by param<String>("a grade, 6H..8B (HB is middling), or chalk", default = "HB")
        val pressure by param<Double>("0..1", default = 0.6)
        val sketch by param<Boolean>("restate each line a few times", default = false)
        val ruler by param<Boolean>("straight edges", default = false)
        action {
            val c = canvas()
            val p = if (pencil().equals("chalk", ignoreCase = true)) c.chalk() else c.pencil(pencil())
            paths().forEach { if (sketch()) c.sketch(through(it), p, pressure = pressure()) else c.draw(through(it), p, pressure = pressure(), ruler = ruler()) }
            done("${paths().size} line(s) in ${pencil()}")
        }
    }

    tool("paint.hatch") {
        describe("Hatch a region with parallel pencil or chalk strokes, for shading in a drawing.")
        effects(ToolEffect.CONTROL_RUNTIME)
        val outline by param<List<Double>>("the region, [x0, y0, ...]")
        val pencil by param<String>("a grade, 6H..8B, or chalk", default = "2B")
        val angle by param<Double>("degrees", default = 45.0)
        val spacing by param<Double>("between strokes, canvas units", default = 6.0)
        action {
            val c = canvas()
            c.hatch(region(outline()), if (pencil().equals("chalk", true)) c.chalk() else c.pencil(pencil()), angle = rad(angle()), spacing = spacing())
            done("hatched")
        }
    }

    tool("paint.stipple") {
        describe("Stipple a region with many small touches: foliage, texture, sparkle on water, distant crowds.")
        effects(ToolEffect.CONTROL_RUNTIME)
        val outline by param<List<Double>>("the region, [x0, y0, ...]")
        val color by param<String>("#rrggbb, a tube name, or tubes by parts")
        val size by param<Double>("touch size, canvas units", default = 4.0)
        val coverage by param<Double>("touches per point on average", default = 0.6)
        val cluster by param<Double>("0 even, 1 clumped", default = 0.3)
        val hiding by param<Double>("how much each touch hides what is under it", default = 0.7)
        action {
            canvas().stipple(region(outline()), paintOf(color()).color, size = size(), coverage = coverage(), cluster = cluster(),
                hiding = hiding(), feather = size() * 2)
            done("stippled")
        }
    }

    tool("paint.wait") {
        describe("Let simulated time pass so wet paint sets (minutes), or dry the canvas completely: paint laid afterwards no longer mixes into what is there.")
        effects(ToolEffect.CONTROL_RUNTIME)
        val minutes by param<Double?>("how long; null dries the canvas completely", default = null)
        action { minutes()?.let { canvas().wait(it) } ?: canvas().dry(); done(minutes()?.let { "waited $it minutes" } ?: "dried") }
    }

    tool("paint.fresh") {
        describe("Put a new canvas on the easel. The old one is not lost: it stays with the conversation, and rewinding brings it back.")
        effects(ToolEffect.CONTROL_RUNTIME)
        val ground by param<String>("Oil (warm primed linen), OilRedGround, Linen (raw) or Bare (flat white)", default = "Oil")
        val aspect by param<Double>("width / height: 1.25 landscape, 0.8 portrait, 1 square", default = 1.25)
        action {
            val g = PaintGround.entries.firstOrNull { it.name.equals(ground(), true) } ?: throw IllegalArgumentException("no ground \"${ground()}\": ${PaintGround.entries.joinToString()}")
            replace(fresh(g, aspect(), seed = System.nanoTime() % 100_000))
            done("a fresh ${g.name} canvas, ${aspect()}")
        }
    }

    tool("paint.crack") {
        describe("Age the finished painting: craquelure through paint and ground, with dirt in the cracks. Irreversible on this branch.")
        exposure(ToolExposure.DEFERRED)
        effects(ToolEffect.CONTROL_RUNTIME)
        val even by param<Boolean>("one even network instead of an aged hierarchy", default = false)
        action { canvas().crack(if (even()) CrackKind.Even else CrackKind.Aged); done("cracked") }
    }

    tool("paint.hang") {
        describe("Hang the painting in the gallery under a title: a dried, lit PNG in the document's gallery folder, shown in this box's gallery panel.")
        effects(ToolEffect.FILESYSTEM_WRITE)
        val title by param<String>("the painting's title")
        action {
            val dir = galleryDir().apply { mkdirs() }
            val file = File(dir, title().replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "untitled" } + ".png")
            file.writeBytes(canvas().png(relief = 0.7, gloss = 0.15))
            showGallery()
            mapOf("ok" to true, "hung" to file.name)
        }
    }

    context("canvas") {
        val c = canvas()
        "The canvas on the Studio easel is 1000 units wide and ${c.heightUnits.roundToInt()} tall (aspect ${c.aspect}), " +
            "x to the right, y DOWN from the top edge. ${state()}."
    }

    context("craft", changesOften = false) {
        """
        |You are painting in oils on a simulated canvas with the paint_* tools; your painting is the Studio box's output.
        |Paint the way a painter does: block in big shapes with paint_work (Broad, then Body) in thin, muted paint first; build
        |light and form with thicker paint; finish with paint_stroke and paint_dab details and highlights. paint_look shows you
        |the canvas — look after each stage, and judge it as a painting. Wet paint mixes into what you lay over it: wait or dry
        |(paint_wait) when you want a clean layer on top. Every request you are given starts from the canvas as the conversation
        |left it, and rewinding the chat brings back the canvas as it was then, so be bold.
        |Colours: "#rrggbb", or real tube paints mixed by parts, "lead white:4, cadmium yellow:1" (they mix like paint, so blue and
        |yellow make green). Tubes: ${Tubes.names.joinToString()}.
        """.trimMargin()
    }

    command("canvas", "show the canvas here") { "![the canvas](${pngUrl(canvas().png())})\n\n${state()}" }
    command("gallery", "list the hung paintings") { _ ->
        galleryDir().listFiles { f -> f.name.endsWith(".png") }.orEmpty().sortedBy { it.lastModified() }
            .joinToString("\n") { "- *${it.nameWithoutExtension}*" }.ifBlank { "Nothing hung yet." }
    }
    command("palette", "list the tube paints") { _ ->
        Tubes.all.joinToString("\n") { t ->
            val hex = "#%02x%02x%02x".format((t.color.x * 255).roundToInt(), (t.color.y * 255).roundToInt(), (t.color.z * 255).roundToInt())
            "- <span style=\"display:inline-block;width:1em;height:1em;background:$hex;vertical-align:middle\"></span> **${t.name}** — ${t.pigment}, hiding ${t.hiding}"
        }
    }

    // The canvas belongs to the conversation: a rewind restores it even over changes made outside the chat
    // (the replaced canvas is saved first), and reopening the document puts it back where the chat left it.
    branchState("canvas",
        save = { canvas().saveState(it.path) },
        restore = { f -> replace(f?.let { PaintCanvas.load(it.path) } ?: fresh()); show() },
        onConflict = BranchConflict.RESTORE, resumeOnOpen = true)
}

if (PaintCanvas.isAvailable) { show(); showGallery() }
