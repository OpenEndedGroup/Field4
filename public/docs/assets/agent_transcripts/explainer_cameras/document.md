# Field Document: explainer_cameras

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 19 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. kilopixel

<!-- field-box id="ec_kilopixel" index="0" -->

```edn [field-box id="ec_kilopixel" role="properties"]
{"id" "ec_kilopixel" "index" 0 "name" "kilopixel" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "kilopixel" "_tombstone" false "frame" #afield/rect [490.0 140.0 170.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ec_kilopixel_code_.kt" "lines" 39 "characters" 1203 "sha256" "30c35225ae42211977ef0b2644032ec35a1725b58c2e36e0a867ca9e3dd53b0d"}]}
```

```kotlin [field-property box="ec_kilopixel" name="code" path="ec_kilopixel_code_.kt"]
import afield.media.image.util.PngEncoder
import afield.simulation.optics.*

// A sensor of 32 × 32 detectors, a kilopixel. Bare, each detector counts all light the same.
// With filters each counts only red, green or blue; filling in guesses the other two from neighbours.
val light =
    SensorCamera(z = -5.0, sensorWidth = 1.2, sensorHeight = 1.2, sensorDistance = 1.0, apertureDiameter = 0.03)
        .render(OpticsScene.testScene(), 32, 32, samples = 32)
var filters = false
var fillIn = false

fun draw() {
    val shown = when {
        !filters ->
            SensorImage(
                32,
                32,
                FloatArray(32 * 32 * 3) {
                    (light.rgb[it / 3 * 3] + light.rgb[it / 3 * 3 + 1] + light.rgb[it / 3 * 3 + 2]) / 3
                },
                light.reference,
            )
        !fillIn -> light.mosaic()
        else -> light.mosaic().demosaic()
    }
    display("sensor", PngEncoder.encode(shown.toArgb(exposure = 1 / light.reference, scale = 12), 384, 384))
}

draw()
controlGroup {
    checkbox("colour filters") {
        filters = it
        draw()
    }
    checkbox("fill in colours") {
        fillIn = it
        draw()
    }
}
```

## 2. slab_ray

<!-- field-box id="ec_slab_ray" index="1" -->

```edn [field-box id="ec_slab_ray" role="properties"]
{"id" "ec_slab_ray" "index" 1 "name" "slab_ray" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "slab_ray" "_tombstone" false "frame" #afield/rect [870.0 220.0 170.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ec_slab_ray_code_.kt" "lines" 68 "characters" 2087 "sha256" "ed6226ce8cd92b4b43d7369fbcb3c7e69b2f22e164a577701015e7740fc85603"}]}
```

```kotlin [field-property box="ec_slab_ray" name="code" path="ec_slab_ray_code_.kt"]
import afield.simulation.optics.*

// One ray through a slab of glass. It bends going in and bends back coming out, so it leaves
// parallel to where it was going, but shifted. The faint line is the path it would have taken.
val glass = 1.5
val unit = 100.0
val top = 100.0
val cyan = vec(0.4, 0.9, 1.0, 1.0)
val guide = vec(1.0, 1.0, 1.0, 0.35)
var slab = 1.0
var angle = 45.0

fun draw() {
    val theta = angle * PI / 180
    val inside = Refraction.snellAngle(theta, 1.0, glass)
    val bottom = top + slab * unit
    val entryX = 200.0
    val exitX = entryX + slab * unit * tan(inside)
    val endY = 360.0
    val start = frameExit(entryX, top, -sin(theta), -cos(theta), 640.0, endY)
    val guideEnd = frameExit(entryX, top, sin(theta), cos(theta), 640.0, endY)
    val parts = listOf(
        fline {
            rect(0, top, 640, slab * unit)
            filled = true
            stroked = false
            color = vec(0.4, 0.9, 1.0, 0.12)
        },
        fline {
            moveTo(entryX, top)
            lineTo(guideEnd[0], guideEnd[1])
            color = guide
            thickness = 1.0
        },
        fline {
            moveTo(start[0], start[1])
            lineTo(entryX, top)
            lineTo(exitX, bottom)
            frameExit(exitX, bottom, sin(theta), cos(theta), 640.0, endY).let { lineTo(it[0], it[1]) }
            color = cyan
            thickness = 2.5
        },
    )
    showPlane(
        parts,
        channel = "side",
        width = 600,
        height = 338,
        padding = 0.0,
        window = Rect(0.0, 0.0, 640.0, endY),
        interactive = false,
    )
    // How far apart the two parallel paths are, measured square to them.
    val shift = slab * sin(theta - inside) / cos(inside)
    markdown("readout", "slab ${slab.f2} thick: the ray comes out shifted ${shift.f2} sideways")
}

draw()
controlGroup {
    slider("thickness", 0.0, 2.2, step = 0.02, initialValue = 1.0) {
        slab = it
        draw()
    }
    slider("angle", 0.0, 75.0, step = 0.5, initialValue = 45.0) {
        angle = it
        draw()
    }
}
```

## 3. pinhole_box

<!-- field-box id="ec_pinhole_box" index="2" -->

```edn [field-box id="ec_pinhole_box" role="properties"]
{"id" "ec_pinhole_box" "index" 2 "name" "pinhole_box" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "pinhole_box" "_tombstone" false "frame" #afield/rect [300.0 220.0 170.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ec_pinhole_box_code_.kt" "lines" 66 "characters" 2589 "sha256" "02b6612376415161c9c481ab347e875c77ba9fd22d91648f63b0a304b63f1256"}]}
```

```kotlin [field-property box="ec_pinhole_box" name="code" path="ec_pinhole_box_code_.kt"]
import afield.simulation.optics.*

// A closed box with a small hole, looking at the test scene. The picture forms on the back wall.
val scene = OpticsScene.testScene()
var hole = 0.04
var distance = 1.0
var fixedExposure = false

// Three points of the scene, each with its ray through the hole to the back wall.
val marked = [Triple(-0.9, 1.4, -0.8), Triple(1.0, 1.2, 1.3), Triple(0.3, 0.0, -2.0)]

fun draw() {
    val pinhole = SensorCamera(z = -5.0, sensorDistance = distance, apertureDiameter = hole)
    val image = pinhole.render(scene, 240, 160, samples = 48)
    // A coarser copy for the back wall of the 3D box, which needs a quad per pixel.
    val wall = pinhole.render(scene, 96, 64, samples = 24)
    // Fixed: right for a hole 0.08 wide at distance 1. Otherwise each picture is scaled to look right.
    val exposure = if (fixedExposure) 1.0 / (PI * 0.04 * 0.04) else 1.0 / image.reference
    // Each ray from 1.8 units in front of the hole, through it, to where it lands.
    val rays = marked.map { (x, y, z) ->
        val landing = pinhole.imageOf(x, y, z)
        val dx = x
        val dy = y - 1.2
        val dz = z + 5.0
        val k = 1.8 / sqrt(dx * dx + dy * dy + dz * dz)
        fline {
            moveTo(dx * k, 1.2 + dy * k, -5.0 + dz * k)
            lineTo(0.0, 1.2, -5.0)
            lineTo(landing[0], landing[1], landing[2])
            color = vec(1.0, 0.6, 0.2, 1.0)
            thickness = 1.5
        }
    }
    /*
    The box, the rays through it and the objects they come from: the view frames the box
    (at its longest, with the sensor 2.5 back) and the two spheres, and lets the floor run
    off the edges.
    */
    three(channel = "box", height = 320, width = 640) {
        mesh(scene.mesh(), wireframe = false)
        mesh(pinhole.sensorMesh(wall, exposure), wireframe = false, unlit = true, cullBackFaces = false)
        lines(pinhole.outline() + rays)
        fitTo(vec(-1.6, 0.0, -7.6), vec(1.6, 2.0, 1.6))
        camera(yaw = 240.0, pitch = 16.0)
        // The scene's bright sky, as in the pictures the camera takes.
        background("#d8dde3")
    }
    // Smoothed: these pixels stand for a picture, not for detectors.
    display("sensor", image.html(exposure, width = 480))
}

draw()
controlGroup {
    slider("hole diameter", 0.005, 0.3, step = 0.005, initialValue = 0.04) {
        hole = it
        draw()
    }
    slider("sensor distance", 0.4, 2.5, step = 0.05, initialValue = 1.0) {
        distance = it
        draw()
    }
    checkbox("fixed exposure") {
        fixedExposure = it
        draw()
    }
}
```

