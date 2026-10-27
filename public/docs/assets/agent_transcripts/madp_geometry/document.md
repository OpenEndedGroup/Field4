# Field Document: madp_geometry

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 11 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. fungal tree

<!-- field-box id="fungal_tree" index="0" -->

```edn [field-box id="fungal_tree" role="properties"]
{"id" "fungal_tree" "index" 0 "name" "fungal tree" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "fungal tree" "_tombstone" false "frame" #afield/rect [80.0 2000.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "fungal_tree_notes_.md" "lines" 1 "characters" 253 "sha256" "a5bafe6ef1ffdd94d40c681c01860c243810847743a63fc8d7f3188b5fdf8f63"} {"name" "code" "language" "kotlin" "path" "fungal_tree_code_.kt" "lines" 57 "characters" 2161 "sha256" "ba1e5c73beea95d145ac9d6d8a66ded8a36f5901b9fc2abb2840f9fb48e0947a"}]}
```

```markdown [field-property box="fungal_tree" name="notes" path="fungal_tree_notes_.md"]
**fungal tree**: a recursive `branch` function grows open tubes, each one thinner, and caps the tips with revolved mushrooms placed by `push`, `translate`, `scale` and `pop`. The depth in each part's `mesh` name is visible in the view's **Parts** list.
```

```kotlin [field-property box="fungal_tree" name="code" path="fungal_tree_code_.kt"]
geometry {
    seed(42)
    view(background = "#060806", ground = true, grid = false)

    fun branch(start: Vec3, dir: Vec3, length: Double, radius: Double, depth: Int) {
        val bend = vec(rand(-0.28, 0.28), rand(-0.05, 0.25), rand(-0.28, 0.28))
        val hue = 88 + depth * 18 + rand(-8.0, 10.0)

        mesh("bark depth $depth")
        mat(color = "#ffffff", roughness = 0.88, metalness = 0.01, flat = true)
        tube(9, 6, closed = false) { s ->
            val eased = s * s * (3 - 2 * s)
            val sway = sin(s * PI) * 0.25
            section(
                start + (length * s) * dir + sway * bend,
                radius = radius * (1 - eased * 0.58),
                color = hsl(hue, 0.38, 0.28 + depth * 0.035 + s * 0.08),
            )
        }
        val end = start + length * dir + 0.22 * bend

        if (depth <= 0) {
            mesh("caps")
            mat(color = "#fff4d8", roughness = 0.64, metalness = 0.0, flat = true)
            push()
            translate(end)
            scale(radius * 4.4, radius * 0.9, radius * 4.4)
            revolve(
                [
                    profile(0.04, 0.0, hsl(36, 0.9, 0.72)),
                    profile(0.58, 0.08, hsl(32, 0.72, 0.55)),
                    profile(1.0, 0.0, hsl(24, 0.75, 0.44)),
                    profile(0.42, -0.16, hsl(42, 0.58, 0.62)),
                ],
                18,
            )
            pop()
            return
        }

        val branches = if (depth > 2) 3 else 2
        for (i in 0 until branches) {
            val turn = i.toDouble() / branches * TAU + rand(-0.5, 0.5)
            val lift = 0.48 + rand(0.0, 0.32)
            val next =
                vec(
                        dir.x * 0.62 + cos(turn) * (0.45 + rand(0.0, 0.22)),
                        dir.y * 0.52 + lift,
                        dir.z * 0.62 + sin(turn) * (0.45 + rand(0.0, 0.22)),
                    )
                    .normalize()
            branch(end - (radius * 0.6) * dir, next, length * rand(0.56, 0.76), radius * rand(0.58, 0.72), depth - 1)
        }
    }

    branch(vec(0.0, -1.1, 0.0), vec(0.0, 1.0, 0.0), 1.45, 0.16, 4)
}
```

## 2. signal tear — launch me

<!-- field-box id="signal_tear" index="1" -->

### Narrative

From `code`

Another animation: the same grid every frame, torn along random rows as t moves. Launch it (alt+up).

