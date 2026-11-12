# Field Document: explainer_lights

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 19 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. distance_rays

<!-- field-box id="el_distance_rays" index="0" -->

### Narrative

From `code`

A point light sends 600 rays into a cone. Move the square patch away and count how many reach it.

```edn [field-box id="el_distance_rays" role="properties"]
{"id" "el_distance_rays" "index" 0 "name" "distance_rays" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "distance_rays" "_tombstone" false "frame" #afield/rect [480.0 210.0 160.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "el_distance_rays_code_.kt" "lines" 45 "characters" 1643 "sha256" "fedaba5b566237318e4ecb1e5c59a0f75a94dc6676d2b2d87bf4d13e98c969f6"}]}
```

```kotlin [field-property box="el_distance_rays" name="code" path="el_distance_rays_code_.kt"]
// A point light sends 600 rays into a cone. Move the square patch away and count how many reach it.
val count = 600
val golden = PI * (3 - sqrt(5.0))
// Directions spread evenly over a cone 30° around +x.
val directions = count { i ->
    val c = 1 - (1 - cos(PI / 6)) * (i + 0.5) / count
    val s = sqrt(1 - c * c)
    vec(c, s * cos(golden * i), s * sin(golden * i))
}

fun draw(distance: Double) {
    var reached = 0
    val rays = directions.map { d ->
        // Where the ray crosses the patch's plane, x = distance.
        val t = distance / d.x
        val hit = abs(t * d.y) <= 0.25 && abs(t * d.z) <= 0.25
        if (hit) reached++
        val end = if (hit) t else 4.5 / d.x
        fline {
            moveTo(0.0, 0.0, 0.0)
            lineTo(end * d.x, end * d.y, end * d.z)
            color = if (hit) vec(0.4, 0.9, 1.0, 1.0) else vec(1.0, 1.0, 1.0, 0.12)
        }
    }
    val patch =
        planeMesh(1, 1, 0.5, 0.5)
            .rotated(Quat().rotateY((-PI / 2).toFloat()))
            .translated(distance, 0.0, 0.0)
            .withColor(1.0, 0.6, 0.2)
    three(channel = "scene", width = 640, height = 380) {
        lines(rays)
        mesh(patch, unlit = true, wireframe = false, cullBackFaces = false)
        mesh(icosphereMesh(2, 0.05).withColor(1.0, 1.0, 1.0), unlit = true, wireframe = false)
        camera(yaw = 30.0, pitch = 25.0, distance = 2.2)
        background("#000000")
        hud(false)
        helpers(false)
    }
    markdown("readout", "${distance.f1} m: $reached of $count rays")
}

draw(1.0)
controlGroup {
    slider("distance", 1.0, 4.0, step = 0.1, initialValue = 1.0) { draw(it) }
}
```

## 2. hemisphere

<!-- field-box id="el_hemisphere" index="1" -->

```edn [field-box id="el_hemisphere" role="properties"]
{"id" "el_hemisphere" "index" 1 "name" "hemisphere" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "hemisphere" "_tombstone" false "frame" #afield/rect [480.0 280.0 160.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "el_hemisphere_code_.kt" "lines" 57 "characters" 2645 "sha256" "70516a3f0d62dcf5608b22ca3bf61c0c516bbec392f22e27c6c1bc617eff44c7"}]}
```

```kotlin [field-property box="el_hemisphere" name="code" path="el_hemisphere_code_.kt"]
import afield.graphics.lighting.*

/*
What one point on the floor sees: a hemisphere of directions. The lamp, pushed onto that
hemisphere (orange) and then flattened straight down onto the disc beneath (cyan). The share of
the disc the flattened lamp covers is the share of the lamp's radiance that reaches the point.
*/
val dome =
    icosphereMesh(4, 1.0).let { ball ->
        ball.filterTriangles { t -> ball.triangle(t).let { (a, b, c) -> a.y + b.y + c.y > 0 } }
    }
val floor = floorMesh(2.6, 2.6, divisions = 48)
val rim = fline {
    moveTo(1.0, 0.0, 0.0)
    for (k in 1..96) lineTo(cos(2 * PI * k / 96), 0.0, sin(2 * PI * k / 96))
    color = vec(1.0, 1.0, 1.0, 0.4)
}
val side = 1.0

fun draw(across: Double) {
    val lamp = RectLight(vec(across, 1.2, 0.0), width = side, height = side, power = 20.0)
    val outline = planeMesh(12, 12, side, side).transformed { p -> vec(p.x + across, 1.2, p.y) }
    val onDome = outline.transformed { p -> p.normalize().mul(1.004f) }
    val flat = onDome.transformed { p -> vec(p.x, 0.004, p.z) }
    var covered = 0.0
    for (t in 0 until flat.triangleCount) covered += flat.triangleArea(t)
    // Irradiance two ways: summed over the lamp by afield.graphics.lighting, and the lamp's
    // radiance times the flattened area it covers.
    val radiance = lamp.power / (PI * side * side)
    val e = irradianceAt(vec(0.0, 0.0, 0.0), vec(0.0, 1.0, 0.0), lamp).x
    val sight =
        listOf(-0.5 to -0.5, 0.5 to -0.5, 0.5 to 0.5, -0.5 to 0.5).map { (x, z) ->
            fline {
                moveTo(0.0, 0.0, 0.0)
                lineTo(across + x, 1.2, z)
                color = vec(1.0, 1.0, 1.0, 0.3)
            }
        }
    three(channel = "scene", width = 640, height = 420) {
        mesh(floor.lit([lamp], occluders = null, exposure = 0.35), unlit = true, wireframe = false)
        mesh(dome.withColor(0.4, 0.45, 0.5), opacity = 0.12, wireframe = false, cullBackFaces = false)
        lines(sight + rim)
        mesh(lamp.mesh(), unlit = true, wireframe = false, cullBackFaces = false)
        mesh(onDome.withColor(1.0, 0.6, 0.2), unlit = true, wireframe = false, cullBackFaces = false)
        mesh(flat.withColor(0.4, 0.9, 1.0), unlit = true, wireframe = false, cullBackFaces = false)
        fitTo(vec(-1.1, 0.0, -1.1), vec(1.1, 1.25, 1.1))
        camera(yaw = 20.0, pitch = 30.0)
        background("#000000")
        hud(false)
    }
    markdown("readout", "${(100 * covered / PI).f1} % of the disc · ${(radiance * covered).f2} W/m² (summed: ${e.f2})")
}

draw(0.0)
controlGroup {
    slider("lamp position", -2.0, 2.0, step = 0.05, initialValue = 0.0) { draw(it) }
}
```

## 3. perceived

<!-- field-box id="el_perceived" index="2" -->

### Narrative

From `code`

The light a patch gives off, against what a screen can show of it. The swatch is the patch;
the curve is the brightness the screen sends, which runs out at its brightest white.

```edn [field-box id="el_perceived" role="properties"]
{"id" "el_perceived" "index" 2 "name" "perceived" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "perceived" "_tombstone" false "frame" #afield/rect [660.0 140.0 160.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "el_perceived_code_.kt" "lines" 41 "characters" 1453 "sha256" "542886b05bd1607f5ef74b8e31d9db3e85da768de04b6cc92ec5e169b2ca3f3c"}]}
```

```kotlin [field-property box="el_perceived" name="code" path="el_perceived_code_.kt"]
// The light a patch gives off, against what a screen can show of it. The swatch is the patch;
// the curve is the brightness the screen sends, which runs out at its brightest white.
fun draw(watts: Double) {
    // A screen channel holds 0..1 of linear light, here reached at 50 W, and encodes it as sRGB.
    fun shown(w: Double) = min(1.0, w / 50).pow(1 / 2.2)
    val curve = fline {
        moveTo(40.0, 260.0)
        for (w in 1..100) lineTo(40.0 + w * 3.6, 260.0 - 220 * shown(w.toDouble()))
        thickness = 2.0
        color = vec(0.4, 0.9, 1.0, 1.0)
    }
    val axes = fline {
        moveTo(40.0, 30.0)
        lineTo(40.0, 260.0)
        lineTo(400.0, 260.0)
        color = vec(1.0, 1.0, 1.0, 0.35)
    }
    val here = fline {
        moveTo(40.0 + watts * 3.6, 260.0)
        lineTo(40.0 + watts * 3.6, 260.0 - 220 * shown(watts))
        thickness = 2.0
        color = vec(1.0, 0.6, 0.2, 1.0)
    }
    val v = shown(watts)
    val swatch = fline {
        moveTo(450.0, 60.0)
        lineTo(610.0, 60.0)
        lineTo(610.0, 220.0)
        lineTo(450.0, 220.0)
        lineTo(450.0, 60.0)
        filled = true
        color = vec(v, v, v, 1.0)
    }
    lines(axes, curve, here, swatch, channel = "curve", w = 640.0, h = 280.0)
    markdown("readout", "${watts.roundToInt()} W → ${(v * 100).roundToInt()} % of white")
}

draw(10.0)
controlGroup {
    slider("power", 0.0, 100.0, step = 1.0, initialValue = 10.0) { draw(it) }
}
```

