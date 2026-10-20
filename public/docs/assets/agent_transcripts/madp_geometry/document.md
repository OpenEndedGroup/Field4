# Field Document: madp_geometry

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 7 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. breathing sheet — launch me

<!-- field-box id="breathing_sheet" index="0" -->

### Narrative

From `code`

animateGeometry runs the recipe 30 times a second with t (seconds) moving.
Launch the box to play it; stop the box to stop it.

```edn [field-box id="breathing_sheet" role="properties"]
{"id" "breathing_sheet" "index" 0 "name" "breathing sheet — launch me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "breathing sheet — launch me" "_tombstone" false "frame" #afield/rect [80.0 1360.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "breathing_sheet_code_.kt" "lines" 18 "characters" 700 "sha256" "ef507bf906f30f1640d7c8db0a56aa92942d0aae19d2c36ed0bce85f5f062a7b"}]}
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

## 2. fungal tree

<!-- field-box id="fungal_tree" index="1" -->

```edn [field-box id="fungal_tree" role="properties"]
{"id" "fungal_tree" "index" 1 "name" "fungal tree" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "fungal tree" "_tombstone" false "frame" #afield/rect [900.0 1360.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fungal_tree_code_.kt" "lines" 52 "characters" 2031 "sha256" "faa34cc2518b330d1ea725f06b9acb675d8b0d1afb47086366bcb5c9e03a1e1b"}]}
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

## 4. crystal bloom

<!-- field-box id="crystal_bloom" index="3" -->

```edn [field-box id="crystal_bloom" role="properties"]
{"id" "crystal_bloom" "index" 3 "name" "crystal bloom" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "crystal bloom" "_tombstone" false "frame" #afield/rect [900.0 80.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "crystal_bloom_code_.kt" "lines" 30 "characters" 1133 "sha256" "c5c483ac8d9a68d04fd86b93738b59f75e9c25d7986b4d2360aed3242b66e41e"}]}
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

## 5. lathe vessel

<!-- field-box id="lathe_vessel" index="4" -->

```edn [field-box id="lathe_vessel" role="properties"]
{"id" "lathe_vessel" "index" 4 "name" "lathe vessel" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "lathe vessel" "_tombstone" false "frame" #afield/rect [80.0 720.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "lathe_vessel_code_.kt" "lines" 19 "characters" 728 "sha256" "13f4da8579d02e37d8813b4be47b774c8180a4c5f7af8069dfbdecf8dd18e50a"}]}
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

## 6. ripple terrain — start here

<!-- field-box id="ripple_terrain" index="5" -->

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

```edn [field-box id="ripple_terrain" role="properties"]
{"id" "ripple_terrain" "index" 5 "name" "ripple terrain — start here" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "ripple terrain — start here" "_tombstone" false "frame" #afield/rect [80.0 80.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ripple_terrain_code_.kt" "lines" 25 "characters" 951 "sha256" "217b975e39068e709ac6159f693602a56b26e35c3c21b3f209f854f434998b47"}]}
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

## 7. menger lesson

<!-- field-box id="menger_lesson" index="6" -->

```edn [field-box id="menger_lesson" role="properties"]
{"id" "menger_lesson" "index" 6 "name" "menger lesson" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "menger lesson" "_tombstone" false "frame" #afield/rect [80.0 2000.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "menger_lesson_code_.kt" "lines" 20 "characters" 719 "sha256" "79a28735220083d152fc8a5e9f89bd6baa120491a0547c6550f41f59f6f1e68c"}]}
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

