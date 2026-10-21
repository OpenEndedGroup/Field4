# Field Document: madp_geometry

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 11 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. fungal tree

<!-- field-box id="fungal_tree" index="0" -->

```edn [field-box id="fungal_tree" role="properties"]
{"id" "fungal_tree" "index" 0 "name" "fungal tree" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "fungal tree" "_tombstone" false "frame" #afield/rect [80.0 2000.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fungal_tree_code_.kt" "lines" 52 "characters" 2031 "sha256" "faa34cc2518b330d1ea725f06b9acb675d8b0d1afb47086366bcb5c9e03a1e1b"}]}
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
            revolve(listOf(
                profile(0.04, 0.0, hsl(36, 0.9, 0.72)),
                profile(0.58, 0.08, hsl(32, 0.72, 0.55)),
                profile(1.0, 0.0, hsl(24, 0.75, 0.44)),
                profile(0.42, -0.16, hsl(42, 0.58, 0.62)),
            ), 18)
            pop()
            return
        }

        val branches = if (depth > 2) 3 else 2
        for (i in 0 until branches) {
            val turn = i.toDouble() / branches * TAU + rand(-0.5, 0.5)
            val lift = 0.48 + rand(0.0, 0.32)
            val next = vec(
                dir.x * 0.62 + cos(turn) * (0.45 + rand(0.0, 0.22)),
                dir.y * 0.52 + lift,
                dir.z * 0.62 + sin(turn) * (0.45 + rand(0.0, 0.22)),
            ).normalize()
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

Another animation: the same grid every frame, torn along random rows as t moves. Launch it.

```edn [field-box id="signal_tear" role="properties"]
{"id" "signal_tear" "index" 1 "name" "signal tear — launch me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "signal tear — launch me" "_tombstone" false "frame" #afield/rect [900.0 1360.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "signal_tear_code_.kt" "lines" 19 "characters" 837 "sha256" "2e4faf06b5a3dde08b6c29f9accace98a4799f9e520214adcb2178eb2f90feae"}]}
```

```kotlin [field-property box="signal_tear" name="code" path="signal_tear_code_.kt"]
// Another animation: the same grid every frame, torn along random rows as t moves. Launch it.
animateGeometry {
    view(background = "#050608", ground = true, grid = false)

    mesh("signal tear")
    mat(color = "#ffffff", roughness = 0.52, metalness = 0.18, emissive = "#07121c", emissiveIntensity = 0.55, flat = true)

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
{"id" "knot_tube" "index" 2 "name" "knot tube" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "knot tube" "_tombstone" false "frame" #afield/rect [900.0 720.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "knot_tube_code_.kt" "lines" 16 "characters" 483 "sha256" "9afc5cb37ce49d1cee21e673bf4bfb8459fcb16f9a02a3ee877ed15bbc8bbb89"}]}
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
{"id" "city_blocks" "index" 3 "name" "city blocks" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "city blocks" "_tombstone" false "frame" #afield/rect [900.0 2640.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "city_blocks_code_.kt" "lines" 23 "characters" 961 "sha256" "6cfcf2e660e3b3c9d1f2cb9c3fb4959ea29daeca230372d33a3a08fd0655e593"}]}
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
{"id" "triangle_lesson" "index" 4 "name" "triangle lesson" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "triangle lesson" "_tombstone" false "frame" #afield/rect [80.0 3280.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "triangle_lesson_code_.kt" "lines" 27 "characters" 1100 "sha256" "ad5a2f862b929b8da9fed9e8c9617a1c35a781877ce6b85733b9c429232b834d"}]}
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
{"id" "lathe_vessel" "index" 5 "name" "lathe vessel" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "lathe vessel" "_tombstone" false "frame" #afield/rect [80.0 720.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "lathe_vessel_code_.kt" "lines" 19 "characters" 728 "sha256" "13f4da8579d02e37d8813b4be47b774c8180a4c5f7af8069dfbdecf8dd18e50a"}]}
```

```kotlin [field-property box="lathe_vessel" name="code" path="lathe_vessel_code_.kt"]
geometry {
    view(background = "#060807", ground = true, grid = true)

    val body = List(36) { i ->
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
    revolve(listOf(profile(1.0, 1.54, "#ffe4a0"), profile(1.16, 1.6, "#fff2bd"), profile(1.02, 1.68, "#c99a45")), 96)
}
```

## 7. menger lesson

<!-- field-box id="menger_lesson" index="6" -->

```edn [field-box id="menger_lesson" role="properties"]
{"id" "menger_lesson" "index" 6 "name" "menger lesson" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "menger lesson" "_tombstone" false "frame" #afield/rect [80.0 2640.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "menger_lesson_code_.kt" "lines" 20 "characters" 719 "sha256" "79a28735220083d152fc8a5e9f89bd6baa120491a0547c6550f41f59f6f1e68c"}]}
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
            val holes = listOf(x, y, z).count { it == 0 }
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
Launch the box to play it; stop the box to stop it.

```edn [field-box id="breathing_sheet" role="properties"]
{"id" "breathing_sheet" "index" 7 "name" "breathing sheet — launch me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "breathing sheet — launch me" "_tombstone" false "frame" #afield/rect [80.0 1360.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "breathing_sheet_code_.kt" "lines" 18 "characters" 700 "sha256" "ef507bf906f30f1640d7c8db0a56aa92942d0aae19d2c36ed0bce85f5f062a7b"}]}
```

```kotlin [field-property box="breathing_sheet" name="code" path="breathing_sheet_code_.kt"]
// animateGeometry runs the recipe 30 times a second with t (seconds) moving.
// Launch the box to play it; stop the box to stop it.
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
{"id" "spiral_fronds" "index" 8 "name" "spiral fronds" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "spiral fronds" "_tombstone" false "frame" #afield/rect [900.0 2000.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "spiral_fronds_code_.kt" "lines" 35 "characters" 1489 "sha256" "0391cce7284589099c27f9499912b0f0c057d12d3148dacfa3b64f5d0617b8f0"}]}
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
{"id" "crystal_bloom" "index" 9 "name" "crystal bloom" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "crystal bloom" "_tombstone" false "frame" #afield/rect [900.0 80.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "crystal_bloom_code_.kt" "lines" 30 "characters" 1133 "sha256" "c5c483ac8d9a68d04fd86b93738b59f75e9c25d7986b4d2360aed3242b66e41e"}]}
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
                vertex(cos(mid) * (radius + 0.12 + lean), lift + height, sin(mid) * (radius + 0.12 - lean),
                    hsl(hue + 26, 0.95, 0.68)),
            )
        }
    }
}
```

## 11. ripple terrain — start here

<!-- field-box id="ripple_terrain" index="10" -->

### Narrative

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
{"id" "ripple_terrain" "index" 10 "name" "ripple terrain — start here" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "ripple terrain — start here" "_tombstone" false "frame" #afield/rect [80.0 80.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ripple_terrain_code_.kt" "lines" 30 "characters" 1163 "sha256" "118c6ed47796f44a1b7b794dc88819af77dd56ff3b768b8b7d320e6e5ee99575"}]}
```

```kotlin [field-property box="ripple_terrain" name="code" path="ripple_terrain_code_.kt"]
// geometry { } is a recipe of triangles. Running the box runs the recipe and draws it.
//
//   mat(...)             the material for triangles that follow
//   color(...)           the colour for vertices that follow
//   tri(a, b, c)         one triangle
//   quad(a, b, c, d)     two triangles
//   surface(...)         a grid of triangles
//   revolve, tube, box   more triangles
//   push() / pop() with translate, rotateX/Y/Z, scale
//
// geometryAgent() teaches this document's AI4 chat the language and lets it look at every view here:
// open the AI panel and ask for "a coral reef of tubes", or "make the terrain stormier".
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