## 4. deck

<!-- field-box id="el_deck" index="3" -->

```edn [field-box id="el_deck" role="properties"]
{"id" "el_deck" "index" 3 "name" "deck" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "deck" "_tombstone" false "frame" #afield/rect [60.0 140.0 160.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "el_deck_code_.kt" "lines" 397 "characters" 19146 "sha256" "52929b33c1be63c61b4effd3b674edfb125c781706abd9301e2bae8be6961806"}]}
```

```kotlin [field-property box="el_deck" name="code" path="el_deck_code_.kt"]
import kotlinx.html.*

// The talk. Every figure is a box beside this one, named as in figureBeside(...).
presentation(title = "Lights and Shadows") {
        step("Lights and Shadows", id = "opening") {
            audience {
                figureBeside("opening_scene") {
                    +"""
                    # Lights and Shadows

                    Everything we see is light that left a source, struck something, and
                    reached an eye. This talk builds up how much light lands on a surface,
                    and why one shadow is crisp while another is soft.

                    Slide the lamp across, or change its size. Drag the picture to look around.""".md
                    p(classes = "muted") {
                        +"After “Lights and Shadows” by Bartosz Ciechanowski, ciechanow.ski/lights-and-shadows. Words and figures made in Field."
                    }
                }
            }
            control("opening_scene", "position", 0.6)
            control("opening_scene", "size", 0.5)
            markdownNote(
                "A lamp, a floor and a block. Every colour on the floor is computed from the physics in this talk. [[next]]",
            )
        }

        section("Power") {
            step("Power", id = "power") {
                audience {
                    figureBeside("sphere_light") {
                        +"""
                        ## Power

                        A lamp turns electrical power into light, and we measure the light the same way:
                        in watts, the energy leaving the source each second.

                        Think of it as rays streaming outward. A stronger lamp sends more of them.""".md
                    }
                }
                control("sphere_light", "power", 10.0)
                cue("More power") { control("sphere_light", "power", 80.0, glideMs = 2000) }
                markdownNote("Next raises the lamp from 10 W to 80 W: eight times the rays. [[back]] [[next]]")
            }
            step("Brightness is not power", id = "perceived") {
                audience {
                    figureBeside("perceived") {
                        +"""
                        ## Brightness is not power

                        Twice the power does not look twice as bright. Our eyes compress what they
                        receive, and a screen can only show so much: once a channel is full, every
                        brighter value looks the same white.""".md
                    }
                }
                control("perceived", "power", 10.0)
                cue("Past white") { control("perceived", "power", 90.0, glideMs = 2000) }
                markdownNote(
                    "Next pushes the power to 90 W: past 50 W the screen has nothing brighter to show. [[back]] [[next]]",
                )
            }
            step("Surfaces pass light on", id = "matte") {
                audience {
                    figureBeside("opening_scene") {
                        +"""
                        ## Surfaces pass light on

                        Most things do not glow. We see them because they send back part of the light
                        that reaches them. A matte surface scatters what it returns evenly in every
                        direction, so it looks the same from wherever you stand.""".md
                    }
                }
                control("opening_scene", "position", 0.6)
                control("opening_scene", "size", 0.5)
                markdownNote(
                    "The opening scene again: drag it round; the floor looks the same from every side. [[back]] [[next]]",
                )
            }
        }

        section("Position") {
            step("Light at a slant", id = "slant") {
                audience {
                    figureBeside("light_angle") {
                        +"""
                        ## Light at a slant

                        Tilt a beam away from straight down and the same rays spread over more
                        floor. The light per square metre falls as the cosine of the angle from
                        the surface's normal: half at 60°, nothing at 90°.""".md
                    }
                }
                control("light_angle", "angle", 0.0)
                cue("Tilt to 60°") { control("light_angle", "angle", 60.0, glideMs = 1800) }
                markdownNote(
                    "Next tilts the rays to 60°: six of twelve land, and cos 60° is one half. [[back]] [[next]]",
                )
            }
            step("Shape from the cosine", id = "lit-sphere") {
                audience {
                    figureBeside("lit_sphere") {
                        +"""
                        ## Shape from the cosine

                        Every point on a ball faces the lamp at a different angle. The cosine
                        alone draws the shading that tells us the ball is round.""".md
                    }
                }
                markdownNote(
                    "Drag the light control round the ball; the dark side always faces away from it. [[back]] [[next]]",
                )
            }
            step("Farther away", id = "distance") {
                audience {
                    figureBeside("distance_rays") {
                        +"""
                        ## Farther away

                        Light leaving a point spreads over a sphere, and a sphere's area grows with
                        the square of its radius. Twice as far, a quarter of the light: the
                        inverse-square law.""".md
                    }
                }
                control("distance_rays", "distance", 1.0)
                cue("Twice as far") { control("distance_rays", "distance", 2.0, glideMs = 1800) }
                markdownNote(
                    "Next moves the patch from 1 m to 2 m: about a quarter as many rays reach it. [[back]] [[next]]",
                )
            }
            step("A light with a size", id = "rect-light") {
                audience {
                    figureBeside("opening_scene") {
                        +"""
                        ## A light with a size

                        A real lamp is not a point. Each small piece of its surface behaves like a
                        point light of its own, and the floor adds all of them up.""".md
                    }
                }
                control("opening_scene", "position", 0.6)
                control("opening_scene", "size", 0.1)
                cue("A wider lamp") { control("opening_scene", "size", 1.2, glideMs = 2000) }
                markdownNote(
                    "Next widens the lamp from 10 cm to 1.2 m at the same power: the pool spreads and the shadow softens. [[back]] [[next]]",
                )
            }
        }

        section("Angles") {
            step("How big things look", id = "angles") {
                audience {
                    figureBeside("car_angle") {
                        +"""
                        ## How big things look

                        How large something looks is the angle it takes up in front of you. Walk
                        away from a car and that angle shrinks, close to one over the distance.""".md
                    }
                }
                control("car_angle", "distance", 10.0)
                cue("Twice as far") { control("car_angle", "distance", 20.0, glideMs = 1800) }
                markdownNote(
                    "Next drives the car from 10 m to 20 m: the angle it spans halves, near enough. [[back]] [[next]]",
                )
            }
            step("Angles as lengths", id = "radians") {
                audience {
                    figureBeside("radians") {
                        +"""
                        ## Angles as lengths

                        Measure an angle by the arc it cuts from a circle: arc length over radius
                        is the angle in radians, and a whole turn is 2π. For a small angle the arc
                        is nearly straight, so size ≈ angle × distance.""".md
                    }
                }
                control("radians", "angle", 60.0)
                control("radians", "radius", 90.0)
                cue("A whole turn") { control("radians", "angle", 360.0, glideMs = 2500) }
                markdownNote("Next sweeps the angle to a whole turn: 6.28 radii of arc, 2π radians. [[back]] [[next]]")
            }
        }

        section("Solid angles") {
            step("Angles in three dimensions", id = "solid-angle") {
                audience {
                    figureBeside("projected_rect") {
                        +"""
                        ## Angles in three dimensions

                        Project a shape onto a sphere of radius one around your eye. The patch it
                        covers is its solid angle, in steradians; the whole sphere is 4π. Move a
                        panel twice as far and its solid angle drops to a quarter.""".md
                    }
                }
                control("projected_rect", "distance", 1.3)
                cue("Twice as far") { control("projected_rect", "distance", 2.6, glideMs = 1800) }
                markdownNote(
                    "Next moves the panel from 1.3 m to 2.6 m: a quarter of the solid angle, while solid angle × distance² stays near the panel's area. [[back]] [[next]]",
                )
            }
        }

        section("Surface vision") {
            step("What the floor sees", id = "hemisphere") {
                audience {
                    figureBeside("hemisphere") {
                        +"""
                        ## What the floor sees

                        A point on a floor looks out on a hemisphere of directions. Project the lamp
                        onto that hemisphere, then flatten it straight down onto the disc beneath:
                        the share of the disc it covers is the share of light the point gets.
                        The flattening is the cosine factor again.""".md
                    }
                }
                control("hemisphere", "lamp position", 0.0)
                cue("Lamp to one side") { control("hemisphere", "lamp position", 1.5, glideMs = 2000) }
                markdownNote(
                    "Next slides the lamp off to the side: its patch on the dome moves down and flattens to a smaller share of the disc. The readout checks the share against the lighting library. [[back]] [[next]]",
                )
            }
        }

        section("Radiance") {
            step("Brightness that does not fade", id = "radiance") {
                audience {
                    figureBeside("constant_radiance") {
                        +"""
                        ## Brightness that does not fade

                        Step back from a lamp and it looks just as bright, only smaller. Each cell in
                        the eye takes in a fixed cone of directions; farther away, that cone covers
                        more of the lamp, and the extra area makes up for the inverse-square law
                        exactly. What stays the same is called radiance.""".md
                    }
                }
                control("constant_radiance", "distance", 1.0)
                cue("Step back") { control("constant_radiance", "distance", 3.0, glideMs = 2000) }
                markdownNote(
                    "Next steps back to 3 m: the cone takes in nine times the lamp, each piece nine times fainter. [[back]] [[next]]",
                )
            }
            step("Equally bright from every side", id = "lambert") {
                audience {
                    figureBeside("lambert_lobe") {
                        +"""
                        ## Equally bright from every side

                        A matte emitter sends less power off to the side, in proportion to the cosine
                        of the angle. Seen from that side its surface is foreshortened by the same
                        cosine, so it looks equally bright from any direction.""".md
                    }
                }
                control("lambert_lobe", "viewing angle", 0.0)
                cue("From the side") { control("lambert_lobe", "viewing angle", 60.0, glideMs = 1800) }
                markdownNote("Next swings the view to 60°: half the power, through half the width. [[back]] [[next]]")
            }
        }

        section("Reflections") {
            step("Mirror to matte", id = "reflections") {
                audience {
                    figureBeside("rough_mirror") {
                        +"""
                        ## Mirror to matte

                        A mirror sends each ray back at the angle it arrived. Roughen it and every
                        ray meets a facet tilted its own way, so the returning rays fan out. A surface
                        rough enough at every scale sends light back in all directions alike: matte.""".md
                    }
                }
                control("rough_mirror", "roughness", 0.0)
                control("rough_mirror", "light angle", 40.0)
                cue("Roughen it") { control("rough_mirror", "roughness", 1.0, glideMs = 2500) }
                markdownNote(
                    "A mirror first; Next roughens the surface until the reflected rays leave in all directions. [[back]] [[next]]",
                )
            }
        }

        section("Shadow") {
            step("Soft and hard shadows", id = "soft-shadow") {
                audience {
                    figureBeside("soft_shadow") {
                        +"""
                        ## Soft and hard shadows

                        Keep the lamp's power fixed and make it bigger. Points near the edge of the
                        shadow can now see part of the lamp, and the hard edge softens into a
                        penumbra.""".md
                    }
                }
                control("soft_shadow", "size", 0.05)
                control("soft_shadow", "altitude", 50.0)
                control("soft_shadow", "point", 1.15)
                cue("A bigger lamp") { control("soft_shadow", "size", 0.4, glideMs = 2000) }
                cue("Into the shadow") { control("soft_shadow", "point", 0.3, glideMs = 2500) }
                markdownNote(
                    "The orange point sits at the shadow's edge; its cyan lines reach the parts of the lamp it can see. Next grows the lamp from 5 cm to 40 cm at the same 40 W, then walks the point into the shadow. [[back]] [[next]]",
                )
            }
        }

        section("Colour") {
            step("Coloured light", id = "colour") {
                audience {
                    figureBeside("light_colour") {
                        +"""
                        ## Coloured light

                        We stand in for the many wavelengths of light with three: red, green and blue
                        power. A surface's colour is how much of each it sends back.""".md
                    }
                }
                markdownNote("Pick a lamp colour: under pure red, the green and blue tiles go dark. [[back]] [[next]]")
            }
            step("Lights add up", id = "additive") {
                audience {
                    figureBeside("additive") {
                        +"""
                        ## Lights add up

                        Two lamps on one floor simply add. Where a red and a green pool of light
                        overlap, the floor turns yellow.""".md
                    }
                }
                control("additive", "red", true)
                control("additive", "green", true)
                control("additive", "blue", false)
                cue("Add blue") { control("additive", "blue", true) }
                markdownNote(
                    "Red and green first; Next turns on the blue lamp. Look at the block's coloured shadows. [[back]] [[next]]",
                )
            }
        }

        section("Bounces") {
            step("Light keeps going", id = "bounces") {
                audience {
                    figureBeside("red_wall") {
                        +"""
                        ## Light keeps going

                        The light a surface sends back lands on other surfaces. A red wall beside a
                        white floor tints the floor red. Each bounce is weaker than the last, and
                        their sum is the scene's indirect light.""".md
                    }
                }
                control("red_wall", "bounces", 0.0)
                control("red_wall", "bounced light only", false)
                cue("Three bounces") { control("red_wall", "bounces", 3.0, glideMs = 1500) }
                cue("Only the bounced light") { control("red_wall", "bounced light only", true) }
                markdownNote(
                    "Direct light only first. Next adds three bounces and the floor by the wall turns pink; the next shows the bounced light alone. [[back]] [[next]]",
                )
            }
        }

        section("Sphere of light") {
            step("A sky of light", id = "sky") {
                audience {
                    figureBeside("sky_sphere") {
                        +"""
                        ## A sky of light

                        Surround the scene with light from every direction, like an overcast sky.
                        Nothing casts a hard shadow, yet a tile still darkens the ground just
                        beneath it.""".md
                    }
                }
                control("sky_sphere", "lamps", 1.0)
                control("sky_sphere", "tile height", 0.3)
                cue("A sky of lamps") { control("sky_sphere", "lamps", 300.0, glideMs = 3000) }
                markdownNote(
                    "One lamp overhead; Next spreads the same 400 W over 300 lamps. Then try the tile height. [[back]] [[next]]",
                )
            }
        }

        step("Further reading", id = "further-reading") {
            audience {
                +"""
                ## Further reading

                - Matt Pharr, Wenzel Jakob and Greg Humphreys, [*Physically Based Rendering*](https://pbr-book.org/), free online.
                - Romain Guy and Mathias Agopian, [*Physically Based Rendering in Filament*](https://google.github.io/filament/Filament.md.html), free online.
                - Michael F. Cohen and John R. Wallace, [*Radiosity and Realistic Image Synthesis*](https://archive.org/details/radiosityrealist0000cohe) (1993).""".md
                p(classes = "muted") {
                    +"After “Lights and Shadows” by Bartosz Ciechanowski, https://ciechanow.ski/lights-and-shadows/. Words and figures made in Field."
                }
            }
            markdownNote("The end. [[back]] [[finish]]")
        }
    }
    .arrangeBoxes()
    .present()
```