```edn [field-box id="signal_tear" role="properties"]
{"id" "signal_tear" "index" 1 "name" "signal tear — launch me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "signal tear — launch me" "_tombstone" false "frame" #afield/rect [900.0 1360.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "signal_tear_notes_.md" "lines" 1 "characters" 249 "sha256" "4b0f01d48a7b28f794ebcd9c6606556bfb559e38da6de0f5b728033bc9cee620"} {"name" "code" "language" "kotlin" "path" "signal_tear_code_.kt" "lines" 26 "characters" 901 "sha256" "d0e2e8719d2fe79adc80e6afc55710dc68fd3345d419b8381f5368f2daeffee1"}]}
```

```markdown [field-property box="signal_tear" name="notes" path="signal_tear_notes_.md"]
**signal tear** (animated): a scanline grid torn along random rows as `t` moves, with a faint emissive glow. Launch the box (alt+up) to play it and stop it with alt+down; running it draws the first frame. `tear.pow(8)` sets how rarely a row breaks.
```

```kotlin [field-property box="signal_tear" name="code" path="signal_tear_code_.kt"]
// Another animation: the same grid every frame, torn along random rows as t moves. Launch it (alt+up).
animateGeometry {
    view(background = "#050608", ground = true, grid = false)

    mesh("signal tear")
    mat(
        color = "#ffffff",
        roughness = 0.52,
        metalness = 0.18,
        emissive = "#07121c",
        emissiveIntensity = 0.55,
        flat = true,
    )

    surface(72, 36) { u, v ->
        val row = floor(v * 36)
        val torn = sin(row * 12.989 + t * 4.0) * 0.5 + 0.5
        val tear = torn.pow(8) * (if (sin(t * 1.7 + row) > -0.35) 1.0 else 0.0)

        val x = (u - 0.5) * 6.6 + tear * sin(row * 3.1) * 0.72
        val z = (v - 0.5) * 3.6
        val band = sin((u * 28 + v * 7) + t * 5)
        val y = band * 0.1 + tear * 0.55 + noise(u * 9 + t, v * 9) * 0.12
        vertex(x, y, z, hsl(190 + band * 45 + tear * 120, 0.95, 0.5 + tear * 0.12))
    }
}
```

## 3. knot tube

<!-- field-box id="knot_tube" index="2" -->

```edn [field-box id="knot_tube" role="properties"]
{"id" "knot_tube" "index" 2 "name" "knot tube" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "knot tube" "_tombstone" false "frame" #afield/rect [900.0 720.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "knot_tube_notes_.md" "lines" 1 "characters" 206 "sha256" "e3ed44278872dee3e5b4030b6f8a35437c073143d282a77eec1b10db3890086f"} {"name" "code" "language" "kotlin" "path" "knot_tube_code_.kt" "lines" 16 "characters" 483 "sha256" "9afc5cb37ce49d1cee21e673bf4bfb8459fcb16f9a02a3ee877ed15bbc8bbb89"}]}
```

```markdown [field-property box="knot_tube" name="notes" path="knot_tube_notes_.md"]
**knot tube**: `tube` sweeps a ring of 10 corners along a parametric curve sampled 260 times; the closed tube joins its last ring to its first. Edit the curve in `section(vec(…))`, or the radius beside it.
```

```kotlin [field-property box="knot_tube" name="code" path="knot_tube_code_.kt"]
geometry {
    view(background = "#05060b", ground = true, grid = false)

    mesh("parametric knot")
    mat(color = "#ffffff", roughness = 0.48, metalness = 0.18, flat = true)

    tube(260, 10) { s ->
        val a = s * TAU * 2
        val pulse = 1.48 + cos(a * 3) * 0.32
        section(
            vec(cos(a) * pulse, sin(a * 1.5) * 0.78, sin(a) * pulse),
            radius = 0.115 + sin(a * 5) * 0.018,
            color = hsl(205 + s * 180, 0.88, 0.56),
        )
    }
}
```

## 4. city blocks

<!-- field-box id="city_blocks" index="3" -->