## 4. test_scene

<!-- field-box id="ec_test_scene" index="3" -->

```edn [field-box id="ec_test_scene" role="properties"]
{"id" "ec_test_scene" "index" 3 "name" "test_scene" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "test_scene" "_tombstone" false "frame" #afield/rect [680.0 140.0 170.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ec_test_scene_code_.kt" "lines" 7 "characters" 252 "sha256" "fecb4ce1aeb96ee732c838f6ea9fa831d908f58030101cb65e9c2beebce5cbda"}]}
```

```kotlin [field-property box="ec_test_scene" name="code" path="ec_test_scene_code_.kt"]
import afield.simulation.optics.*

// The scene every camera in the talk photographs. Drag to look around it.
three(channel = "scene", height = 400, width = 640) {
    mesh(OpticsScene.testScene().mesh(), wireframe = false)
    background("#d8dde3")
}
```

## 5. lens_camera

<!-- field-box id="ec_lens_camera" index="4" -->

```edn [field-box id="ec_lens_camera" role="properties"]
{"id" "ec_lens_camera" "index" 4 "name" "lens_camera" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "lens_camera" "_tombstone" false "frame" #afield/rect [300.0 380.0 170.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ec_lens_camera_code_.kt" "lines" 36 "characters" 1186 "sha256" "addb11a818e8fb2c3e119b8793887f56e54bef8170a551fb664f87ae4de9e75e"}]}
```

```kotlin [field-property box="ec_lens_camera" name="code" path="ec_lens_camera_code_.kt"]
import afield.simulation.optics.*

// The test scene through a lens. Choose the distance to focus at and the lens puts its sensor
// where that distance comes out sharp; nearer and further things blur.
val scene = OpticsScene.testScene()
var focus = 4.0
var focal = 1.0
var aperture = 0.5

fun draw() {
    val sensor = ThinLens.imageDistance(focal, focus)
    val camera = SensorCamera(z = -4.5, sensorDistance = sensor, apertureDiameter = aperture, focalLength = focal)
    val image = camera.render(scene, 300, 200, samples = 64)
    // Smoothed when scaled: a picture, not a grid of detectors.
    display("sensor", image.html(width = 600))
    markdown(
        "readout",
        "f/${(focal / aperture).f1}, sensor ${sensor.f3} behind the lens, sharp at ${focus.f1} in front",
    )
}

draw()
controlGroup {
    slider("focus distance", 2.0, 12.0, step = 0.05, initialValue = 4.0, law = ControlLaw.LOGARITHMIC) {
        focus = it
        draw()
    }
    slider("aperture", 0.02, 0.8, step = 0.01, initialValue = 0.5) {
        aperture = it
        draw()
    }
    slider("focal length", 0.7, 1.6, step = 0.01, initialValue = 1.0) {
        focal = it
        draw()
    }
}
```

## 6. depth_of_field

<!-- field-box id="ec_depth_of_field" index="5" -->

```edn [field-box id="ec_depth_of_field" role="properties"]
{"id" "ec_depth_of_field" "index" 5 "name" "depth_of_field" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "depth_of_field" "_tombstone" false "frame" #afield/rect [680.0 380.0 170.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ec_depth_of_field_code_.kt" "lines" 91 "characters" 2921 "sha256" "3a771f94eea094d24ebd0b264bb59979e484bcd154c693d7b18718383cb8c43f"}]}
```

```kotlin [field-property box="ec_depth_of_field" name="code" path="ec_depth_of_field_code_.kt"]
import afield.simulation.optics.*

// A lens focused at one distance. Points between the near and far limits make blur circles
// smaller than a pixel (here 0.04), so everything in the shaded band looks sharp.
val unit = 60.0
val lensX = 380.0
val axis = 150.0
val pixel = 0.04
val cyan = vec(0.4, 0.9, 1.0, 1.0)
val orange = vec(1.0, 0.6, 0.2, 1.0)
val guide = vec(1.0, 1.0, 1.0, 0.35)
var aperture = 1.0
var focal = 1.0
var focus = 3.0

fun cone(objectDistance: Double, sensorX: Double, c: Vec4, width: Double) =
    listOf(-1.0, 1.0).map { side ->
        val image = ThinLens.imageDistance(focal, objectDistance)
        val edge = side * aperture / 2 * unit
        fline {
            moveTo(lensX - objectDistance * unit, axis)
            lineTo(lensX, axis + edge)
            // Towards the image point, stopping at the sensor.
            lineTo(sensorX, axis + edge * (1 - (sensorX - lensX) / (image * unit)))
            color = c
            thickness = width
        }
    }

fun draw() {
    val sensor = ThinLens.imageDistance(focal, focus)
    val sensorX = lensX + sensor * unit
    val (near, far) = ThinLens.depthOfField(focal, aperture, focus, pixel).let { it[0] to it[1] }
    val farX = if (far.isInfinite()) 0.0 else max(0.0, lensX - far * unit)
    val parts = mutableListOf(
        fline {
            rect(farX, axis - 70, lensX - near * unit - farX, 140)
            filled = true
            stroked = false
            color = vec(0.4, 0.9, 1.0, 0.12)
        },
        fline {
            moveTo(0, axis)
            lineTo(640, axis)
            color = guide
            thickness = 1.0
        },
        fline {
            moveTo(lensX, axis - aperture / 2 * unit)
            lineTo(lensX, axis + aperture / 2 * unit)
            color = cyan
            thickness = 6.0
        },
        fline {
            moveTo(sensorX, axis - 60)
            lineTo(sensorX, axis + 60)
            color = guide
            thickness = 3.0
        },
    )
    parts += cone(focus, sensorX, cyan, 1.5)
    parts += cone(near, sensorX, orange, 1.0)
    if (!far.isInfinite() && far * unit < lensX) parts += cone(far, sensorX, orange, 1.0)
    showPlane(
        parts,
        channel = "side",
        width = 600,
        height = 281,
        padding = 0.0,
        window = Rect(0.0, 0.0, 640.0, 300.0),
        interactive = false,
    )
    val farText = if (far.isInfinite()) "to infinity" else "to ${far.f2}"
    markdown("readout", "f/${(focal / aperture).f1}, focused at ${focus.f2}: sharp from ${near.f2} $farText")
}

draw()
controlGroup {
    slider("aperture", 0.05, 2.0, step = 0.01, initialValue = 1.0) {
        aperture = it
        draw()
    }
    slider("focal length", 0.6, 1.6, step = 0.01, initialValue = 1.0) {
        focal = it
        draw()
    }
    slider("focus distance", 1.8, 6.0, step = 0.01, initialValue = 3.0) {
        focus = it
        draw()
    }
}
```

## 7. deck

<!-- field-box id="ec_deck" index="6" -->

```edn [field-box id="ec_deck" role="properties"]
{"id" "ec_deck" "index" 6 "name" "deck" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "deck" "_tombstone" false "frame" #afield/rect [60.0 140.0 150.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ec_deck_code_.kt" "lines" 446 "characters" 19668 "sha256" "a52543dbd665c0030f05f85e6c61c57b0c3ef3535dc03dac740e24023a665153"}]}
```