## 5. lambert_lobe

<!-- field-box id="el_lambert_lobe" index="4" -->

### Narrative

From `code`

A small matte patch giving off light, seen from the side. It sends less light off to the side,
as the cosine of the angle, and it also looks narrower from there by the same cosine.

```edn [field-box id="el_lambert_lobe" role="properties"]
{"id" "el_lambert_lobe" "index" 4 "name" "lambert_lobe" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "lambert_lobe" "_tombstone" false "frame" #afield/rect [840.0 280.0 160.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "el_lambert_lobe_code_.kt" "lines" 59 "characters" 2101 "sha256" "f2958f7d91be59c5485c25014f98dcd44598d49171b7de660d35a6afedad5808"}]}
```

```kotlin [field-property box="el_lambert_lobe" name="code" path="el_lambert_lobe_code_.kt"]
// A small matte patch giving off light, seen from the side. It sends less light off to the side,
// as the cosine of the angle, and it also looks narrower from there by the same cosine.
val rays = 41

fun draw(degrees: Double) {
    val v = degrees * PI / 180
    val drawn = mutableListOf<FLine>()
    // Rays whose sines are evenly spaced: their density falls off as the cosine.
    for (i in 0 until rays) {
        val s = 2.0 * (i + 0.5) / rays - 1
        val c = sqrt(1 - s * s)
        drawn += fline {
            moveTo(320.0, 250.0)
            lineTo(320 + 210 * s, 250 - 210 * c)
            color = vec(1.0, 1.0, 1.0, 0.3)
        }
    }
    // The lobe: in each direction, the power sent that way. A circle resting on the patch.
    drawn += fline {
        moveTo(320.0, 250.0)
        for (k in 1..180) {
            val p = -PI / 2 + PI * k / 180
            lineTo(320 + 200 * cos(p) * sin(p), 250 - 200 * cos(p) * cos(p))
        }
        thickness = 2.0
        color = vec(0.4, 0.9, 1.0, 1.0)
    }
    drawn += fline {
        moveTo(270.0, 250.0)
        lineTo(370.0, 250.0)
        thickness = 6.0
        color = vec(1.0, 0.6, 0.2, 1.0)
    }
    // The viewing direction, and how wide the patch looks from there.
    drawn += fline {
        moveTo(320.0, 250.0)
        lineTo(320 + 240 * sin(v), 250 - 240 * cos(v))
        thickness = 2.0
        color = vec(1.0, 0.6, 0.2, 1.0)
    }
    val w = 50 * cos(v)
    drawn += fline {
        moveTo(320 + 240 * sin(v) - w * cos(v), 250 - 240 * cos(v) - w * sin(v))
        lineTo(320 + 240 * sin(v) + w * cos(v), 250 - 240 * cos(v) + w * sin(v))
        thickness = 5.0
        color = vec(1.0, 0.6, 0.2, 1.0)
    }
    lines(drawn, channel = "side", w = 640.0, h = 280.0, framing = LinesFraming.fixed(0.0, 0.0, 640.0, 280.0))
    val c = cos(v)
    markdown(
        "readout",
        "${degrees.roundToInt()}°: ${(c * 100).roundToInt()} % of the power, ${(c * 100).roundToInt()} % of the width",
    )
}

draw(0.0)
controlGroup {
    slider("viewing angle", -80.0, 80.0, step = 1.0, initialValue = 0.0) { draw(it) }
}
```