```edn [field-box id="city_blocks" role="properties"]
{"id" "city_blocks" "index" 3 "name" "city blocks" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "city blocks" "_tombstone" false "frame" #afield/rect [900.0 2640.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "city_blocks_notes_.md" "lines" 1 "characters" 203 "sha256" "0025f15ef1842155a5533aeac4afbce5f7ada0ec4898878545130a83f3690f6a"} {"name" "code" "language" "kotlin" "path" "city_blocks_code_.kt" "lines" 23 "characters" 961 "sha256" "6cfcf2e660e3b3c9d1f2cb9c3fb4959ea29daeca230372d33a3a08fd0655e593"}]}
```

```markdown [field-property box="city_blocks" name="notes" path="city_blocks_notes_.md"]
**city blocks**: boxes on an 11 × 11 grid, skipped where `noise` is high, with a second material for a ring of copper towers. The colour state changes per block; the material state changes per district.
```

```kotlin [field-property box="city_blocks" name="code" path="city_blocks_code_.kt"]
geometry {
    seed(7)
    view(background = "#060708", ground = true, grid = true)

    // Boxes are triangle recipes too; the material state changes by district.
    mat(color = "#ffffff", roughness = 0.8, metalness = 0.04, flat = true)
    for (x in -5..5) for (z in -5..5) {
        if (noise(x * 0.7, z * 0.7) > 0.38) continue
        val height = 0.18 + rand(0.05, 1.0).pow(1.8) * 2.8
        val warm = (x + z + 10) / 20.0
        color(hsl(178 + warm * 46 + height * 10, 0.5, 0.28 + height * 0.08))
        box(vec(x * 0.42, height * 0.5 - 1.05, z * 0.42), vec(0.31, height, 0.31))
    }

    mesh("copper towers")
    mat(color = "#e2a75a", roughness = 0.5, metalness = 0.32, flat = true)
    for (i in 0 until 18) {
        val angle = i / 18.0 * TAU
        val r = 2.75 + noise(i.toDouble(), 0.0) * 0.28
        color(hsl(35 + i * 4, 0.7, 0.46))
        box(vec(cos(angle) * r, 0.25, sin(angle) * r), vec(0.16, 2.2 + rand(0.0, 0.85), 0.16))
    }
}
```

## 5. triangle lesson

<!-- field-box id="triangle_lesson" index="4" -->

### Narrative

From `code`

A grammar of triangles: winding (flip swaps two corners), colour interpolated across each face,
and a small box standing on each one.

```edn [field-box id="triangle_lesson" role="properties"]
{"id" "triangle_lesson" "index" 4 "name" "triangle lesson" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "triangle lesson" "_tombstone" false "frame" #afield/rect [80.0 3280.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "triangle_lesson_notes_.md" "lines" 1 "characters" 261 "sha256" "af2b13589c34d93ce81e6437abd73a744aacc5b61c745e4a4aba6a7d51c61601"} {"name" "code" "language" "kotlin" "path" "triangle_lesson_code_.kt" "lines" 27 "characters" 1100 "sha256" "ad5a2f862b929b8da9fed9e8c9617a1c35a781877ce6b85733b9c429232b834d"}]}
```

```markdown [field-property box="triangle_lesson" name="notes" path="triangle_lesson_notes_.md"]
**triangle lesson**: a grammar of triangles. Alternate faces swap two corners (winding), each face blends three colours, and a small box stands on each one at a point found with `mix`. Set `side = GeometrySide.FRONT` to see which faces are wound the other way.
```