```kotlin [field-property box="ec_deck" name="code" path="ec_deck_code_.kt"]
import kotlinx.html.*

// Cameras and lenses, in six sections. Each figure is a box of the same name to the right.
presentation(title = "Cameras and lenses") {
        section("Recording light") {
            step("Cameras and lenses", id = "opening") {
                audience {
                    +"""
                # Cameras and lenses

                A photograph is a count of light. This talk builds a camera up from that
                count: a grid of detectors, a box with a hole in it, then glass, shaped and
                stacked until the picture is sharp, bright and in focus where we choose.

                Every picture you will see is computed live, ray by ray.
            """
                        .md
                    p(classes = "muted") {
                        +"After “Cameras and Lenses” by Bartosz Ciechanowski, ciechanow.ski/cameras-and-lenses. Words and figures made in Field."
                    }
                }
                markdownNote("The question to hold: what does each part of a camera do to the light? [[next]]")
            }
            step("Counting light", id = "photons") {
                audience {
                    figureBeside("photon_grid") {
                        +"""
                    ## Counting light

                    Each detector counts the light that reaches it while the shutter is
                    open. Too little and the count is grain; too much and it fills to
                    white.
                """
                            .md
                    }
                }
                control("photon_grid", "collection time", 0.05)
                cue("Too short") { control("photon_grid", "collection time", 0.003, glideMs = 1500) }
                cue("Too long") { control("photon_grid", "collection time", 0.4, glideMs = 2000) }
                markdownNote(
                    "Next shortens the exposure until the grain shows, then lengthens it until the sky burns out. [[back]] [[next]]",
                )
            }
            step("Colour from filters", id = "colour") {
                audience {
                    figureBeside("kilopixel") {
                        +"""
                    ## Colour from filters

                    A filter over each detector lets it count only red, green or blue. The
                    two colours a pixel missed are guessed from its neighbours.
                """
                            .md
                    }
                }
                control("kilopixel", "colour filters", false)
                control("kilopixel", "fill in colours", false)
                cue("Filters") { control("kilopixel", "colour filters", true) }
                cue("Fill in") { control("kilopixel", "fill in colours", true) }
                markdownNote(
                    "Next puts the filters on (a mosaic), then fills in the missing colours. [[back]] [[next]]",
                )
            }
            step("A scene to photograph", id = "scene") {
                audience {
                    figureBeside("test_scene") {
                        +"""
                    ## A scene to photograph

                    Every camera in this talk looks at this. Drag to look around it.
                """
                            .md
                    }
                }
                markdownNote("Invite someone to drag the scene round. [[back]] [[next]]")
            }
            step("A bare sensor", id = "bare-sensor") {
                audience {
                    figureBeside("bare_sensor") {
                        +"""
                    ## A bare sensor sees nothing

                    Every detector gets light from every point of the scene, so every
                    count is the same blend.
                """
                            .md
                    }
                }
                markdownNote(
                    "The lines run from one point on each ball to every detector. Drag to look. [[back]] [[next]]",
                )
            }
            step("A box with a hole", id = "pinhole") {
                audience {
                    figureBeside("pinhole_box") {
                        +"""
                    ## A box with a hole

                    Through a small hole each detector sees one patch of the scene. A
                    picture forms on the back wall, upside down.
                """
                            .md
                    }
                }
                control("pinhole_box", "hole diameter", 0.04)
                control("pinhole_box", "sensor distance", 1.0)
                control("pinhole_box", "fixed exposure", false)
                markdownNote(
                    "Top: the box and the picture on its back wall. Bottom: that picture turned upright. [[back]] [[next]]",
                )
            }
            step("How much it sees", id = "field-of-view") {
                audience {
                    figureBeside("field_of_view") {
                        +"""
                    ## How much it sees

                    Rays cross at the hole. A sensor further back takes a narrower view,
                    drawn larger.
                """
                            .md
                    }
                }
                control("field_of_view", "sensor distance", 1.0)
                cue("Further back") { control("field_of_view", "sensor distance", 2.2, glideMs = 2000) }
                markdownNote("Next moves the sensor back: the view narrows and the image grows. [[back]] [[next]]")
            }
            step("Darker at the edges", id = "falloff") {
                audience {
                    figureBeside("off_axis") {
                        +"""
                    ## Darker at the edges

                    From the edge of the sensor the hole is further away and seen at a
                    slant: the light falls as cos⁴ of the angle.
                """
                            .md
                    }
                }
                control("off_axis", "sensor distance", 0.8)
                cue("Closer") { control("off_axis", "sensor distance", 0.35, glideMs = 1500) }
                markdownNote(
                    "Next brings the sensor close to the hole, so the edges look out at steeper angles. [[back]] [[next]]",
                )
            }
            step("Sharp or bright", id = "hole-size") {
                audience {
                    figureBeside("pinhole_box") {
                        +"""
                    ## Sharp or bright, not both

                    Each point lights a patch the size of the hole. A smaller hole is
                    sharper, and darker.
                """
                            .md
                    }
                }
                control("pinhole_box", "hole diameter", 0.12)
                control("pinhole_box", "sensor distance", 1.0)
                control("pinhole_box", "fixed exposure", true)
                cue("A smaller hole") { control("pinhole_box", "hole diameter", 0.015, glideMs = 2000) }
                markdownNote(
                    "Exposure is now fixed. Next shrinks the hole: sharper, and much darker. [[back]] [[next]]",
                )
            }
        }
        section("Glass") {
            step("Glass moves things", id = "glass") {
                audience {
                    figureBeside("slab_ray") {
                        +"""
                    ## Glass moves what you see

                    A ray bends going into glass and bends back coming out: it leaves
                    parallel, but shifted.
                """
                            .md
                    }
                }
                control("slab_ray", "thickness", 1.0)
                control("slab_ray", "angle", 45.0)
                cue("Thicker") { control("slab_ray", "thickness", 2.0, glideMs = 1500) }
                markdownNote("Next doubles the thickness, and the shift doubles. [[back]] [[next]]")
            }
        }
        section("Waves") {
            step("Light is a wave", id = "wave") {
                audience {
                    figureBeside("into_glass") {
                        +"""
                    ## Light is a wave

                    In glass the wave travels more slowly, so its crests crowd together.
                """
                            .md
                    }
                }
                control("into_glass", "playing", true)
                control("into_glass", "angle", 0.0)
                control("into_glass", "glass index", 1.5)
                markdownNote("The orange line is the way the wave travels. [[back]] [[next]]")
            }
            step("Why it turns", id = "turning") {
                audience {
                    figureBeside("into_glass") {
                        +"""
                    ## Why it turns

                    Arriving at a slant, each crest slows on one side first, and the wave
                    turns.
                """
                            .md
                    }
                }
                control("into_glass", "playing", true)
                control("into_glass", "angle", 0.0)
                control("into_glass", "glass index", 1.5)
                cue("At a slant") { control("into_glass", "angle", 40.0, glideMs = 2000) }
                markdownNote("Next tilts the wave so it meets the glass at 40°. [[back]] [[next]]")
            }
            step("Snell's law", id = "snell") {
                audience {
                    figureBeside("snell") {
                        +"""
                    ## How far it turns

                    Snell's law: n₁ sin θ₁ = n₂ sin θ₂. Some light always reflects, more
                    of it at grazing angles.
                """
                            .md
                    }
                }
                control("snell", "angle", 30.0)
                control("snell", "from inside the glass", false)
                cue("More grazing") { control("snell", "angle", 82.0, glideMs = 2000) }
                markdownNote("Next swings the ray towards grazing: watch the reflection thicken. [[back]] [[next]]")
            }
            step("Trapped light", id = "total-reflection") {
                audience {
                    figureBeside("snell") {
                        +"""
                    ## Trapped light

                    Leaving glass past about 42°, the light has no angle to take in air,
                    and all of it reflects.
                """
                            .md
                    }
                }
                control("snell", "angle", 30.0)
                control("snell", "from inside the glass", true)
                cue("Past the critical angle") { control("snell", "angle", 50.0, glideMs = 2000) }
                markdownNote("Now the light starts inside the glass. Next tilts it past 42°. [[back]] [[next]]")
            }
        }
        section("Lenses") {
            step("Tilted faces", id = "wedge") {
                audience {
                    figureBeside("prism_segments") {
                        +"""
                    ## Tilted faces steer light

                    A tilted face turns a ray. Steeper tilts further out, and the rays can
                    be made to meet.
                """
                            .md
                    }
                }
                control("prism_segments", "segments", 1.0)
                cue("More segments") { control("prism_segments", "segments", 24.0, glideMs = 2500) }
                markdownNote(
                    "One segment each side is two prisms. Next adds segments until the face is nearly a curve. [[back]] [[next]]",
                )
            }
            step("A thin lens", id = "thin-lens") {
                audience {
                    figureBeside("thin_lens") {
                        +"""
                    ## A lens brings rays together

                    A smooth curve is the limit of the steps. A point at o comes to focus
                    at i, where 1/o + 1/i = 1/f.
                """
                            .md
                    }
                }
                control("thin_lens", "source distance", 5.0)
                control("thin_lens", "focal length", 2.0)
                cue("Closer") { control("thin_lens", "source distance", 2.6, glideMs = 2000) }
                cue("Inside the focal length") { control("thin_lens", "source distance", 1.5, glideMs = 1500) }
                markdownNote(
                    "Next brings the source closer: the image moves away. Then inside f, where no image forms. [[back]] [[next]]",
                )
            }
            step("A camera with a lens", id = "lens-camera") {
                audience {
                    figureBeside("lens_camera") {
                        +"""
                    ## A camera with a lens

                    A lens bends every ray from a point to one place on the sensor: a wide
                    hole, and still sharp.
                """
                            .md
                    }
                }
                control("lens_camera", "focus distance", 4.0)
                control("lens_camera", "aperture", 0.5)
                control("lens_camera", "focal length", 1.0)
                markdownNote("A wide opening, f/2, and still sharp where it is focused. [[back]] [[next]]")
            }
            step("Out of focus", id = "focus") {
                audience {
                    figureBeside("lens_camera") {
                        +"""
                    ## Out of focus

                    Nearer or further, a point meets in front of the sensor or behind it,
                    and lands as a disc.
                """
                            .md
                    }
                }
                control("lens_camera", "focus distance", 4.0)
                control("lens_camera", "aperture", 0.5)
                control("lens_camera", "focal length", 1.0)
                cue("Focus further") { control("lens_camera", "focus distance", 9.0, glideMs = 2000) }
                markdownNote("Next refocuses from the red ball to the far end of the floor. [[back]] [[next]]")
            }
            step("The shape of blur", id = "aperture") {
                audience {
                    figureBeside("bokeh") {
                        +"""
                    ## The shape of blur

                    An out-of-focus light is a picture of the opening. Iris blades make it
                    a polygon.
                """
                            .md
                    }
                }
                control("bokeh", "aperture", 0.5)
                control("bokeh", "blades", 0.0)
                cue("Six blades") { control("bokeh", "blades", 6.0) }
                cue("Narrower") { control("bokeh", "aperture", 0.2, glideMs = 1500) }
                markdownNote("Next swaps the round opening for six blades, then closes them down. [[back]] [[next]]")
            }
            step("How deep is sharp", id = "depth-of-field") {
                audience {
                    figureBeside("depth_of_field") {
                        +"""
                    ## How deep is sharp

                    Blur smaller than a pixel looks sharp. A smaller opening widens that
                    band, and costs light.
                """
                            .md
                    }
                }
                control("depth_of_field", "aperture", 1.0)
                control("depth_of_field", "focal length", 1.0)
                control("depth_of_field", "focus distance", 3.0)
                cue("Stop down") { control("depth_of_field", "aperture", 0.25, glideMs = 2000) }
                markdownNote(
                    "The shaded band is what looks sharp. Next narrows the opening and the band widens. [[back]] [[next]]",
                )
            }
            step("f-numbers", id = "f-number") {
                audience {
                    figureBeside("f_stops") {
                        +"""
                    ## f-numbers

                    Focal length over opening. Each stop is √2 narrower and lets in half
                    the light.
                """
                            .md
                    }
                }
                control("f_stops", "stop", 0.0)
                cue("Stop by stop") { control("f_stops", "stop", 7.0, glideMs = 3000) }
                markdownNote("Next walks from f/1.4 to f/16, halving the light each step. [[back]] [[next]]")
            }
        }
        section("Aberrations") {
            step("Spheres miss", id = "spherical") {
                audience {
                    figureBeside("spherical_aberration") {
                        +"""
                    ## Spherical lenses miss

                    A spherical face bends rim rays too much: no one place is sharp for
                    all of them.
                """
                            .md
                    }
                }
                control("spherical_aberration", "lens radius", 20.0)
                cue("The whole lens") { control("spherical_aberration", "lens radius", 85.0, glideMs = 2000) }
                markdownNote(
                    "Near the middle the rays meet at one point. Next uses the lens out to its rim. [[back]] [[next]]",
                )
            }
            step("Colours split", id = "chromatic") {
                audience {
                    figureBeside("dispersion") {
                        +"""
                    ## Colours split

                    Glass bends blue more than red, so each colour has its own focus.
                """
                            .md
                    }
                }
                control("dispersion", "dispersion", 1.0)
                control("dispersion", "angle", 50.0)
                cue("More dispersion") { control("dispersion", "dispersion", 4.0, glideMs = 2000) }
                markdownNote("1 is ordinary crown glass. Next exaggerates the spread four times. [[back]] [[next]]")
            }
        }
        section("Further reading") {
            step("Further reading", id = "further-reading") {
                audience {
                    +"""
                # Further reading

                - Eugene Hecht, [*Optics*](https://archive.org/details/optics0000hech_e5d5): refraction,
                  lenses and aberrations, worked properly.
                - Richard Feynman, [*QED: The Strange Theory of Light and Matter*](https://archive.org/details/qedstrangetheory0000feyn_x8n8):
                  why light seems to take the quickest path.
                - Matt Pharr, Wenzel Jakob and Greg Humphreys, *Physically Based Rendering*,
                  [the chapter on cameras](https://pbr-book.org/4ed/Cameras_and_Film), free online:
                  pinholes, thin lenses and real lens systems in code.
                - Rudolf Kingslake, [*A History of the Photographic Lens*](https://archive.org/details/historyofphotogr0000king).
            """
                        .md
                    p(classes = "muted") {
                        +"After “Cameras and Lenses” by Bartosz Ciechanowski, https://ciechanow.ski/cameras-and-lenses/. Words and figures made in Field."
                    }
                }
                markdownNote("The end. [[back]] [[finish]]")
            }
        }
    }
    .arrangeBoxes()
    .present()
```