## 6. sphere_light

<!-- field-box id="el_sphere_light" index="5" -->

### Narrative

From `code`

A glowing ball and the light leaving it, drawn as rays: one ray for every quarter watt.

```edn [field-box id="el_sphere_light" role="properties"]
{"id" "el_sphere_light" "index" 5 "name" "sphere_light" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "sphere_light" "_tombstone" false "frame" #afield/rect [480.0 140.0 160.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "el_sphere_light_code_.kt" "lines" 35 "characters" 1345 "sha256" "4e71b6b6f47eeda7025bde5946e25d6d4ca842da02df0578b755847cfcd995fa"}]}
```

```kotlin [field-property box="el_sphere_light" name="code" path="el_sphere_light_code_.kt"]
// A glowing ball and the light leaving it, drawn as rays: one ray for every quarter watt.
val golden = PI * (3 - sqrt(5.0))

fun draw(watts: Double) {
    val n = (watts * 4).roundToInt()
    val rays = n { i ->
        // Directions spread evenly over the sphere, each ray starting on the ball's surface.
        val y = 1 - 2 * (i + 0.5) / n
        val r = sqrt(1 - y * y)
        val d = vec(r * cos(golden * i), y, r * sin(golden * i))
        fline {
            moveTo(d.x * 0.2, d.y * 0.2, d.z * 0.2)
            lineTo(d.x * 1.4, d.y * 1.4, d.z * 1.4)
            color = vec(1.0, 0.9, 0.6, 0.5)
        }
    }
    // How bright the ball looks: its glow fills up and then stays white.
    val glow = min(1.0, watts / 60)
    three(channel = "scene", width = 640, height = 420) {
        lines(rays)
        // Frame the view on the rays' reach, not on the ball.
        fitTo(vec(-1.0, -1.0, -1.0), vec(1.0, 1.0, 1.0))
        mesh(icosphereMesh(3, 0.2).withColor(glow, glow * 0.95, glow * 0.8), unlit = true, wireframe = false)
        camera(yaw = 20.0, pitch = 15.0, distance = 2.2)
        background("#000000")
        hud(false)
        helpers(false)
    }
    markdown("readout", "${watts.roundToInt()} W · $n rays")
}

draw(10.0)
controlGroup {
    slider("power", 1.0, 100.0, step = 1.0, initialValue = 10.0) { draw(it) }
}
```

## 7. lit_sphere

<!-- field-box id="el_lit_sphere" index="6" -->

```edn [field-box id="el_lit_sphere" role="properties"]
{"id" "el_lit_sphere" "index" 6 "name" "lit_sphere" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "lit_sphere" "_tombstone" false "frame" #afield/rect [300.0 210.0 160.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "el_lit_sphere_code_.kt" "lines" 23 "characters" 952 "sha256" "7ccc6c3dc8c85b6af32f6a10a12c0a0e76dc707e77a6e745611d01260ebe355c"}]}
```

```kotlin [field-property box="el_lit_sphere" name="code" path="el_lit_sphere_code_.kt"]
import afield.graphics.lighting.*

// A ball under one far-away light. Its shading is the cosine factor and nothing else.
val ball = icosphereMesh(4, 0.5)

fun draw(toward: Vec2) {
    // The control picks where the light is, seen from the camera; it sits 100 m away in that direction.
    val z = sqrt(max(0.0, 1.0 - toward.x * toward.x - toward.y * toward.y))
    val far = 100.0
    // Φ = 4π·100² W makes the irradiance one watt per square metre where the light hits head on.
    val sun = PointLight(vec(toward.x * far, toward.y * far, z * far), power = 4 * PI * far * far)
    three(channel = "scene", width = 640, height = 420) {
        mesh(ball.lit([sun], occluders = null, exposure = 2.0), unlit = true, wireframe = false)
        camera(yaw = 0.0, pitch = 0.0, distance = 2.4)
        background("#000000")
        hud(false)
    }
}

draw(vec(0.7, 0.4))
controlGroup {
    vec2Control("light", initialValue = vec(0.7, 0.4)) { draw(it) }
}
```

## 8. sky_sphere

<!-- field-box id="el_sky_sphere" index="7" -->

```edn [field-box id="el_sky_sphere" role="properties"]
{"id" "el_sky_sphere" "index" 7 "name" "sky_sphere" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "sky_sphere" "_tombstone" false "frame" #afield/rect [1020.0 350.0 160.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "el_sky_sphere_code_.kt" "lines" 46 "characters" 1842 "sha256" "e9681a487e474afc278413f4cff5cea1a75cb908493f65a9997fba29d5f3a2d4"}]}
```

