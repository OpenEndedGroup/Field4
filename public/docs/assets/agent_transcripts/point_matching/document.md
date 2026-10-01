# Field Document: point_matching

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. matchPoints — a disc onto a ring

<!-- field-box id="match_hello" index="0" -->

### Narrative

From `code`

Run it. 600 points scattered in a disc and 600 on a ring around it. matchPoints gives
every disc point a ring point of its own, keeping the total squared travel small.

```edn [field-box id="match_hello" role="properties"]
{"id" "match_hello" "index" 0 "name" "matchPoints — a disc onto a ring" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "matchPoints — a disc onto a ring" "_tombstone" false "frame" #afield/rect [80.0 80.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "match_hello_code_.kt" "lines" 21 "characters" 1052 "sha256" "a4c5ab43e86e1d4f1e592a6961882ad579c767568bd9353860cd9f789ca6871a"}]}
```

```kotlin [field-property box="match_hello" name="code" path="match_hello_code_.kt"]
// Run it. 600 points scattered in a disc and 600 on a ring around it. matchPoints gives
// every disc point a ring point of its own, keeping the total squared travel small.
val rnd = java.util.Random(4)
val disc = List(600) {
    val r = 150 * sqrt(rnd.nextDouble()); val a = 2 * PI * rnd.nextDouble()
    vec(r * cos(a), r * sin(a))
}
val ring = List(600) { val a = 2 * PI * it / 600; vec(300 * cos(a), 300 * sin(a)) }

val partner = matchPoints(disc, ring).reorder(ring)

// Left: the matching. Right: ring point i for disc point i, which is what you get by
// interpolating two point lists without matching them first.
fun chord(from: Vec2, to: Vec2, dx: Double) = FLine().moveTo(from.x + dx, from.y).lineTo(to.x + dx, to.y).also {
    it.thickness = 0.8
    it.color = okhsl(atan2(to.y.toDouble(), to.x.toDouble()) * 180 / PI + 360, 0.85, 0.65, 0.8)
}
lines(disc.indices.map { chord(disc[it], partner[it], -340.0) } +
      disc.indices.map { chord(disc[it], ring[it], 340.0) },
      w = 1000.0, h = 500.0, fitToSize = true)
displayOpen("lines")
```

## 2. a morph, matched and unmatched

<!-- field-box id="square_to_heart" index="1" -->

### Narrative

From `code`

Launch it (Alt-R). 4000 points flow from a square into a heart and back. On the left
each point heads for the partner matchPoints gave it; on the right, for the heart
point that shares its list index.

```edn [field-box id="square_to_heart" role="properties"]
{"id" "square_to_heart" "index" 1 "name" "a morph, matched and unmatched" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "a morph, matched and unmatched" "_tombstone" false "frame" #afield/rect [80.0 760.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "square_to_heart_code_.kt" "lines" 40 "characters" 1845 "sha256" "e8554d8949daf8d44f780821c576110b61123d9d76ce007874e73653aed67e03"}]}
```

```kotlin [field-property box="square_to_heart" name="code" path="square_to_heart_code_.kt"]
// Launch it (Alt-R). 4000 points flow from a square into a heart and back. On the left
// each point heads for the partner matchPoints gave it; on the right, for the heart
// point that shares its list index.
val rnd = java.util.Random(1)
val n = 4000
val square = List(n) { vec(rnd.nextDouble() * 2 - 1, rnd.nextDouble() * 2 - 1) }
val heart = mutableListOf<Vec2>()
while (heart.size < n) {
    val x = rnd.nextDouble() * 2.6 - 1.3; val y = rnd.nextDouble() * 2.6 - 1.3
    val a = x * x + y * y - 1
    if (a * a * a - x * x * y * y * y <= 0) heart += vec(x, 0.1 - y) // image y runs down
}
val partner = matchPoints(square, heart).reorder(heart)

// Each point keeps the colour of where it started in the square.
val colour = IntArray(n) {
    val c = okhsl(atan2(square[it].y, square[it].x) * 180 / PI + 360, 0.9, 0.5 + 0.25 * square[it].length() / 1.42)
    (255 shl 24) or ((c.x * 255).toInt() shl 16) or ((c.y * 255).toInt() shl 8) or (c.z * 255).toInt()
}

val imageWidth = 900; val imageHeight = 440
fun splat(px: IntArray, centre: Int, a: Vec2, b: Vec2, t: Double, c: Int) {
    val x = centre + ((a.x + (b.x - a.x) * t) * 160).toInt()
    val y = imageHeight / 2 + ((a.y + (b.y - a.y) * t) * 160).toInt()
    for (dy in 0..1) for (dx in 0..1)
        if (x + dx in 0 until imageWidth && y + dy in 0 until imageHeight) px[(y + dy) * imageWidth + x + dx] = c
}

displayOpen("morph")
loop {
    if (frameNumber % 2 == 0) {
        val t = 0.5 - 0.5 * cos(frameNumber / 70.0)
        val px = IntArray(imageWidth * imageHeight) { 0xff12161c.toInt() }
        for (i in 0 until n) {
            splat(px, imageWidth / 4, square[i], partner[i], t, colour[i])
            splat(px, 3 * imageWidth / 4, square[i], heart[i], t, colour[i])
        }
        display("morph", afield.util.PngEncoder.encode(px, imageWidth, imageHeight))
    }
}
```