## 8. off_axis

<!-- field-box id="ec_off_axis" index="7" -->

```edn [field-box id="ec_off_axis" role="properties"]
{"id" "ec_off_axis" "index" 7 "name" "off_axis" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "off_axis" "_tombstone" false "frame" #afield/rect [680.0 220.0 170.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ec_off_axis_code_.kt" "lines" 68 "characters" 2066 "sha256" "be826c50182d9b4b0257183a8ed28422cf91678ae532b4b385bd7ae2eabaa89e"}]}
```

```kotlin [field-property box="ec_off_axis" name="code" path="ec_off_axis_code_.kt"]
import afield.media.image.util.PngEncoder
import afield.simulation.optics.*

// A pinhole camera looking at an even sky. Its picture is darker towards the edges: the curve is
// the brightness across the sensor, the faint line cos⁴ of the angle each point looks out at.
val cyan = vec(0.4, 0.9, 1.0, 1.0)
val guide = vec(1.0, 1.0, 1.0, 0.35)
var distance = 0.8

fun draw() {
    val camera = SensorCamera(
        y = 0.0,
        z = 0.0,
        lookY = 0.0,
        lookZ = 1.0,
        sensorWidth = 2.0,
        sensorHeight = 0.25,
        sensorDistance = distance,
        apertureDiameter = 0.01,
    )
    val image = camera.render(OpticsScene.uniformSky(), 160, 20, samples = 8)
    display("sensor", PngEncoder.encode(image.toArgb(scale = 4), 640, 80))
    val measured = fline {
        for (i in 0 until 160) {
            val y = 230 - 200 * image[i, 10, 0] / image.reference
            if (i == 0) moveTo(4.0 * i + 2, y) else lineTo(4.0 * i + 2, y)
        }
        color = cyan
        thickness = 3.0
    }
    val predicted = fline {
        for (i in 0 until 160) {
            val sx = ((i + 0.5) / 160 - 0.5) * 2.0
            val y = 230 - 200 * cos(atan(sx / distance)).pow(4)
            if (i == 0) moveTo(4.0 * i + 2, y) else lineTo(4.0 * i + 2, y)
        }
        color = guide
        thickness = 1.0
    }
    showPlane(
        listOf(
            fline {
                moveTo(0, 230)
                lineTo(640, 230)
                color = guide
                thickness = 1.0
            },
            measured,
            predicted,
        ),
        channel = "brightness",
        width = 600,
        height = 225,
        padding = 0.0,
        window = Rect(0.0, 0.0, 640.0, 240.0),
        interactive = false,
    )
    val edge = image[0, 10, 0] / image.reference
    markdown("readout", "the edge of the sensor gets ${(100 * edge).f0}% of the light the middle gets")
}

draw()
controlGroup {
    slider("sensor distance", 0.3, 2.0, step = 0.01, initialValue = 0.8) {
        distance = it
        draw()
    }
}
```

## 9. photon_grid

<!-- field-box id="ec_photon_grid" index="8" -->