```kotlin [field-property box="triangle_lesson" name="code" path="triangle_lesson_code_.kt"]
// A grammar of triangles: winding (flip swaps two corners), colour interpolated across each face,
// and a small box standing on each one.
geometry {
    view(background = "#070809", ground = true, grid = true)
    mat(color = "#ffffff", roughness = 0.78, metalness = 0.02, flat = true, side = GeometrySide.DOUBLE)

    for (y in 0 until 6) for (x in 0 until 10) {
        val cx = (x - 4.5) * 0.48
        val cz = (y - 2.5) * 0.5
        val tilt = sin(x * 0.7 + y * 1.3) * 0.24
        val flip = (x + y) % 2 == 0
        val a = vec(cx - 0.22, -0.65 + tilt, cz - 0.18)
        val b = vec(cx + 0.24, -0.65 - tilt * 0.5, cz - 0.15)
        val c = vec(cx - 0.02, -0.18 + tilt * 0.8, cz + 0.25)
        val h = 170 + x * 11 + y * 18

        tri(
            vertex(if (flip) a else b, hsl(h, 0.86, 0.42)),
            vertex(if (flip) b else a, hsl(h + 34, 0.86, 0.58)),
            vertex(c, hsl(h + 74, 0.9, 0.67)),
        )

        val m = mix(mix(a, b, 0.5), c, 0.33)
        color(hsl(h + 18, 0.7, 0.36))
        box(vec(m.x, m.y + 0.08, m.z), vec(0.035, 0.28 + abs(tilt), 0.035))
    }
}
```

## 6. lathe vessel

<!-- field-box id="lathe_vessel" index="5" -->

```edn [field-box id="lathe_vessel" role="properties"]
{"id" "lathe_vessel" "index" 5 "name" "lathe vessel" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "lathe vessel" "_tombstone" false "frame" #afield/rect [80.0 720.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "lathe_vessel_notes_.md" "lines" 1 "characters" 214 "sha256" "702666a88c901bece63d32155f54210360c8d8d91484e7bd51b0f9503b69e0cc"} {"name" "code" "language" "kotlin" "path" "lathe_vessel_code_.kt" "lines" 20 "characters" 754 "sha256" "159df79aea8e8cc7842f8c496faf175ce8526881e47d147d5dc5db69cd01c812"}]}
```

```markdown [field-property box="lathe_vessel" name="notes" path="lathe_vessel_notes_.md"]
**lathe vessel**: a profile of 36 points, radius and height, turned round the y axis by `revolve`. A second `mesh` and `mat` give the rim its own metallic material. Change `waist` or `ripple` to throw another pot.
```

```kotlin [field-property box="lathe_vessel" name="code" path="lathe_vessel_code_.kt"]
geometry {
    view(background = "#060807", ground = true, grid = true)

    val body =
        List(36) { i ->
            val v = i / 35.0
            val waist = 0.72 + sin(v * PI) * 0.42
            val ripple = sin(v * PI * 8) * 0.045
            val shoulder = exp(-(v - 0.72) * (v - 0.72) * 38) * 0.22
            profile(waist + ripple + shoulder, (v - 0.5) * 3.2, hsl(28 + v * 34, 0.58, 0.42 + v * 0.16))
        }

    mesh("vessel body")
    mat(color = "#ffffff", roughness = 0.72, metalness = 0.02, flat = true)
    revolve(body, 96)

    mesh("rim")
    mat(color = "#f4d28a", roughness = 0.38, metalness = 0.65, flat = true)
    revolve([profile(1.0, 1.54, "#ffe4a0"), profile(1.16, 1.6, "#fff2bd"), profile(1.02, 1.68, "#c99a45")], 96)
}
```

## 7. menger lesson

<!-- field-box id="menger_lesson" index="6" -->

```edn [field-box id="menger_lesson" role="properties"]
{"id" "menger_lesson" "index" 6 "name" "menger lesson" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "menger lesson" "_tombstone" false "frame" #afield/rect [80.0 2640.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "menger_lesson_notes_.md" "lines" 1 "characters" 151 "sha256" "962cc51bbeac5039fc5ec668367fd97463ec47a1d877ba9c587be0c9e1b68efb"} {"name" "code" "language" "kotlin" "path" "menger_lesson_code_.kt" "lines" 20 "characters" 713 "sha256" "fd633b6ee9bf9a3a1e55661787f8f233a9ab9063fff7e7c32cd0e04a5229b6a5"}]}
```

```markdown [field-property box="menger_lesson" name="notes" path="menger_lesson_notes_.md"]
**menger lesson**: a recursive sponge made only of `box` calls, coloured by position. `sponge(…, 2)` is 400 boxes; depth 3 is 8000, which still draws.
```

