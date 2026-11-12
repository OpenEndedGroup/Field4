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