```kotlin [field-property box="el_sky_sphere" name="code" path="el_sky_sphere_code_.kt"]
import afield.graphics.lighting.*

/*
A floor under a dome of small lamps, all of them together giving out 400 W, and a black tile
held above the floor. With one lamp the tile casts a hard shadow; with hundreds the light comes
from the whole sky and only the ground right under the tile stays dark.
*/
val floor = floorMesh(3.0, 3.0, divisions = 60).withColor(0.8, 0.8, 0.8)
val golden = PI * (3 - sqrt(5.0))
val dot = icosphereMesh(1, 0.03).withColor(1.0, 1.0, 1.0)
var count = 1.0
var height = 0.3

fun draw() {
    val n = count.toInt()
    // Lamps spread evenly over the upper half of a sphere of radius 2; the first sits overhead.
    val lamps = n { i ->
        val y = if (n == 1) 1.0 else 1 - (i + 0.5) / n
        val r = sqrt(1 - y * y)
        PointLight(vec(2 * r * cos(golden * i), 2 * y, 2 * r * sin(golden * i)), power = 400.0 / n)
    }
    val tile = panelBoxMesh(0.6, 0.02, 0.6, divisions = 4).translated(0.0, height, 0.0).withColor(0.02, 0.02, 0.02)
    val dots = lamps.map { dot.translated(it.position) }.reduce { a, b -> a + b }
    three(channel = "scene", width = 640, height = 420) {
        // Only the tile casts shadows.
        mesh((floor + tile).lit(lamps, occluders = tile, exposure = 0.4), unlit = true, wireframe = false)
        mesh(dots, unlit = true, wireframe = false)
        // Frame the view on the whole set, so it holds still as the lamp changes.
        fitTo(vec(-2.0, 0.0, -2.0), vec(2.0, 2.0, 2.0))
        camera(yaw = 25.0, pitch = 30.0, distance = 2.3)
        background("#000000")
        hud(false)
    }
}

draw()
controlGroup {
    slider("lamps", 1.0, 300.0, step = 1.0, initialValue = count, law = ControlLaw.LOGARITHMIC) {
        count = it
        draw()
    }
    slider("tile height", 0.05, 0.8, step = 0.01, initialValue = height) {
        height = it
        draw()
    }
}
```

## 9. projected_rect

<!-- field-box id="el_projected_rect" index="8" -->

### Narrative

From `code`

A square panel 0.8 m across and its shadow on a sphere of radius one around the eye. The area of
that shadow is the panel's solid angle; move the panel away and it shrinks as the square.

```edn [field-box id="el_projected_rect" role="properties"]
{"id" "el_projected_rect" "index" 8 "name" "projected_rect" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "projected_rect" "_tombstone" false "frame" #afield/rect [300.0 280.0 160.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "el_projected_rect_code_.kt" "lines" 50 "characters" 2226 "sha256" "e549bbebd6aaf46621e466076ab2201c5c16da99f47683cfafc0bc500d8c6f9f"}]}
```

```kotlin [field-property box="el_projected_rect" name="code" path="el_projected_rect_code_.kt"]
// A square panel 0.8 m across and its shadow on a sphere of radius one around the eye. The area of
// that shadow is the panel's solid angle; move the panel away and it shrinks as the square.
val sphere = icosphereMesh(4, 1.0)
// Two great circles, so the sphere reads as a sphere without a wireframe in the way.
val circles = [
    fline {
        moveTo(1.0, 0.0, 0.0)
        for (k in 1..96) lineTo(cos(2 * PI * k / 96), 0.0, sin(2 * PI * k / 96))
        color = vec(1.0, 1.0, 1.0, 0.35)
    },
    fline {
        moveTo(1.0, 0.0, 0.0)
        for (k in 1..96) lineTo(cos(2 * PI * k / 96), sin(2 * PI * k / 96), 0.0)
        color = vec(1.0, 1.0, 1.0, 0.35)
    },
]

fun draw(distance: Double) {
    val panel = planeMesh(12, 12, 0.8, 0.8).translated(0.0, 0.0, -distance)
    // Every point of the panel pushed onto the unit sphere, along its line to the eye.
    val shadow = panel.transformed { p -> p.normalize().mul(1.004f) }
    var omega = 0.0
    for (t in 0 until shadow.triangleCount) omega += shadow.triangleArea(t)
    // From the eye through each corner of the patch to the panel's corner.
    val corners =
        listOf(-0.4 to -0.4, 0.4 to -0.4, 0.4 to 0.4, -0.4 to 0.4).map { (x, y) ->
            fline {
                moveTo(0.0, 0.0, 0.0)
                lineTo(x, y, -distance)
                color = vec(1.0, 1.0, 1.0, 0.35)
            }
        }
    three(channel = "scene", width = 640, height = 420) {
        mesh(sphere.withColor(0.4, 0.45, 0.5), opacity = 0.1, wireframe = false)
        lines(circles + corners)
        mesh(panel.withColor(0.4, 0.9, 1.0), unlit = true, wireframe = false, cullBackFaces = false)
        mesh(shadow.withColor(1.0, 0.6, 0.2), unlit = true, wireframe = false, cullBackFaces = false)
        mesh(icosphereMesh(1, 0.03).withColor(1.0, 1.0, 1.0), unlit = true, wireframe = false)
        fitTo(vec(-1.1, -1.1, -2.4), vec(1.1, 1.1, 0.6))
        camera(yaw = 145.0, pitch = 18.0)
        background("#000000")
        hud(false)
    }
    markdown("readout", "${distance.f1} m: ${omega.f3} sr · × distance² = ${(omega * distance * distance).f2}")
}

draw(1.3)
controlGroup {
    slider("distance", 1.2, 4.0, step = 0.1, initialValue = 1.3) { draw(it) }
}
```

## 10. soft_shadow

<!-- field-box id="el_soft_shadow" index="9" -->

```edn [field-box id="el_soft_shadow" role="properties"]
{"id" "el_soft_shadow" "index" 9 "name" "soft_shadow" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "soft_shadow" "_tombstone" false "frame" #afield/rect [300.0 350.0 160.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "el_soft_shadow_code_.kt" "lines" 68 "characters" 2670 "sha256" "145be80ef4195e86ba19992adcd0a28dbc1be0ed6b75f15723ba1ea1e78b99aa"}]}
```

```kotlin [field-property box="el_soft_shadow" name="code" path="el_soft_shadow_code_.kt"]
import afield.graphics.lighting.*

/*
A black wall on a white floor, lit by a ball of light. The ball always gives out 40 W;
make it bigger and the edge of the shadow softens.
The wall's faces sit on the floor's grid lines, so no floor vertex is half under it.
*/
val floor = floorMesh(4.0, 3.2, divisions = 80).withColor(0.8, 0.8, 0.8)
val wall = panelBoxMesh(0.05, 0.6, 1.2, divisions = 4).translated(0.025, 0.3, 0.0).withColor(0.03, 0.03, 0.03)
val blocker = wall.bvh()
val scene = floor + wall
// The lit floor depends only on the lamp, so moving the point redraws the lines, not the light.
var litFor = ""
var litScene = scene
var size = 0.05
var altitude = 50.0
var probe = 1.15

fun draw() {
    val a = altitude * PI / 180
    val lamp = SphereLight(vec(-1.4 * cos(a), 1.4 * sin(a), 0.0), radius = size, power = 40.0, samples = 160)
    // From one point on the floor: a line to every bit of the lamp it faces, cyan where it is clear.
    val point = vec(probe, 0.0, 0.0)
    val sight = lamp.sightLines(point, vec(0.0, 1.0, 0.0), blocker)
    val rays = sight.map { s ->
        fline {
            moveTo(s.from.x.toDouble(), s.from.y.toDouble(), s.from.z.toDouble())
            lineTo(s.to.x.toDouble(), s.to.y.toDouble(), s.to.z.toDouble())
            color = if (s.clear) vec(0.4, 0.9, 1.0, 0.7) else vec(1.0, 1.0, 1.0, 0.08)
        }
    }
    if (litFor != "$size $altitude") {
        // Only the wall casts shadows; the floor need not be tested against its own triangles.
        litScene = scene.lit([lamp], occluders = wall, exposure = 0.6)
        litFor = "$size $altitude"
    }
    three(channel = "scene", width = 640, height = 420) {
        mesh(litScene, unlit = true, wireframe = false)
        mesh(lamp.mesh(), unlit = true, wireframe = false)
        mesh(icosphereMesh(2, 0.035).translated(point).withColor(1.0, 0.6, 0.2), unlit = true, wireframe = false)
        lines(rays)
        // Frame the view on the whole set, so it holds still as the lamp changes.
        fitTo(vec(-2.0, 0.0, -1.6), vec(2.0, 1.6, 1.6))
        camera(yaw = 20.0, pitch = 35.0)
        background("#000000")
        hud(false)
    }
    markdown(
        "readout",
        "The orange point sees ${(100.0 * sight.count { it.clear } / sight.size.coerceAtLeast(1)).roundToInt()} % of the lamp",
    )
}

draw()
controlGroup {
    slider("size", 0.02, 0.5, step = 0.01, initialValue = size) {
        size = it
        draw()
    }
    slider("altitude", 15.0, 85.0, step = 1.0, initialValue = altitude) {
        altitude = it
        draw()
    }
    slider("point", -1.5, 1.8, step = 0.01, initialValue = probe) {
        probe = it
        draw()
    }
}
```