```kotlin [field-property box="menger_lesson" name="code" path="menger_lesson_code_.kt"]
geometry {
    view(background = "#070707", ground = true, grid = true)
    mat(color = "#ffffff", roughness = 0.7, metalness = 0.02, flat = true)

    fun sponge(center: Vec3, size: Double, depth: Int) {
        if (depth == 0) {
            color(hsl(180 + center.x * 28 + center.y * 42 - center.z * 18, 0.62, 0.42 + center.y * 0.08))
            box(center, size * 0.96)
            return
        }
        val step = size / 3
        for (x in -1..1) for (y in -1..1) for (z in -1..1) {
            val holes = [x, y, z].count { it == 0 }
            if (holes >= 2) continue
            sponge(center + vec(x * step, y * step, z * step), step, depth - 1)
        }
    }

    sponge(vec(0, 0, 0), 3.1, 2)
}
```

## 8. breathing sheet — launch me

<!-- field-box id="breathing_sheet" index="7" -->

### Narrative

From `code`

animateGeometry runs the recipe 30 times a second with t (seconds) moving.
Launch the box (alt+up) to play it; stop it with alt+down.

```edn [field-box id="breathing_sheet" role="properties"]
{"id" "breathing_sheet" "index" 7 "name" "breathing sheet — launch me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "breathing sheet — launch me" "_tombstone" false "frame" #afield/rect [80.0 1360.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "breathing_sheet_notes_.md" "lines" 1 "characters" 284 "sha256" "d5ca2a5324ef72a9820892024e159a302b668c5b9820422a1584e459b2e7c6b4"} {"name" "code" "language" "kotlin" "path" "breathing_sheet_code_.kt" "lines" 18 "characters" 707 "sha256" "aa93c5991679b3525b5da06a55cad0b48cd881f0735bb7b60f18bf234b4efa8f"}]}
```

```markdown [field-property box="breathing_sheet" name="notes" path="breathing_sheet_notes_.md"]
**breathing sheet** (animated): the same 64 × 28 grid every frame, with `t` moving the waves. Launch the box (alt+up) to play it and stop it with alt+down; running it draws the first frame. Because the topology never changes, `writeGlbAnimation` can save it as a vertex-animated GLB.
```

```kotlin [field-property box="breathing_sheet" name="code" path="breathing_sheet_code_.kt"]
// animateGeometry runs the recipe 30 times a second with t (seconds) moving.
// Launch the box (alt+up) to play it; stop it with alt+down.
animateGeometry {
    view(background = "#05080c", ground = true, grid = false)

    mesh("breathing sheet")
    mat(color = "#ffffff", roughness = 0.74, metalness = 0.05, flat = true)

    surface(64, 28) { u, v ->
        val x = (u - 0.5) * 6.8
        val z = (v - 0.5) * 2.8
        val phase = t * 2.2
        val wave = sin(x * 2.8 + phase) * cos(z * 4.2 - phase * 0.7)
        val curl = sin((u + v) * TAU * 2.0 + phase * 0.55)
        val y = wave * 0.34 + curl * 0.12
        vertex(x, y, z, hsl(188 + wave * 44 + curl * 18, 0.82, 0.48 + y * 0.12))
    }
}
```

## 9. spiral fronds

<!-- field-box id="spiral_fronds" index="8" -->

```edn [field-box id="spiral_fronds" role="properties"]
{"id" "spiral_fronds" "index" 8 "name" "spiral fronds" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "spiral fronds" "_tombstone" false "frame" #afield/rect [900.0 2000.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "spiral_fronds_notes_.md" "lines" 1 "characters" 200 "sha256" "544c63b65b26de683a83d2151ad1fe1c604e54ab6a4ce16425522c1873c7f37e"} {"name" "code" "language" "kotlin" "path" "spiral_fronds_code_.kt" "lines" 35 "characters" 1489 "sha256" "0391cce7284589099c27f9499912b0f0c057d12d3148dacfa3b64f5d0617b8f0"}]}
```