```edn [field-box id="ec_photon_grid" role="properties"]
{"id" "ec_photon_grid" "index" 8 "name" "photon_grid" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "photon_grid" "_tombstone" false "frame" #afield/rect [300.0 140.0 170.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ec_photon_grid_code_.kt" "lines" 25 "characters" 1051 "sha256" "33c4c468681b1b873623f8aa5ed33bc134cec8fd4f8ba2434de1765fdb6a2ae0"}]}
```

```kotlin [field-property box="ec_photon_grid" name="code" path="ec_photon_grid_code_.kt"]
import afield.media.image.util.PngEncoder
import afield.simulation.optics.*

// A 48 × 32 sensor collecting photons from the test scene. Light arrives at random, so a short
// exposure is grainy; each detector holds at most 400, so a long one turns bright parts flat white.
val full = 400.0
val light =
    SensorCamera(z = -5.0, sensorDistance = 1.0, apertureDiameter = 0.03)
        .render(OpticsScene.testScene(), 48, 32, samples = 32)
val perSecond = 4000.0 / light.reference // photons per second from the bright sky at the middle

fun draw(time: Double) {
    val counts = light.photons(perSecond * time, seed = 3)
    display("sensor", PngEncoder.encode(counts.toArgb(exposure = 1 / full, scale = 10), 480, 320))
    val sky = 4000.0 * time
    markdown(
        "readout",
        "open ${time.f3} s: about ${sky.f0} photons on a detector facing the sky, which holds ${full.toInt()}",
    )
}

draw(0.05)
controlGroup {
    slider("collection time", 0.001, 0.5, step = 0.001, initialValue = 0.05, law = ControlLaw.LOGARITHMIC) { draw(it) }
}
```

## 10. snell

<!-- field-box id="ec_snell" index="9" -->

```edn [field-box id="ec_snell" role="properties"]
{"id" "ec_snell" "index" 9 "name" "snell" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "snell" "_tombstone" false "frame" #afield/rect [490.0 300.0 170.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ec_snell_code_.kt" "lines" 70 "characters" 2200 "sha256" "5c306826af6856d6ea5bdafd82458541eeb9cb2c87e823436bf702871e018f2d"}]}
```

```kotlin [field-property box="ec_snell" name="code" path="ec_snell_code_.kt"]
import afield.simulation.optics.*

// A ray meets glass (index 1.5). Part of it turns as it crosses, part reflects;
// the line widths show how the light divides.
val glass = 1.5
val cyan = vec(0.4, 0.9, 1.0, 1.0)
val orange = vec(1.0, 0.6, 0.2, 1.0)
var angle = 40.0
var inside = false

fun ray(x1: Double, y1: Double, share: Double, c: Vec4) = fline {
    moveTo(320.0, 180.0)
    lineTo(x1, y1)
    color = vec(c.x, c.y, c.z, 0.25 + 0.75 * share)
    thickness = 0.8 + 5.0 * share
}

fun draw() {
    val theta = angle * PI / 180
    val (n1, n2) = if (inside) glass to 1.0 else 1.0 to glass
    val side = if (inside) 1.0 else -1.0 // the incoming ray's side of the surface: -1 above, +1 below
    val turned = Refraction.snellAngle(theta, n1, n2)
    val reflected = Refraction.reflectance(theta, n1, n2)
    val parts = mutableListOf(
        fline {
            rect(0, 180, 640, 180)
            filled = true
            stroked = false
            color = vec(0.4, 0.9, 1.0, 0.12)
        },
        fline {
            moveTo(320, 10)
            lineTo(320, 350)
            color = vec(1.0, 1.0, 1.0, 0.35)
            thickness = 1.0
        },
        fline {
            moveTo(320 - 200 * sin(theta), 180 + side * 200 * cos(theta))
            lineTo(320, 180)
            color = cyan
            thickness = 5.8
        },
        ray(320 + 200 * sin(theta), 180 + side * 200 * cos(theta), reflected, orange),
    )
    if (!turned.isNaN()) parts += ray(320 + 200 * sin(turned), 180 - side * 200 * cos(turned), 1 - reflected, cyan)
    showPlane(
        parts,
        channel = "rays",
        width = 600,
        height = 338,
        padding = 0.0,
        window = Rect(0.0, 0.0, 640.0, 360.0),
        interactive = false,
    )
    val crossing =
        if (turned.isNaN()) "none crosses: total internal reflection" else "crosses at ${(turned * 180 / PI).f1}°"
    markdown("readout", "arrives at ${angle.f1}°, ${(100 * reflected).f0}% reflects, $crossing")
}

draw()
controlGroup {
    slider("angle", 0.0, 89.0, step = 0.5, initialValue = 40.0) {
        angle = it
        draw()
    }
    checkbox("from inside the glass") {
        inside = it
        draw()
    }
}
```

## 11. spherical_aberration

<!-- field-box id="ec_spherical_aberration" index="10" -->

```edn [field-box id="ec_spherical_aberration" role="properties"]
{"id" "ec_spherical_aberration" "index" 10 "name" "spherical_aberration" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "spherical_aberration" "_tombstone" false "frame" #afield/rect [300.0 460.0 170.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ec_spherical_aberration_code_.kt" "lines" 76 "characters" 2228 "sha256" "7fde7580f4fee08b1b93d14f4623fc52f067b8b7cf92bf45ca4e555fcbe64288"}]}
```

```kotlin [field-property box="ec_spherical_aberration" name="code" path="ec_spherical_aberration_code_.kt"]
import afield.simulation.optics.*

// Parallel rays through a lens with a spherical back face, traced exactly with Snell's law.
// Rays near the rim (orange) cross the axis before those near the middle (cyan).
val glass = 1.5
val axis = 180.0
val flatX = 140.0
val radius = 130.0
val cyan = vec(0.4, 0.9, 1.0, 1.0)
val guide = vec(1.0, 1.0, 1.0, 0.35)
var opening = 40.0

fun draw() {
    val sag = radius - sqrt(radius * radius - 90.0 * 90.0)
    val centreX = flatX + sag + 6 - radius // centre of the spherical face
    val edge = opening + 6
    val outline = fline {
        moveTo(flatX, axis - edge)
        for (k in 0..60) {
            val h = -edge + 2 * edge * k / 60
            lineTo(centreX + sqrt(radius * radius - h * h), axis + h)
        }
        lineTo(flatX, axis + edge)
        close()
        filled = true
        color = vec(0.4, 0.9, 1.0, 0.18)
    }
    val paraxial = centreX + radius + radius / (glass - 1)
    val parts = mutableListOf(
        outline,
        fline {
            moveTo(0, axis)
            lineTo(640, axis)
            color = guide
            thickness = 1.0
        },
        fline {
            moveTo(paraxial, axis - 8)
            lineTo(paraxial, axis + 8)
            color = guide
            thickness = 2.0
        },
    )
    for (k in -8..8) {
        if (k == 0) continue
        val h = opening * k / 8
        val x = centreX + sqrt(radius * radius - h * h)
        val out = Refraction.refract(1.0, 0.0, (x - centreX) / radius, h / radius, glass, 1.0) ?: continue
        val t = abs(h) / 85.0
        val end = frameExit(x, axis + h, out[0], out[1], 640.0, 360.0)
        parts += fline {
            moveTo(0.0, axis + h)
            lineTo(x, axis + h)
            lineTo(end[0], end[1])
            color = vec(0.4 + 0.6 * t, 0.9 - 0.3 * t, 1.0 - 0.8 * t, 1.0)
            thickness = 1.3
        }
    }
    showPlane(
        parts,
        channel = "side",
        width = 600,
        height = 338,
        padding = 0.0,
        window = Rect(0.0, 0.0, 640.0, 360.0),
        interactive = false,
    )
}

draw()
controlGroup {
    slider("lens radius", 5.0, 85.0, step = 1.0, initialValue = 40.0) {
        opening = it
        draw()
    }
}
```

## 12. prism_segments

<!-- field-box id="ec_prism_segments" index="11" -->

```edn [field-box id="ec_prism_segments" role="properties"]
{"id" "ec_prism_segments" "index" 11 "name" "prism_segments" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "prism_segments" "_tombstone" false "frame" #afield/rect [680.0 300.0 170.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ec_prism_segments_code_.kt" "lines" 77 "characters" 2476 "sha256" "0853e1496c4dda97411d29cf1575bbbf4c65977aae66936293c297f41f08d25e"}]}
```