## 11. constant_radiance

<!-- field-box id="el_constant_radiance" index="10" -->

### Narrative

From `code`

An eye looking at a wide lamp, from the side. One cell of the eye takes in a fixed cone of
directions; move the lamp away and the cone covers more of it.

```edn [field-box id="el_constant_radiance" role="properties"]
{"id" "el_constant_radiance" "index" 10 "name" "constant_radiance" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "constant_radiance" "_tombstone" false "frame" #afield/rect [660.0 280.0 160.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "el_constant_radiance_code_.kt" "lines" 41 "characters" 1290 "sha256" "a922f0699468f7e2d98606b21ed155eeaeb6191e0fd3e5a4c296fa1737988f57"}]}
```

```kotlin [field-property box="el_constant_radiance" name="code" path="el_constant_radiance_code_.kt"]
// An eye looking at a wide lamp, from the side. One cell of the eye takes in a fixed cone of
// directions; move the lamp away and the cone covers more of it.
fun draw(distance: Double) {
    val half = 5 * PI / 180
    val x = 60 + distance * 130
    val reach = (x - 60) * tan(half)
    val drawn = mutableListOf<FLine>()
    drawn += fline {
        moveTo(x, 20.0)
        lineTo(x, 260.0)
        thickness = 4.0
        color = vec(1.0, 1.0, 1.0, 0.5)
    }
    drawn += fline {
        moveTo(x, 140 - reach)
        lineTo(x, 140 + reach)
        thickness = 8.0
        color = vec(1.0, 0.6, 0.2, 1.0)
    }
    drawn += fline {
        moveTo(x, 140 - reach)
        lineTo(60.0, 140.0)
        lineTo(x, 140 + reach)
        color = vec(0.4, 0.9, 1.0, 1.0)
    }
    drawn += fline {
        moveTo(40.0, 125.0)
        lineTo(60.0, 140.0)
        lineTo(40.0, 155.0)
        thickness = 3.0
        color = vec(1.0, 1.0, 1.0, 0.8)
    }
    lines(drawn, channel = "side", w = 640.0, h = 280.0, framing = LinesFraming.fixed(0.0, 0.0, 640.0, 280.0))
    val d2 = distance * distance
    markdown("readout", "${distance.f1} m: ${d2.f1}× the area, each ${d2.f1}× fainter")
}

draw(1.0)
controlGroup {
    slider("distance", 1.0, 4.0, step = 0.1, initialValue = 1.0) { draw(it) }
}
```

## 12. car_angle

<!-- field-box id="el_car_angle" index="11" -->

### Narrative

From `code`

A car 1.8 m wide driving away from you, from above and through your eye. How wide it looks is
the angle it spans, and that angle shrinks about as one over the distance.

```edn [field-box id="el_car_angle" role="properties"]
{"id" "el_car_angle" "index" 11 "name" "car_angle" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "car_angle" "_tombstone" false "frame" #afield/rect [840.0 210.0 160.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "el_car_angle_code_.kt" "lines" 49 "characters" 1844 "sha256" "1b114925572749831babf8a9db96f98634f13b80203f590ad35f4f52f593821e"}]}
```

```kotlin [field-property box="el_car_angle" name="code" path="el_car_angle_code_.kt"]
// A car 1.8 m wide driving away from you, from above and through your eye. How wide it looks is
// the angle it spans, and that angle shrinks about as one over the distance.
fun draw(distance: Double) {
    val half = atan(0.9 / distance)
    val scale = 9.0
    val x = 30 + distance * scale
    val top = mutableListOf<FLine>()
    top += fline {
        moveTo(x, 100 - 0.9 * scale)
        lineTo(x + 4.5 * scale, 100 - 0.9 * scale)
        lineTo(x + 4.5 * scale, 100 + 0.9 * scale)
        lineTo(x, 100 + 0.9 * scale)
        lineTo(x, 100 - 0.9 * scale)
        filled = true
        color = vec(1.0, 0.6, 0.2, 1.0)
    }
    top += fline {
        moveTo(x, 100 - 0.9 * scale)
        lineTo(30.0, 100.0)
        lineTo(x, 100 + 0.9 * scale)
        color = vec(0.4, 0.9, 1.0, 1.0)
    }
    lines(top, channel = "above", w = 640.0, h = 200.0, framing = LinesFraming.fixed(0.0, 0.0, 640.0, 200.0))
    // The eye's view: 40° across 640 pixels. The car is 1.8 m wide and 1.5 m tall.
    val perDegree = 640.0 / 40
    val w = 2 * half * 180 / PI * perDegree
    val h = 2 * atan(0.75 / distance) * 180 / PI * perDegree
    val eye = fline {
        moveTo(320 - w / 2, 140 - h)
        lineTo(320 + w / 2, 140 - h)
        lineTo(320 + w / 2, 140.0)
        lineTo(320 - w / 2, 140.0)
        lineTo(320 - w / 2, 140 - h)
        filled = true
        color = vec(1.0, 0.6, 0.2, 1.0)
    }
    val ground = fline {
        moveTo(0.0, 140.0)
        lineTo(640.0, 140.0)
        color = vec(1.0, 1.0, 1.0, 0.35)
    }
    lines(ground, eye, channel = "view", w = 640.0, h = 160.0, framing = LinesFraming.fixed(0.0, 0.0, 640.0, 160.0))
    markdown("readout", "${distance.roundToInt()} m away: ${(2 * half * 180 / PI).f1}°")
}

draw(10.0)
controlGroup {
    slider("distance", 3.0, 60.0, step = 1.0, initialValue = 10.0) { draw(it) }
}
```

## 13. light_angle

<!-- field-box id="el_light_angle" index="12" -->

### Narrative

From `code`

Parallel rays falling on an orange patch of floor, seen from the side.
Tilt them away from straight down and fewer of them land on the patch.

```edn [field-box id="el_light_angle" role="properties"]
{"id" "el_light_angle" "index" 12 "name" "light_angle" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "light_angle" "_tombstone" false "frame" #afield/rect [1020.0 140.0 160.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "el_light_angle_code_.kt" "lines" 64 "characters" 2246 "sha256" "af3318c53369a79f72daa2b3219e9071bee91dea44f395d068eb617a1dcaf3b8"}]}
```

```kotlin [field-property box="el_light_angle" name="code" path="el_light_angle_code_.kt"]
// Parallel rays falling on an orange patch of floor, seen from the side.
// Tilt them away from straight down and fewer of them land on the patch.
val rays = 12

fun draw(degrees: Double) {
    val a = degrees * PI / 180
    val across = sin(a)
    val down = cos(a)
    var landed = 0
    val drawn = mutableListOf<FLine>()
    for (i in 0 until rays) {
        // Rays sit evenly across a beam 200 pixels wide; this is where each one meets the floor.
        val x = 320 + ((i + 0.5) / rays - 0.5) * 200 / down
        val hit = abs(x - 320) <= 100
        if (hit) landed++
        // Each ray is drawn back from the floor to the top or side of the frame.
        val back = min(230 / down, if (across > 0) x / across else 1e9)
        drawn += fline {
            moveTo(x - across * back, 250 - down * back)
            lineTo(x, 250.0)
            thickness = 1.5
            color = if (hit) vec(0.4, 0.9, 1.0, 1.0) else vec(1.0, 1.0, 1.0, 0.35)
        }
    }
    drawn += fline {
        moveTo(0.0, 250.0)
        lineTo(640.0, 250.0)
        color = vec(1.0, 1.0, 1.0, 0.35)
    }
    drawn += fline {
        moveTo(220.0, 250.0)
        lineTo(420.0, 250.0)
        thickness = 6.0
        color = vec(1.0, 0.6, 0.2, 1.0)
    }
    lines(drawn, channel = "side", w = 640.0, h = 280.0, framing = LinesFraming.fixed(0.0, 0.0, 640.0, 280.0))

    // The cosine of the angle, with this angle marked.
    val curve = fline {
        moveTo(40.0, 20.0)
        for (d in 1..90) lineTo(40.0 + d * 6.4, 20.0 + 120 * (1 - cos(d * PI / 180)))
        thickness = 2.0
        color = vec(0.4, 0.9, 1.0, 1.0)
    }
    val axes = fline {
        moveTo(40.0, 10.0)
        lineTo(40.0, 140.0)
        lineTo(620.0, 140.0)
        color = vec(1.0, 1.0, 1.0, 0.35)
    }
    val here = fline {
        moveTo(40.0 + degrees * 6.4, 140.0)
        lineTo(40.0 + degrees * 6.4, 20.0 + 120 * (1 - down))
        thickness = 2.0
        color = vec(1.0, 0.6, 0.2, 1.0)
    }
    lines(axes, curve, here, channel = "cosine", w = 640.0, h = 150.0)
    markdown("readout", "$landed of $rays rays land · cos ${degrees.roundToInt()}° = ${down.f2}")
}

draw(0.0)
controlGroup {
    slider("angle", 0.0, 85.0, step = 1.0, initialValue = 0.0) { draw(it) }
}
```