```markdown [field-property box="spiral_fronds" name="notes" path="spiral_fronds_notes_.md"]
**spiral fronds**: 190 leaves, one `quad` each, set round a spiral by the golden angle (phyllotaxis). The leaves shrink and lighten as they climb. Change `golden` to see why that angle packs so well.
```

```kotlin [field-property box="spiral_fronds" name="code" path="spiral_fronds_code_.kt"]
geometry {
    seed(91)
    view(background = "#050807", ground = true, grid = false)

    mesh("spiral fronds")
    mat(color = "#ffffff", roughness = 0.82, metalness = 0.02, flat = true, side = GeometrySide.DOUBLE)

    // Phyllotaxis: each leaf turns the golden angle from the last.
    val golden = PI * (3 - sqrt(5.0))
    for (i in 0 until 190) {
        val f = i / 189.0
        val angle = i * golden + f * 0.12
        val radius = 0.18 + sqrt(f) * 2.4
        val lift = -0.95 + f * 2.0 + sin(f * 1.4 + i * 0.11) * 0.035
        val cx = cos(angle) * radius
        val cz = sin(angle) * radius
        val tangent = angle + PI * 0.5
        val width = 0.07 + (1 - f) * 0.18
        val length = 0.28 + (1 - f) * 0.42
        val curl = sin(f + i * 0.17) * 0.12

        val root = vec(cx - cos(angle) * length * 0.35, lift, cz - sin(angle) * length * 0.35)
        val left = vec(cx + cos(tangent) * width, lift + curl, cz + sin(tangent) * width)
        val tip = vec(cx + cos(angle) * length, lift + 0.08 + curl, cz + sin(angle) * length)
        val right = vec(cx - cos(tangent) * width, lift - curl * 0.5, cz - sin(tangent) * width)

        val hue = 112 + f * 84 + sin(i.toDouble()) * 8
        quad(
            vertex(root, hsl(hue - 12, 0.66, 0.24 + f * 0.12)),
            vertex(left, hsl(hue, 0.72, 0.34 + f * 0.18)),
            vertex(tip, hsl(hue + 20, 0.78, 0.48 + f * 0.18)),
            vertex(right, hsl(hue + 8, 0.72, 0.32 + f * 0.16)),
        )
    }
}
```

## 10. crystal bloom

<!-- field-box id="crystal_bloom" index="9" -->

```edn [field-box id="crystal_bloom" role="properties"]
{"id" "crystal_bloom" "index" 9 "name" "crystal bloom" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "crystal bloom" "_tombstone" false "frame" #afield/rect [900.0 80.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "crystal_bloom_notes_.md" "lines" 1 "characters" 198 "sha256" "eb14a30910bcbdbb39b3af80c4bfaedb7a8942393edf336a03ba8a5eb3135092"} {"name" "code" "language" "kotlin" "path" "crystal_bloom_code_.kt" "lines" 34 "characters" 1212 "sha256" "37ce9971b4dca8e47ef8020ff420f69ea7c022c968ee53c17f4cb8e7d5744d25"}]}
```

```markdown [field-property box="crystal_bloom" name="notes" path="crystal_bloom_notes_.md"]
**crystal bloom**: hundreds of single triangles grown from nine seeded polar rings. Each one is a `tri` with three coloured corners. `seed(18)` fixes the random stream; change it for another bloom.
```