## 3. a million pixels, a million targets

<!-- field-box id="million_pixels" index="2" -->

### Narrative

From `code`

Run it; a few seconds. Every pixel of a 1024 x 1024 square is a source point, and a
million targets are scattered through three discs, each holding a share proportional
to its area. Each pixel gets a target of its own, and is painted with that target's
place in its disc: hue for the angle, lightness for the distance from the centre.

```edn [field-box id="million_pixels" role="properties"]
{"id" "million_pixels" "index" 2 "name" "a million pixels, a million targets" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "a million pixels, a million targets" "_tombstone" false "frame" #afield/rect [80.0 1440.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "million_pixels_code_.kt" "lines" 37 "characters" 1815 "sha256" "ed01f4f318fe76b508ea40f128f72127bd67a59cf5edb5855fd7f75e954faa9f"}]}
```

```kotlin [field-property box="million_pixels" name="code" path="million_pixels_code_.kt"]
// Run it; a few seconds. Every pixel of a 1024 x 1024 square is a source point, and a
// million targets are scattered through three discs, each holding a share proportional
// to its area. Each pixel gets a target of its own, and is painted with that target's
// place in its disc: hue for the angle, lightness for the distance from the centre.
val side = 1024
val n = side * side
val ax = FloatArray(n) { it % side + 0.5f }
val ay = FloatArray(n) { it / side + 0.5f }

val discs = listOf(Triple(330.0, 400.0, 250.0), Triple(740.0, 300.0, 160.0), Triple(640.0, 760.0, 200.0))
val area = discs.sumOf { it.third * it.third }
val rnd = java.util.Random(9)
val home = IntArray(n)
val bx = FloatArray(n); val by = FloatArray(n)
for (j in 0 until n) {
    var pick = rnd.nextDouble() * area
    var d = 0
    while (pick > discs[d].third * discs[d].third) { pick -= discs[d].third * discs[d].third; d++ }
    val r = discs[d].third * sqrt(rnd.nextDouble()); val a = 2 * PI * rnd.nextDouble()
    home[j] = d
    bx[j] = (discs[d].first + r * cos(a)).toFloat(); by[j] = (discs[d].second + r * sin(a)).toFloat()
}

val start = System.nanoTime()
val match = BspOt2D.match(ax, ay, bx, by, BspOt2D.Options(trees = 8))
val seconds = (System.nanoTime() - start) / 1e9

val px = IntArray(n) { i ->
    val j = match.targetOfSource[i]
    val (cx, cy, radius) = discs[home[j]]
    val dx = bx[j] - cx; val dy = by[j] - cy
    val c = okhsl(atan2(dy, dx) * 180 / PI + 360, 0.85, 0.25 + 0.6 * sqrt(dx * dx + dy * dy) / radius)
    (255 shl 24) or ((c.x * 255).toInt() shl 16) or ((c.y * 255).toInt() shl 8) or (c.z * 255).toInt()
}
display("main", afield.util.PngEncoder.encode(px, side, side))
displayOpen("main")
println("matched %,d to %,d in %.1f s, rms travel %.1f px".format(n, n, seconds, sqrt(match.meanSquaredDistance)))
```