## 14. rough_mirror

<!-- field-box id="el_rough_mirror" index="13" -->

### Narrative

From `code`

Parallel rays striking a surface, seen from the side. Each ray meets a tiny facet of the
surface and leaves as from a mirror; roughness tilts the facets, so the rays fan out.

```edn [field-box id="el_rough_mirror" role="properties"]
{"id" "el_rough_mirror" "index" 13 "name" "rough_mirror" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "rough_mirror" "_tombstone" false "frame" #afield/rect [1020.0 280.0 160.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "el_rough_mirror_code_.kt" "lines" 59 "characters" 1991 "sha256" "1af0ab11e029cd435e80ffe466c33837af5a380d6dec4604010bcbae9d520438"}]}
```

```kotlin [field-property box="el_rough_mirror" name="code" path="el_rough_mirror_code_.kt"]
// Parallel rays striking a surface, seen from the side. Each ray meets a tiny facet of the
// surface and leaves as from a mirror; roughness tilts the facets, so the rays fan out.
val rays = 25
var roughness = 0.0
var angle = 40.0

fun draw() {
    val a = angle * PI / 180
    val drawn = mutableListOf<FLine>()
    drawn += fline {
        moveTo(20.0, 240.0)
        lineTo(620.0, 240.0)
        color = vec(1.0, 1.0, 1.0, 0.35)
    }
    for (i in 0 until rays) {
        val x = 220 + 200.0 * i / (rays - 1)
        // Each facet's tilt, spread evenly (golden-ratio steps) over ± roughness × 50°.
        val tilt = roughness * 50 * PI / 180 * (2 * ((i * 0.618034) % 1.0) - 1)
        // Travelling down and to the right; y grows downward in this drawing.
        val dx = sin(a)
        val dy = cos(a)
        // The facet's normal points up, tilted; the ray reflects about it.
        val nx = sin(tilt)
        val ny = -cos(tilt)
        val dot = dx * nx + dy * ny
        var rx = dx - 2 * dot * nx
        var ry = dy - 2 * dot * ny
        if (ry > 0) ry = -ry
        drawn += fline {
            moveTo(x - dx * 230, 240 - dy * 230)
            lineTo(x, 240.0)
            color = vec(1.0, 0.6, 0.2, 0.6)
        }
        drawn += fline {
            moveTo(x, 240.0)
            lineTo(x + rx * 200, 240 + ry * 200)
            color = vec(0.4, 0.9, 1.0, 0.9)
        }
        drawn += fline {
            moveTo(x - 6 * cos(tilt), 240 - 6 * sin(tilt))
            lineTo(x + 6 * cos(tilt), 240 + 6 * sin(tilt))
            thickness = 2.0
            color = vec(1.0, 1.0, 1.0, 0.8)
        }
    }
    lines(drawn, channel = "side", w = 640.0, h = 280.0, framing = LinesFraming.fixed(0.0, 0.0, 640.0, 280.0))
}

draw()
controlGroup {
    slider("roughness", 0.0, 1.0, step = 0.01, initialValue = roughness) {
        roughness = it
        draw()
    }
    slider("light angle", 0.0, 70.0, step = 1.0, initialValue = angle) {
        angle = it
        draw()
    }
}
```

## 15. additive

<!-- field-box id="el_additive" index="14" -->

```edn [field-box id="el_additive" role="properties"]
{"id" "el_additive" "index" 14 "name" "additive" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "additive" "_tombstone" false "frame" #afield/rect [660.0 350.0 160.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "el_additive_code_.kt" "lines" 56 "characters" 1730 "sha256" "48d07b748eb450f9013f7b39b851c9ec3851fd7466339002fc26c077240b87e7"}]}
```

```kotlin [field-property box="el_additive" name="code" path="el_additive_code_.kt"]
import afield.graphics.lighting.*

// Red, green and blue lamps over a white floor with a white block. Where two pools overlap
// their light adds; the block's shadows take the colours of the lamps it hides.
val floor = floorMesh(2.4, 2.4, divisions = 72).withColor(0.8, 0.8, 0.8)
val block = panelBoxMesh(0.3, 0.5, 0.3, divisions = 6).translated(0.0, 0.25, 0.0).withColor(0.8, 0.8, 0.8)
val scene = floor + block
var red = true
var green = true
var blue = true
var spread = 0.6

fun draw() {
    val lamps = listOf(
        Triple(red, 90.0, vec(1.0, 0.0, 0.0)),
        Triple(green, 210.0, vec(0.0, 1.0, 0.0)),
        Triple(blue, 330.0, vec(0.0, 0.0, 1.0)),
    ).filter { it.first }
        .map { (_, degrees, colour) ->
            val a = degrees * PI / 180
            SphereLight(
                vec(spread * cos(a), 1.2, spread * sin(a)),
                radius = 0.04,
                power = 25.0,
                color = colour,
                samples = 128,
            )
        }
    three(channel = "scene", width = 640, height = 420) {
        mesh(scene.lit(lamps, occluders = block, exposure = 1.2), unlit = true, wireframe = false)
        lamps.forEach { mesh(it.mesh(), unlit = true, wireframe = false) }
        camera(yaw = 20.0, pitch = 45.0, distance = 2.0)
        background("#000000")
        hud(false)
    }
}

draw()
controlGroup {
    checkbox("red", initialValue = true) {
        red = it
        draw()
    }
    checkbox("green", initialValue = true) {
        green = it
        draw()
    }
    checkbox("blue", initialValue = true) {
        blue = it
        draw()
    }
    slider("spread", 0.0, 1.2, step = 0.01, initialValue = spread) {
        spread = it
        draw()
    }
}
```

## 16. radians

<!-- field-box id="el_radians" index="15" -->

### Narrative

From `code`

An angle measured by the arc it cuts from a circle. Unrolled on the right, the arc is so many
radii long: that count is the angle in radians.

```edn [field-box id="el_radians" role="properties"]
{"id" "el_radians" "index" 15 "name" "radians" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "radians" "_tombstone" false "frame" #afield/rect [1020.0 210.0 160.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "el_radians_code_.kt" "lines" 60 "characters" 1897 "sha256" "58afcecbca48b8a2bd98235222de1e19604a1c177429ee45fbae14d87c0f25b9"}]}
```