```kotlin [field-property box="crystal_bloom" name="code" path="crystal_bloom_code_.kt"]
geometry {
    seed(18)
    view(background = "#070708", ground = true, grid = false)

    mesh("crystal bloom")
    mat(color = "#ffffff", roughness = 0.42, metalness = 0.06, flat = true)

    for (ring in 0 until 9) {
        val count = 7 + ring * 4
        val radius = 0.35 + ring * 0.28
        val lift = -0.7 + ring * 0.12

        for (i in 0 until count) {
            val a0 = i.toDouble() / count * TAU + ring * 0.19
            val a1 = (i + 0.82) / count * TAU + ring * 0.19
            val mid = (a0 + a1) * 0.5
            val height = 0.35 + rand(0.05, 0.72) + ring * 0.045
            val lean = rand(-0.12, 0.12)
            val jitter = rand(-0.04, 0.04)
            val hue = 182 + ring * 12 + rand(-10.0, 16.0)

            tri(
                vertex(cos(a0) * radius, lift, sin(a0) * radius, hsl(hue - 14, 0.9, 0.38)),
                vertex(cos(a1) * radius, lift + jitter, sin(a1) * radius, hsl(hue + 10, 0.95, 0.52)),
                vertex(
                    cos(mid) * (radius + 0.12 + lean),
                    lift + height,
                    sin(mid) * (radius + 0.12 - lean),
                    hsl(hue + 26, 0.95, 0.68),
                ),
            )
        }
    }
}
```

## 11. ripple terrain — start here

<!-- field-box id="ripple_terrain" index="10" -->

### Narrative

From `notes`

Geometry: models as recipes of triangles

From `code`

geometry { } is a recipe of triangles. Running the box runs the recipe and draws it.

mat(...)             the material for triangles that follow
color(...)           the colour for vertices that follow
tri(a, b, c)         one triangle
quad(a, b, c, d)     two triangles
surface(...)         a grid of triangles
revolve, tube, box   more triangles
push() / pop() with translate, rotateX/Y/Z, scale

geometryAgent() teaches this document's AI4 chat the language and lets it look at every view here:
open the AI panel and ask for "a coral reef of tubes", or "make the terrain stormier".

```edn [field-box id="ripple_terrain" role="properties"]
{"id" "ripple_terrain" "index" 10 "name" "ripple terrain — start here" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "ripple terrain — start here" "_tombstone" false "frame" #afield/rect [80.0 80.0 300.0 100.0]} "blockProperties" [{"name" "notes" "language" "markdown" "path" "ripple_terrain_notes_.md" "lines" 63 "characters" 4246 "sha256" "d9705d3e2a7b17cdb74e55fcc8616bd5f551dafb11271f4495ca65f4b65549b8"} {"name" "code" "language" "kotlin" "path" "ripple_terrain_code_.kt" "lines" 32 "characters" 1135 "sha256" "4092282d4cab28c451f21c81595eae36ba11969ad4728919c37f2989837ec65d"}]}
```