```kotlin [field-property box="ec_prism_segments" name="code" path="ec_prism_segments_code_.kt"]
import afield.simulation.optics.*

// Parallel rays through glass whose back face is made of flat segments. One segment each side is a
// pair of prisms: the rays turn but stay parallel. More segments, closer to a curve, and they meet.
val glass = 1.5
val axis = 180.0
val flatX = 220.0
val radius = 300.0
val rim = 130.0
val cyan = vec(0.4, 0.9, 1.0, 1.0)
val guide = vec(1.0, 1.0, 1.0, 0.35)
var segments = 1

// The curved face these segments approximate, a circle that is `rim` above the axis at its edge.
fun surfaceX(h: Double) = flatX + 10.0 + sqrt(radius * radius - h * h) - sqrt(radius * radius - rim * rim)

fun draw() {
    val n = segments
    // The facets' corners from the rim at the top to the rim at the bottom.
    val corners =
        (0..2 * n).map { k ->
            val h = -rim + rim * k / n
            h to surfaceX(h)
        }
    val outline = fline {
        moveTo(flatX, axis - rim)
        for ((h, x) in corners) lineTo(x, axis + h)
        lineTo(flatX, axis + rim)
        close()
        filled = true
        color = vec(0.4, 0.9, 1.0, 0.18)
    }
    val parts = mutableListOf(
        outline,
        fline {
            moveTo(0, axis)
            lineTo(640, axis)
            color = guide
            thickness = 1.0
        },
    )
    for (k in -6..6) {
        val h = 20.0 * k + if (k == 0) 0.0 else -0.5 * sign(k.toDouble()) // keep rays off the facet corners
        val facet = ((h + rim) / rim * n).toInt().coerceIn(0, 2 * n - 1)
        val (h0, x0) = corners[facet]
        val (h1, x1) = corners[facet + 1]
        val x = x0 + (x1 - x0) * (h - h0) / (h1 - h0)
        // Outward normal of the facet, then Snell's law leaving the glass.
        val len = sqrt((x1 - x0).pow(2) + (h1 - h0).pow(2))
        val out = Refraction.refract(1.0, 0.0, (h1 - h0) / len, -(x1 - x0) / len, glass, 1.0) ?: continue
        val end = frameExit(x, axis + h, out[0], out[1], 640.0, 360.0)
        parts += fline {
            moveTo(0.0, axis + h)
            lineTo(x, axis + h)
            lineTo(end[0], end[1])
            color = cyan
            thickness = 1.5
        }
    }
    showPlane(
        parts,
        channel = "side",
        width = 600,
        height = 338,
        padding = 0.0,
        window = Rect(0.0, 0.0, 640.0, 360.0),
        interactive = false,
    )
}

draw()
controlGroup {
    slider("segments", 1.0, 24.0, step = 1.0, initialValue = 1.0) {
        segments = it.toInt()
        draw()
    }
}
```

## 13. f_stops

<!-- field-box id="ec_f_stops" index="12" -->

### Narrative

From `code`

The opening at each full f-stop for one lens, drawn to scale. Each is √2 narrower than the
last, so it has half the area and lets in half the light.

```edn [field-box id="ec_f_stops" role="properties"]
{"id" "ec_f_stops" "index" 12 "name" "f_stops" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "f_stops" "_tombstone" false "frame" #afield/rect [870.0 380.0 170.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ec_f_stops_code_.kt" "lines" 46 "characters" 1490 "sha256" "5335f8d7f9b4eb97400a133a440f8472a88381950df9d5c901125a78f46fd9bb"}]}
```

```kotlin [field-property box="ec_f_stops" name="code" path="ec_f_stops_code_.kt"]
// The opening at each full f-stop for one lens, drawn to scale. Each is √2 narrower than the
// last, so it has half the area and lets in half the light.
val stops = ["1.4", "2", "2.8", "4", "5.6", "8", "11", "16"]
val cyan = vec(0.4, 0.9, 1.0, 1.0)
val orange = vec(1.0, 0.6, 0.2, 1.0)
var chosen = 2

fun draw() {
    val parts =
        stops.indices.map { k ->
            val r = 38.0 / sqrt(2.0).pow(k)
            fline {
                for (s in 0..48) {
                    val t = 2 * PI * s / 48
                    if (s == 0) moveTo(40 + 80 * k + r, 60 + 0.0) else lineTo(40 + 80 * k + r * cos(t), 60 + r * sin(t))
                }
                filled = true
                color = if (k == chosen) orange else cyan
            }
        }
    showPlane(
        parts,
        channel = "openings",
        width = 600,
        height = 112,
        padding = 0.0,
        window = Rect(0.0, 0.0, 640.0, 120.0),
        interactive = false,
    )
    val labels = stops.mapIndexed { k, s ->
        "<span style='display:inline-block;width:75px;text-align:center;${if (k == chosen) "color:#ff9933" else ""}'>f/$s</span>"
    }
    val share = "1/${1 shl chosen}"
    markdown(
        "readout",
        "<div style='width:600px'>${labels.joinToString("")}</div>\n\nf/${stops[chosen]} lets in $share of the light of f/1.4",
    )
}

draw()
controlGroup {
    slider("stop", 0.0, 7.0, step = 1.0, initialValue = 2.0) {
        chosen = it.toInt()
        draw()
    }
}
```

## 14. bokeh

<!-- field-box id="ec_bokeh" index="13" -->

```edn [field-box id="ec_bokeh" role="properties"]
{"id" "ec_bokeh" "index" 13 "name" "bokeh" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "bokeh" "_tombstone" false "frame" #afield/rect [490.0 380.0 170.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ec_bokeh_code_.kt" "lines" 53 "characters" 1474 "sha256" "e35315be4d0b5475447c8cb4fbf1e53757707c47c036e3b62b7758fe7c0efa58"}]}
```

```kotlin [field-property box="ec_bokeh" name="code" path="ec_bokeh_code_.kt"]
import afield.simulation.optics.*

// Small lights far behind the point the lens is focused on. Each one spreads into a copy of the
// opening: a disc for a round one, a polygon for an iris of blades.
val lights = OpticsScene(
    12 { k ->
        val hue = Refraction.wavelengthColor(450.0 + 20.0 * k)
        OpticsSphere(
            -3.6 + 0.65 * k,
            0.6 + 1.5 * sin(k * 2.4),
            7.0 + 1.5 * cos(k * 1.7),
            0.12,
            hue[0],
            hue[1],
            hue[2],
            emission = 12.0,
        )
    },
    floorY = null,
    sky = doubleArrayOf(0.0, 0.0, 0.0),
    sun = 0.0,
)
var aperture = 0.5
var blades = 0

fun draw() {
    val camera = SensorCamera(
        y = 0.6,
        z = -2.0,
        lookY = 0.6,
        lookZ = 6.0,
        sensorDistance = ThinLens.imageDistance(1.0, 3.0),
        apertureDiameter = aperture,
        focalLength = 1.0,
        blades = blades,
        bladeRotation = 0.3,
    )
    val image = camera.render(lights, 150, 100, samples = 400)
    // Low resolution keeps 400 rays a pixel quick; smoothed, the discs read as discs.
    display("sensor", image.html(exposure = 1.0 / camera.reference, width = 600))
}

draw()
controlGroup {
    slider("aperture", 0.05, 0.8, step = 0.01, initialValue = 0.5) {
        aperture = it
        draw()
    }
    slider("blades", 0.0, 9.0, step = 1.0, initialValue = 0.0) {
        blades = if (it < 3) 0 else it.toInt()
        draw()
    }
}
```

## 15. thin_lens

<!-- field-box id="ec_thin_lens" index="14" -->

```edn [field-box id="ec_thin_lens" role="properties"]
{"id" "ec_thin_lens" "index" 14 "name" "thin_lens" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "thin_lens" "_tombstone" false "frame" #afield/rect [870.0 300.0 170.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ec_thin_lens_code_.kt" "lines" 84 "characters" 2527 "sha256" "44a6484928c8edbe56a4420d00990f7514e429946f3e151c4b9e7d37a1e70582"}]}
```