```kotlin [field-property box="el_radians" name="code" path="el_radians_code_.kt"]
// An angle measured by the arc it cuts from a circle. Unrolled on the right, the arc is so many
// radii long: that count is the angle in radians.
var radius = 90.0
var degrees = 60.0

fun draw() {
    val a = degrees * PI / 180
    val cx = 160.0
    val cy = 150.0
    val drawn = mutableListOf<FLine>()
    drawn += fline {
        moveTo(cx + radius, cy)
        for (k in 1..120) lineTo(cx + radius * cos(2 * PI * k / 120), cy - radius * sin(2 * PI * k / 120))
        color = vec(1.0, 1.0, 1.0, 0.35)
    }
    drawn += fline {
        moveTo(cx + radius, cy)
        lineTo(cx, cy)
        lineTo(cx + radius * cos(a), cy - radius * sin(a))
        color = vec(1.0, 1.0, 1.0, 0.8)
    }
    drawn += fline {
        moveTo(cx + radius, cy)
        for (k in 1..90) lineTo(cx + radius * cos(a * k / 90), cy - radius * sin(a * k / 90))
        thickness = 4.0
        color = vec(0.4, 0.9, 1.0, 1.0)
    }
    // The arc laid straight, drawn at 0.4 of its length, with a tick at every radius.
    val x0 = 330.0
    drawn += fline {
        moveTo(x0, 270.0)
        lineTo(x0 + radius * a * 0.4, 270.0)
        thickness = 4.0
        color = vec(0.4, 0.9, 1.0, 1.0)
    }
    var k = 0
    while (k * radius <= radius * a + 1e-9) {
        drawn += fline {
            moveTo(x0 + k * radius * 0.4, 258.0)
            lineTo(x0 + k * radius * 0.4, 282.0)
            thickness = 2.0
            color = vec(1.0, 0.6, 0.2, 1.0)
        }
        k++
    }
    lines(drawn, channel = "circle", w = 640.0, h = 300.0, framing = LinesFraming.fixed(0.0, 0.0, 640.0, 300.0))
    markdown("readout", "${degrees.roundToInt()}° = ${a.f2} radians")
}

draw()
controlGroup {
    slider("angle", 0.0, 360.0, step = 1.0, initialValue = degrees) {
        degrees = it
        draw()
    }
    slider("radius", 40.0, 120.0, step = 1.0, initialValue = radius) {
        radius = it
        draw()
    }
}
```

## 17. light_colour

<!-- field-box id="el_light_colour" index="16" -->

```edn [field-box id="el_light_colour" role="properties"]
{"id" "el_light_colour" "index" 16 "name" "light_colour" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "light_colour" "_tombstone" false "frame" #afield/rect [480.0 350.0 160.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "el_light_colour_code_.kt" "lines" 40 "characters" 1238 "sha256" "0cbbf2ae262f24709287defa330450dfa734947705b41aad07d4dba4da4667e6"}]}
```

```kotlin [field-property box="el_light_colour" name="code" path="el_light_colour_code_.kt"]
import afield.graphics.lighting.*

// Nine tiles of different colours under one lamp whose colour you pick. A tile can only send
// back the colours the lamp gives it.
val colours = [
    vec(0.8, 0.8, 0.8),
    vec(0.8, 0.1, 0.1),
    vec(0.1, 0.7, 0.1),
    vec(0.1, 0.2, 0.8),
    vec(0.8, 0.7, 0.1),
    vec(0.1, 0.7, 0.7),
    vec(0.7, 0.1, 0.7),
    vec(0.8, 0.4, 0.1),
    vec(0.3, 0.3, 0.3),
]
val tiles =
    colours
        .withIndex()
        .map { (i, c) ->
            floorMesh(0.5, 0.5, divisions = 8)
                .translated((i % 3 - 1) * 0.6, 0.0, (i / 3 - 1) * 0.6)
                .withColor(c.x, c.y, c.z)
        }
        .reduce { a, b -> a + b }

fun draw(light: Vec4) {
    val lamp =
        RectLight(vec(0.0, 1.6, 0.0), width = 1.2, height = 1.2, power = 60.0, color = vec(light.x, light.y, light.z))
    three(channel = "scene", width = 640, height = 420) {
        mesh(tiles.lit([lamp], occluders = null, exposure = 0.6), unlit = true, wireframe = false)
        camera(yaw = 0.0, pitch = 60.0, distance = 2.0)
        background("#000000")
        hud(false)
    }
}

draw(vec(1.0, 1.0, 1.0, 1.0))
controlGroup {
    colorPicker("light colour", initialValue = vec(1.0, 1.0, 1.0, 1.0)) { draw(it) }
}
```

## 18. opening_scene

<!-- field-box id="el_opening_scene" index="17" -->

```edn [field-box id="el_opening_scene" role="properties"]
{"id" "el_opening_scene" "index" 17 "name" "opening_scene" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "opening_scene" "_tombstone" false "frame" #afield/rect [300.0 140.0 160.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "el_opening_scene_code_.kt" "lines" 34 "characters" 1167 "sha256" "979b26735c144943e37d2246371d0a694fbe5ab4028df19e03601415cdfba1dd"}]}
```

```kotlin [field-property box="el_opening_scene" name="code" path="el_opening_scene_code_.kt"]
import afield.graphics.lighting.*

// A lamp over a floor with a block on it. The light at every vertex is computed here.
val floor = floorMesh(4.0, 4.0, divisions = 64)
val block = panelBoxMesh(0.5, 0.5, 0.5, divisions = 10).translated(0.0, 0.25, 0.0)
val scene = floor + block
var across = 0.6
var size = 0.5

fun draw() {
    val lamp = RectLight(vec(across, 1.3, 0.0), width = size, height = size, power = 30.0)
    three(channel = "scene", width = 640, height = 420) {
        // Only the block casts shadows.
        mesh(scene.lit([lamp], occluders = block, exposure = 0.5), unlit = true, wireframe = false)
        // Frame the view on the whole set, so it holds still as the lamp changes.
        fitTo(vec(-2.0, 0.0, -2.0), vec(2.0, 1.6, 2.0))
        mesh(lamp.mesh(), unlit = true, wireframe = false, cullBackFaces = false)
        camera(yaw = 35.0, pitch = 30.0, distance = 2.6)
        background("#000000")
        hud(false)
    }
}

draw()
controlGroup {
    slider("position", -1.2, 1.2, initialValue = across) {
        across = it
        draw()
    }
    slider("size", 0.05, 1.5, initialValue = size) {
        size = it
        draw()
    }
}
```

## 19. red_wall

<!-- field-box id="el_red_wall" index="18" -->

```edn [field-box id="el_red_wall" role="properties"]
{"id" "el_red_wall" "index" 18 "name" "red_wall" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "red_wall" "_tombstone" false "frame" #afield/rect [840.0 350.0 160.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "el_red_wall_code_.kt" "lines" 48 "characters" 1495 "sha256" "d867675feb9471c5191b7abc5470e4a5f9d64455413ae1464a59cba92dbcb27e"}]}
```

```kotlin [field-property box="el_red_wall" name="code" path="el_red_wall_code_.kt"]
import afield.graphics.lighting.*

/*
A white floor beside a red wall, lit by one small lamp close to the wall, so the wall is lit
far more brightly than the floor. The solve follows the light through three bounces once,
when the box runs; the sliders only choose what to show.
*/
val floor = floorMesh(2.4, 2.4, divisions = 24).withColor(0.8, 0.8, 0.8)
val wall = panelBoxMesh(2.4, 1.4, 0.05, divisions = 16).translated(0.0, 0.7, -1.225).withColor(0.9, 0.06, 0.03)
val lamp = SphereLight(vec(0.0, 0.9, -0.8), radius = 0.06, power = 1.0)
val solve = Radiosity(floor + wall, [lamp], bounces = 3)

var power = 20.0
var bounces = 0.0
var bounceOnly = false

fun draw() {
    val shown = solve.lit(
        upTo = bounces.toInt(),
        from = if (bounceOnly) 1 else 0,
        exposure = if (bounceOnly) 2.0 else 0.6,
        scale = power,
        toneMap = ToneMap.EXPONENTIAL,
    )
    three(channel = "scene", width = 640, height = 420) {
        mesh(shown, unlit = true, wireframe = false)
        mesh(lamp.mesh(), unlit = true, wireframe = false)
        camera(yaw = 25.0, pitch = 28.0, distance = 2.2)
        background("#000000")
        hud(false)
    }
}

draw()
controlGroup {
    slider("power", 5.0, 60.0, step = 1.0, initialValue = power) {
        power = it
        draw()
    }
    slider("bounces", 0.0, 3.0, step = 1.0, initialValue = bounces) {
        bounces = it
        draw()
    }
    checkbox("bounced light only") {
        bounceOnly = it
        draw()
    }
}
```