```markdown [field-property box="ripple_terrain" name="notes" path="ripple_terrain_notes_.md"]
# Geometry: models as recipes of triangles

This document is Field's version of **Geometry**, the madp toolbox page `3d.html`. There, a code
editor sits beside a three.js view. You write one `build()` function, and every edit rebuilds the
model and draws it on a dark studio ground with soft shadows. Eleven presets show what the language
can do, from a single triangle to a recursive tree.

## What it teaches

- **Everything is triangles.** A surface, a lathe-turned vessel, a tube along a knot and a box are
  all loops that emit triangles. `tri` is the one real primitive; the rest are recipes built on it.
- **Immediate mode with state.** There is a current colour, a current material and a current
  transform. Each call uses whatever is current, so `color(...)` before `box(...)` colours that box,
  and `push()` / `pop()` save and restore everything.
- **Colour per vertex.** Each corner can carry its own colour, and three.js blends it across the
  face. With flat shading, that blend and the facets do the drawing.
- **A fixed topology animates.** Re-run the recipe every frame with `t` moving, and as long as it
  emits the same triangles, only the vertices move. That is what makes a vertex-animated GLB.

## How to play

1. **Run this box** (alt+0). The terrain appears in its output. Drag to orbit, scroll to zoom.
2. **Change a number and run it again**: `3.6` in `sin(r * 3.6)` sets how tight the ripples are,
   `188` in `hsl(188 + …)` the hue. The view keeps your orbit.
3. **Run the other boxes**: each is one of the toolbox's presets, with a short note of its own.
   *breathing sheet* and *signal tear* animate: **launch** them (alt+up) to play, and stop them with
   alt+down. Running them (alt+0) draws one still frame.
4. **Export GLB**, in the view's toolbar, saves the model. If gltfpack is installed
   (`./field.sh --setup`, or `./gradlew prepareGltfpack`), the file is compressed. From code,
   `geometryScene { … }.writeGlb(file)` saves a still and `writeGlbAnimation(file, frames = 90) { … }`
   saves an animation as morph targets.
5. **Ask the AI.** `geometryAgent()` at the top of this box teaches the document's AI4 chat the
   language. It also gives the chat `geometry.look`, which returns a picture of any view here as it is
   on screen. Open the chat (ctrl/command-4) and ask for "a coral reef of tubes", or "make the terrain
   stormier". The chat edits a box, runs it, looks, and tries again.

## From `3d.html` to Field

| In the toolbox | Here |
| --- | --- |
| `function build() { … }` | `geometry { … }`, the box's code |
| **Animate** | `animateGeometry { … }`, then launch the box (alt+up) |
| `mat({ roughness: 0.8, flat: true })` | `mat(roughness = 0.8, flat = true)` |
| `[x, y, z]` | `vec(x, y, z)` |
| `{ pos: [x, y, z], color: hsl(…) }` | `vertex(x, y, z, hsl(…))` |
| `surface(c, r, (u, v) => ({ pos, color }))` | `surface(c, r) { u, v -> vertex(…) }` |
| `revolve([{ r, y, color }, …], 96)` | `revolve(listOf(profile(r, y, color), …), 96)` |
| `tube(n, sides, t => ({ pos, radius, color }))` | `tube(n, sides) { s -> section(pos, radius, color) }` |
| `hsl`, `seed`, `rand`, `noise` | the same names and the same numbers: `seed(18)` grows the same crystal bloom |
| `view({ background, ground, grid })` | `view(background = …, ground = …, grid = …)` |
| **Export GLB** (+ gltfpack) | the view's **Export GLB**, `writeGlb`, `writeGlbAnimation` |
| **Copy Guide**, AI completion, agent chat | `geometryAgent()` and AI4; Field's own completion |

## What is different

- **Kotlin, not JavaScript.** Numbers are `Double`, so a loop index sometimes needs `i.toDouble()`,
  and a local helper is a local `fun`, as in *fungal tree* and *menger lesson*.
- **One box per preset** instead of a menu, so you can run them side by side and keep your edits.
- **Lambda names.** A tube's parameter is `s` here, because `t` is already the animation time.
- **The camera** frames each model a little differently from the toolbox's fit.
- **Saving** is Field's: documents and their history, not the page's local saves.
- **The look** is the same rig (ACES tone mapping, a shadow-casting key, hemisphere and rim lights,
  ambient occlusion), provided by `three { studio() }`, so any `three` scene can use it.
```

```kotlin [field-property box="ripple_terrain" name="code" path="ripple_terrain_code_.kt"]
/*
geometry { } is a recipe of triangles. Running the box runs the recipe and draws it.

  mat(...)             the material for triangles that follow
  color(...)           the colour for vertices that follow
  tri(a, b, c)         one triangle
  quad(a, b, c, d)     two triangles
  surface(...)         a grid of triangles
  revolve, tube, box   more triangles
  push() / pop() with translate, rotateX/Y/Z, scale

geometryAgent() teaches this document's AI4 chat the language and lets it look at every view here:
open the AI panel and ask for "a coral reef of tubes", or "make the terrain stormier".
*/
geometryAgent()

geometry {
    view(background = "#05080a", ground = true, grid = true)

    mesh("ripple terrain")
    mat(color = "#ffffff", roughness = 0.86, metalness = 0.03, flat = true)

    surface(78, 78) { u, v ->
        val x = (u - 0.5) * 7.2
        val z = (v - 0.5) * 7.2
        val r = sqrt(x * x + z * z)
        val waves = sin(r * 3.6) * 0.22
        val folds = noise(x * 0.75, z * 0.75) * 0.38
        val y = waves + folds
        vertex(x, y, z, hsl(188 + y * 56 + r * 5, 0.74, 0.42 + y * 0.08))
    }
}
```