```kotlin [field-property box="ec_thin_lens" name="code" path="ec_thin_lens_code_.kt"]
import afield.simulation.optics.*

// A thin lens bends each ray in proportion to how far from the middle it passes,
// so the rays from one point meet again on the other side.
val unit = 40.0
val axis = 180.0
val lensX = 320.0
val cyan = vec(0.4, 0.9, 1.0, 1.0)
val orange = vec(1.0, 0.6, 0.2, 1.0)
val guide = vec(1.0, 1.0, 1.0, 0.35)
var focal = 2.0
var source = 5.0

fun segment(x0: Double, y0: Double, x1: Double, y1: Double, c: Vec4, width: Double = 1.5) = fline {
    moveTo(x0, y0)
    lineTo(x1, y1)
    color = c
    thickness = width
}

fun dot(x: Double, y: Double, c: Vec4) = fline {
    moveTo(x, y)
    pointed = true
    pointSize = 2.0
    stroked = false
    color = c
}

fun draw() {
    val lens = fline {
        moveTo(lensX, axis - 110)
        for (k in 0..40) {
            val t = -1 + k / 20.0
            lineTo(lensX + 9 * (1 - t * t), axis + 110 * t)
        }
        for (k in 0..40) {
            val t = 1 - k / 20.0
            lineTo(lensX - 9 * (1 - t * t), axis + 110 * t)
        }
        filled = true
        color = vec(0.4, 0.9, 1.0, 0.25)
    }
    val sx = lensX - source * unit
    val sy = axis - 40.0
    val parts = mutableListOf(
        segment(0.0, axis, 640.0, axis, guide, 1.0),
        lens,
        dot(lensX - focal * unit, axis, guide),
        dot(lensX + focal * unit, axis, guide),
        dot(sx, sy, orange),
    )
    for (k in -4..4) {
        val h = 24.0 * k
        val slope = (axis + h - sy) / (lensX - sx) - h / (focal * unit)
        parts += segment(sx, sy, lensX, axis + h, cyan)
        val end = frameExit(lensX, axis + h, 1.0, slope, 640.0, 360.0)
        parts += segment(lensX, axis + h, end[0], end[1], cyan)
    }
    val image = ThinLens.imageDistance(focal, source)
    if (image > 0 && image * unit < 320) parts += dot(lensX + image * unit, axis + 40.0 * image / source, orange)
    showPlane(
        parts,
        channel = "rays",
        width = 600,
        height = 338,
        padding = 0.0,
        window = Rect(0.0, 0.0, 640.0, 360.0),
        interactive = false,
    )
    val where = if (image > 0) "a sharp image ${image.f2} behind the lens" else "no image: the rays spread apart"
    markdown("readout", "focal length ${focal.f1}, source ${source.f1} in front: $where")
}

draw()
controlGroup {
    slider("source distance", 1.0, 7.5, step = 0.05, initialValue = 5.0) {
        source = it
        draw()
    }
    slider("focal length", 1.0, 3.0, step = 0.05, initialValue = 2.0) {
        focal = it
        draw()
    }
}
```

## 16. field_of_view

<!-- field-box id="ec_field_of_view" index="15" -->

```edn [field-box id="ec_field_of_view" role="properties"]
{"id" "ec_field_of_view" "index" 15 "name" "field_of_view" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "field_of_view" "_tombstone" false "frame" #afield/rect [490.0 220.0 170.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ec_field_of_view_code_.kt" "lines" 75 "characters" 2603 "sha256" "efcedd54235af45db701484378be344a294e65263399df8322835f85b13e13cd"}]}
```

```kotlin [field-property box="ec_field_of_view" name="code" path="ec_field_of_view_code_.kt"]
import afield.simulation.optics.*

// A pinhole camera from the side. Rays cross at the hole, so the picture is upside down;
// the sensor's edges and its distance from the hole set how wide a view it takes.
val unit = 80.0
val holeX = 400.0
val axis = 150.0
val cyan = vec(0.4, 0.9, 1.0, 1.0)
val orange = vec(1.0, 0.6, 0.2, 1.0)
val guide = vec(1.0, 1.0, 1.0, 0.35)
var distance = 1.0

fun segment(x0: Double, y0: Double, x1: Double, y1: Double, c: Vec4, width: Double = 1.5) = fline {
    moveTo(x0, y0)
    lineTo(x1, y1)
    color = c
    thickness = width
}

fun draw() {
    val sensorX = holeX + distance * unit
    val half = 0.5 * unit
    // The edge rays, from each end of the sensor out through the hole.
    // Where the edge rays leave the picture.
    val low = frameExit(holeX, axis, -distance * unit, half, 640.0, 300.0)
    val high = frameExit(holeX, axis, -distance * unit, -half, 640.0, 300.0)
    val view = fline {
        moveTo(holeX, axis)
        lineTo(low[0], low[1])
        if (low[1] >= 300.0 || high[1] <= 0.0) {
            lineTo(0.0, 300.0)
            lineTo(0.0, 0.0)
        }
        lineTo(high[0], high[1])
        close()
        filled = true
        stroked = false
        color = vec(0.4, 0.9, 1.0, 0.12)
    }
    val tipX = 120.0
    val tipY = axis - 90.0
    val imageY = axis + 90.0 * distance * unit / (holeX - tipX)
    val parts = listOf(
        view,
        segment(sensorX, axis + half, holeX, axis + half, guide),
        segment(sensorX, axis - half, holeX, axis - half, guide),
        segment(holeX, axis - half, holeX, axis - 6, guide, 2.0),
        segment(holeX, axis + 6, holeX, axis + half, guide, 2.0),
        segment(sensorX, axis - half, sensorX, axis + half, cyan, 3.0),
        segment(sensorX, axis - half, low[0], low[1], cyan, 1.0),
        segment(sensorX, axis + half, high[0], high[1], cyan, 1.0),
        segment(tipX, axis + 40, tipX, tipY, orange, 4.0),
        segment(tipX, tipY, sensorX, imageY, orange, 1.0),
        segment(sensorX, axis - 40.0 * distance * unit / (holeX - tipX), sensorX, imageY, orange, 4.0),
    )
    showPlane(
        parts,
        channel = "side",
        width = 600,
        height = 281,
        padding = 0.0,
        window = Rect(0.0, 0.0, 640.0, 300.0),
        interactive = false,
    )
    val degrees = 2 * atan(0.5 / distance) * 180 / PI
    markdown("readout", "sensor ${distance.f2} behind the hole: a view ${degrees.f0}° wide")
}

draw()
controlGroup {
    slider("sensor distance", 0.3, 2.4, step = 0.02, initialValue = 1.0) {
        distance = it
        draw()
    }
}
```

## 17. into_glass

<!-- field-box id="ec_into_glass" index="16" -->

```edn [field-box id="ec_into_glass" role="properties"]
{"id" "ec_into_glass" "index" 16 "name" "into_glass" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "into_glass" "_tombstone" false "frame" #afield/rect [300.0 300.0 170.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ec_into_glass_code_.kt" "lines" 77 "characters" 2284 "sha256" "607aa88077d5187bbcff7a14b9b728b08e2d0ff94b1e73342c394a0247daf8cc"}]}
```

```kotlin [field-property box="ec_into_glass" name="code" path="ec_into_glass_code_.kt"]
import afield.simulation.optics.*

// The crests of a light wave arriving at glass. In glass the wave is slower, so the crests bunch up;
// arriving at a slant, each crest bends where it crosses, and the wave turns.
val axis = 180.0
val wavelength = 44.0
val cyan = vec(0.4, 0.9, 1.0, 1.0)
val orange = vec(1.0, 0.6, 0.2, 1.0)
var angle = 0.0
var index = 1.5
var playing = true
var travelled = 0.0

fun draw() {
    val theta = angle * PI / 180
    val turned = Refraction.snellAngle(theta, 1.0, index)
    val parts = mutableListOf(
        fline {
            rect(0, axis, 640, 180)
            filled = true
            stroked = false
            color = vec(0.4, 0.9, 1.0, 0.12)
        },
    )
    for (crest in Refraction.crossingCrests(theta, index, wavelength, travelled, 640.0, 360.0, axis)) {
        for (segment in listOfNotNull(crest.air, crest.glass)) parts += fline {
            moveTo(segment[0], segment[1])
            lineTo(segment[2], segment[3])
            color = cyan
            thickness = 2.0
        }
    }
    // Which way the wave is going, in air and in glass.
    parts += fline {
        moveTo(320 - 150 * sin(theta), axis - 150 * cos(theta))
        lineTo(320.0, axis)
        lineTo(320 + 150 * sin(turned), axis + 150 * cos(turned))
        color = orange
        thickness = 2.0
    }
    showPlane(
        parts,
        channel = "wave",
        width = 600,
        height = 338,
        padding = 0.0,
        window = Rect(0.0, 0.0, 640.0, 360.0),
        interactive = false,
    )
}

draw()
controlGroup {
    checkbox("playing", initialValue = true) { playing = it }
    slider("angle", 0.0, 70.0, step = 0.5, initialValue = 0.0) {
        angle = it
        draw()
    }
    slider("glass index", 1.0, 2.4, step = 0.01, initialValue = 1.5) {
        index = it
        draw()
    }
}

// Paced by the clock, not by the loop, which runs far faster than the display: the wave moves
// 25 pixels a second, a wavelength in under two seconds, and is redrawn thirty times a second.
var last = AnimationClock.nowSeconds()
var drawnAt = 0.0
loop {
    val now = AnimationClock.nowSeconds()
    if (playing) travelled += 25.0 * (now - last)
    last = now
    if (now - drawnAt >= 1.0 / 30) {
        drawnAt = now
        draw()
    }
}
```

