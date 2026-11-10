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