## 18. dispersion

<!-- field-box id="ec_dispersion" index="17" -->

```edn [field-box id="ec_dispersion" role="properties"]
{"id" "ec_dispersion" "index" 17 "name" "dispersion" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "dispersion" "_tombstone" false "frame" #afield/rect [490.0 460.0 170.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ec_dispersion_code_.kt" "lines" 135 "characters" 4122 "sha256" "238d4e8192257c06900b81fc30877011dde0372090d6a9a55d4a96cea2a1c439"}]}
```

```kotlin [field-property box="ec_dispersion" name="code" path="ec_dispersion_code_.kt"]
import afield.simulation.optics.*

// White light is every colour at once, and glass bends blue more than red. Through a prism the
// colours fan out; through a lens each colour comes to its own focus.
val cyan = vec(0.4, 0.9, 1.0, 1.0)
var spread = 2.0
var angle = 50.0
val waves = [410.0, 450.0, 490.0, 530.0, 570.0, 610.0, 650.0, 690.0]

fun index(nm: Double) = Refraction.cauchyIndex(nm, b = 4200.0 * spread)
fun colour(nm: Double) = Refraction.wavelengthColor(nm).let { vec(it[0], it[1], it[2], 0.9) }

// Distance along the ray (px, py) + t (dx, dy) to the segment a-b, or null.
fun hit(px: Double, py: Double, dx: Double, dy: Double, a: DoubleArray, b: DoubleArray): Double? {
    val ex = b[0] - a[0]
    val ey = b[1] - a[1]
    val det = dx * -ey + ex * dy
    if (abs(det) < 1e-12) return null
    val t = ((a[0] - px) * -ey + ex * (a[1] - py)) / det
    val u = (dx * (a[1] - py) - dy * (a[0] - px)) / det
    return if (t > 1e-6 && u in 0.0..1.0) t else null
}

fun prism() {
    val a = doubleArrayOf(320.0, 50.0)
    val b = doubleArrayOf(190.0, 275.0)
    val c = doubleArrayOf(450.0, 275.0)
    val parts = mutableListOf(
        fline {
            moveTo(a[0], a[1])
            lineTo(b[0], b[1])
            lineTo(c[0], c[1])
            close()
            filled = true
            color = vec(0.4, 0.9, 1.0, 0.15)
        },
    )
    val phi = (30.0 - angle) * PI / 180
    val dx = cos(phi)
    val dy = sin(phi)
    val px = (a[0] + b[0]) / 2
    val py = (a[1] + b[1]) / 2
    val from = frameExit(px, py, -dx, -dy, 640.0, 320.0)
    parts += fline {
        moveTo(from[0], from[1])
        lineTo(px, py)
        color = vec(1.0, 1.0, 1.0, 1.0)
        thickness = 3.0
    }
    for (nm in waves) {
        val n = index(nm)
        val inside = Refraction.refract(dx, dy, 0.866, 0.5, 1.0, n) ?: continue
        val faces = [a to c, b to c]
        val (t, face) =
            faces
                .mapNotNull { (p, q) -> hit(px, py, inside[0], inside[1], p, q)?.let { it to (p to q) } }
                .minByOrNull { it.first } ?: continue
        val qx = px + t * inside[0]
        val qy = py + t * inside[1]
        val ex = face.second[0] - face.first[0]
        val ey = face.second[1] - face.first[1]
        val len = sqrt(ex * ex + ey * ey)
        val out = Refraction.refract(inside[0], inside[1], ey / len, -ex / len, n, 1.0)
        parts += fline {
            moveTo(px, py)
            lineTo(qx, qy)
            if (out != null) frameExit(qx, qy, out[0], out[1], 640.0, 320.0).let { lineTo(it[0], it[1]) }
            color = colour(nm)
            thickness = 1.5
        }
    }
    showPlane(
        parts,
        channel = "prism",
        width = 600,
        height = 300,
        padding = 0.0,
        window = Rect(0.0, 0.0, 640.0, 320.0),
        interactive = false,
    )
}

fun lens() {
    val parts = mutableListOf(
        fline {
            moveTo(160, 20)
            lineTo(160, 220)
            color = vec(0.4, 0.9, 1.0, 0.5)
            thickness = 6.0
        },
        fline {
            moveTo(0, 120)
            lineTo(640, 120)
            color = vec(1.0, 1.0, 1.0, 0.35)
            thickness = 1.0
        },
    )
    val green = index(550.0)
    for (nm in [650.0, 550.0, 450.0]) {
        val focal = 300.0 * (green - 1) / (index(nm) - 1)
        for (h in [-90.0, -60.0, 60.0, 90.0]) parts += fline {
            moveTo(0.0, 120 + h)
            lineTo(160.0, 120 + h)
            frameExit(160.0, 120 + h, 1.0, -h / focal, 640.0, 240.0).let { lineTo(it[0], it[1]) }
            color = colour(nm)
            thickness = 1.3
        }
    }
    showPlane(
        parts,
        channel = "lens",
        width = 600,
        height = 225,
        padding = 0.0,
        window = Rect(0.0, 0.0, 640.0, 240.0),
        interactive = false,
    )
}

fun draw() {
    prism()
    lens()
}

draw()
controlGroup {
    slider("dispersion", 0.0, 4.0, step = 0.05, initialValue = 2.0) {
        spread = it
        draw()
    }
    slider("angle", 20.0, 80.0, step = 0.5, initialValue = 50.0) {
        angle = it
        draw()
    }
}
```

## 19. bare_sensor

<!-- field-box id="ec_bare_sensor" index="18" -->

```edn [field-box id="ec_bare_sensor" role="properties"]
{"id" "ec_bare_sensor" "index" 18 "name" "bare_sensor" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "bare_sensor" "_tombstone" false "frame" #afield/rect [870.0 140.0 170.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ec_bare_sensor_code_.kt" "lines" 25 "characters" 1049 "sha256" "4582cae58a295c75a11e3e6c727c7bc9229eb3350b89c0ba81516efea6ea5c9e"}]}
```

```kotlin [field-property box="ec_bare_sensor" name="code" path="ec_bare_sensor_code_.kt"]
import afield.simulation.optics.*

// A sensor held up to the scene with nothing in front of it. One point on each ball sends light to
// every detector, and so does every other point: each detector gets the same blend.
val scene = OpticsScene.testScene()
val bare = SensorCamera(z = -4.0)
val image = bare.renderBare(scene, 48, 32, samples = 256)
val corners = 5 { i -> 4 { j -> bare.toWorld(-0.6 + 0.3 * i, -0.36 + 0.24 * j, 0.0) } }.flatten()
fun fan(x: Double, y: Double, z: Double, c: Vec4) = corners.map { p ->
    fline {
        moveTo(x, y, z)
        lineTo(p[0], p[1], p[2])
        color = c
        thickness = 1.0
    }
}

three(channel = "rays", height = 320, width = 640) {
    mesh(scene.mesh(), wireframe = false)
    mesh(bare.sensorMesh(image), wireframe = false, unlit = true, cullBackFaces = false)
    lines(fan(-0.9, 1.4, -0.8, vec(1.0, 0.6, 0.2, 1.0)) + fan(1.0, 1.2, 1.3, vec(0.4, 0.9, 1.0, 1.0)))
    camera(yaw = 235.0, pitch = 18.0, distance = 1.5)
    background("#d8dde3")
}
display("sensor", image.html(width = 480))
```

