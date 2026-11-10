import kotlinx.html.*

/*
The talk. Each figure is a box of the same name in this document; the deck starts a figure
when its slide comes up and stops it when the talk moves on. Nothing sounds until someone
holds a key or ticks "sound".
*/

presentation(title = "Sound") {
        section("Sound") {
            step("Sound", id = "opening") {
                audience {
                    +"""
                    # Sound

                    Everything we hear is air pressing on our ears a little harder, then a little
                    softer, many times a second.

                    This talk builds that idea up from molecules: how air carries a push, how a
                    repeated push becomes a note, how notes combine, how strings make them, and how
                    sound spreads through a room.""".md
                    p(classes = "muted") {
                        +"After “Sound” by Bartosz Ciechanowski, ciechanow.ski/sound. Words and figures made in Field."
                    }
                }
                markdownNote(
                    "Every figure in this talk is a live box. Sound starts only when someone holds a key or ticks **sound**. [[next]]",
                )
            }
            step("Three keys", id = "keyboard") {
                audience {
                    figureBeside("keyboard") {
                        +"""
                        ## Three keys

                        Hold a key. Each moves the air in its own repeating pattern.""".md
                    }
                }
                markdownNote("Hold a key with the mouse, or click the figure and use W, E and R. [[back]] [[next]]")
            }
        }
        section("Air") {
            step("Air is a crowd", id = "air-crowd") {
                audience {
                    figureBeside("pressure_box") {
                        +"""
                        ## Air is a crowd

                        Air is molecules flying at about 500 m/s until they
                        hit something. This is a flat cartoon of a few
                        hundred.""".md
                    }
                }
                control("pressure_box", "length", 1.0)
                control("pressure_box", "follow one", false)
                markdownNote("Drag **speed** to slow time down. [[back]] [[next]]")
            }
            step("Pressure is hits on a wall", id = "air-pressure") {
                audience {
                    figureBeside("pressure_box") {
                        +"""
                        ## Pressure is hits on a wall

                        Each bounce pushes the wall. Pressure is how much
                        push each piece of wall gets: the plot.""".md
                    }
                }
                control("pressure_box", "length", 1.0)
                control("pressure_box", "follow one", false)
                markdownNote("The plot is hits per unit of wall per unit of time. [[back]] [[next]]")
            }
            step("Squeeze it", id = "air-squeeze") {
                audience {
                    figureBeside("pressure_box") {
                        +"""
                        ## Squeeze it

                        Half the length, twice the hits: twice the pressure.""".md
                    }
                }
                control("pressure_box", "length", 1.0)
                control("pressure_box", "follow one", false)
                cue("Halve the box") { control("pressure_box", "length", 0.5, glideMs = 2000) }
                markdownNote("Next squeezes the box to half its length over two seconds. [[back]] [[next]]")
            }
            step("Nobody goes far", id = "air-one-molecule") {
                audience {
                    figureBeside("pressure_box") {
                        +"""
                        ## Nobody goes far

                        One molecule, knocked about, barely leaves home. A
                        push travels by being handed on.""".md
                    }
                }
                control("pressure_box", "length", 1.0)
                control("pressure_box", "follow one", true)
                markdownNote("The orange trail is one molecule's path. [[back]] [[next]]")
            }
            step("A plate in a tube", id = "air-tube") {
                audience {
                    figureBeside("tube_plate") {
                        +"""
                        ## A plate in a tube

                        Each line is a slice of air. Push the plate: the
                        crowding runs down the tube; the slices only
                        shuffle.""".md
                    }
                }
                control("tube_plate", "plate", 0.0)
                control("tube_plate", "colour", false)
                cue("Push") { control("tube_plate", "plate", 12.0, glideMs = 300) }
                markdownNote("Next pushes the plate; or drag **plate** quickly one way, then back. [[back]] [[next]]")
            }
            step("Squeezed and stretched", id = "air-compression") {
                audience {
                    figureBeside("tube_plate") {
                        +"""
                        ## Squeezed and stretched

                        Red is squeezed, blue stretched. Either travels at
                        the speed of sound, about 343 m/s.""".md
                    }
                }
                control("tube_plate", "plate", 0.0)
                control("tube_plate", "colour", true)
                cue("Push") { control("tube_plate", "plate", 12.0, glideMs = 300) }
                cue("Pull back") { control("tube_plate", "plate", -12.0, glideMs = 600) }
                markdownNote(
                    "Colour is pressure: red above, blue below. Next pushes the plate, then Next pulls it back. [[back]] [[next]]",
                )
            }
        }
        section("Making sounds") {
            step("A jump is a pop", id = "sounds-pop") {
                audience {
                    figureBeside("repeated_jumps") {
                        +"""
                        ## A jump is a pop

                        A sudden move of the plate is a pop. A bigger move, a louder pop.""".md
                    }
                }
                control("repeated_jumps", "repeat", false)
                control("repeated_jumps", "distance", 0.5)
                control("repeated_jumps", "rate", 4.0)
                control("repeated_jumps", "sound", false)
                markdownNote(
                    "Tick **sound**, then hold **jump**; drag **distance** to change how far it moves. [[back]] [[next]]",
                )
            }
            step("Repeat it and it becomes a note", id = "sounds-repeat") {
                audience {
                    figureBeside("repeated_jumps") {
                        +"""
                        ## Repeat it and it becomes a note

                        Past about twenty a second the pops blur into a
                        tone. Its frequency, in hertz, is its pitch.""".md
                    }
                }
                control("repeated_jumps", "rate", 4.0)
                control("repeated_jumps", "repeat", false)
                control("repeated_jumps", "sound", false)
                cue("Repeat") { control("repeated_jumps", "repeat", true) }
                cue("Faster") { control("repeated_jumps", "rate", 220.0, glideMs = 4000) }
                markdownNote(
                    "Tick **sound** to hear it. Next starts the repeats at 4 per second, then Next glides to 220. [[back]] [[next]]",
                )
            }
            step("Sounds add", id = "sounds-add") {
                audience {
                    figureBeside("wave_synth") {
                        +"""
                        ## Sounds add

                        Two keys at once: the plate makes both motions, and
                        they add. The bottom line is what you hear.""".md
                    }
                }
                markdownNote("Hold one key, then two. [[back]] [[next]]")
            }
            step("One key, two sounds", id = "sounds-sum-key") {
                audience {
                    figureBeside("wave_synth") {
                        +"""
                        ## One key, two sounds

                        The last key plays A + B as one pattern. It sounds the same as holding both.""".md
                    }
                }
                markdownNote("Hold the first two keys together, then the fourth alone. [[back]] [[next]]")
            }
            step("Three numbers for a shape", id = "sounds-triangle") {
                audience {
                    figureBeside("triangle_params") {
                        +"""
                        ## Three numbers for a repeating shape

                        **Amplitude** is loudness, **frequency** is pitch,
                        **phase** is where it starts, which alone you cannot
                        hear.""".md
                    }
                }
                control("triangle_params", "amplitude", 0.6)
                control("triangle_params", "frequency", 220.0)
                control("triangle_params", "phase", 0.0)
                control("triangle_params", "sound", false)
                cue("Louder") { control("triangle_params", "amplitude", 1.0, glideMs = 1500) }
                cue("Higher") { control("triangle_params", "frequency", 440.0, glideMs = 1500) }
                cue("Shift") { control("triangle_params", "phase", 0.5, glideMs = 1500) }
                markdownNote(
                    "Tick **sound** first if you want it heard. Next: amplitude up, then frequency up, then phase shifted. [[back]] [[next]]",
                )
            }
            step("Half a cycle apart, they cancel", id = "sounds-cancel") {
                audience {
                    figureBeside("phase_cancel") {
                        +"""
                        ## Half a cycle apart, they cancel

                        Two equal tones add. Shift one by half a cycle and
                        every push meets a pull: silence.""".md
                    }
                }
                control("phase_cancel", "shift", 0.0)
                control("phase_cancel", "sound", false)
                cue("Shift half a cycle") { control("phase_cancel", "shift", 0.5, glideMs = 2500) }
                markdownNote("Tick **sound**, then Next slides the second tone half a cycle along. [[back]] [[next]]")
            }
            step("Draw a shape, hear it", id = "sounds-draw") {
                audience {
                    figureBeside("draw_wave", "wave", "controls") {
                        +"""
                        ## Draw a shape, hear it

                        Drag the points, then hold a key. Corners and jumps sound brighter.""".md
                    }
                }
                markdownNote("Drag points on the curve, then hold a key. [[back]] [[next]]")
            }
        }
        section("Pure tones") {
            step("A turning circle", id = "tones-circle") {
                audience {
                    figureBeside("circle_sine") {
                        +"""
                        ## A sine wave is a turning circle

                        Radius is amplitude, speed of turning is frequency, starting point is phase.""".md
                    }
                }
                control("circle_sine", "radius", 0.8)
                control("circle_sine", "turns per second", 0.5)
                control("circle_sine", "start", 0.0)
                markdownNote("Drag the sliders; the newest part of the trace is beside the circle. [[back]] [[next]]")
            }
            step("The range of hearing", id = "tones-sweep") {
                audience {
                    figureBeside("sine_sweep") {
                        +"""
                        ## The range of hearing

                        The purest tone. We hear about 20 Hz to 20,000 Hz,
                        best between 1,000 and 5,000.""".md
                    }
                }
                control("sine_sweep", "frequency", 220.0)
                control("sine_sweep", "sound", false)
                cue("Up an octave") { control("sine_sweep", "frequency", 440.0, glideMs = 1500) }
                markdownNote(
                    "Tick **sound**, then Next glides up an octave. Keep the room's volume low. [[back]] [[next]]",
                )
            }
            step("Close tones beat", id = "tones-beats") {
                audience {
                    figureBeside("beats") {
                        +"""
                        ## Two close tones beat

                        A few hertz apart, two sines drift in and out of
                        step: one tone, swelling and fading.""".md
                    }
                }
                markdownNote("Hold both keys; drag **difference**. [[back]] [[next]]")
            }
            step("Every shape is a sum of sines", id = "tones-square") {
                audience {
                    figureBeside("square_partials") {
                        +"""
                        ## Every shape is a sum of sines

                        Odd multiples, each smaller, build a square. The
                        ripples at the corners never shrink in height.""".md
                    }
                }
                control("square_partials", "sines", 1.0)
                control("square_partials", "sound", false)
                cue("Add sines") { control("square_partials", "sines", 25.0, glideMs = 4000) }
                markdownNote("Next adds sines one by one up to 25. [[back]] [[next]]")
            }
            step("Taking a drawing apart", id = "tones-fourier") {
                audience {
                    figureBeside("draw_wave", "wave", "partials", "controls") {
                        +"""
                        ## Taking a drawing apart

                        Your drawing is a sum of sines too. The bars say how much of each multiple.""".md
                    }
                }
                markdownNote("Redraw the shape and watch the bars. [[back]] [[next]]")
            }
            step("Listening back", id = "tones-microphone") {
                audience {
                    figureBeside("microphone") {
                        +"""
                        ## Listening back

                        Whistle: one bar. Sing a vowel: a row of them, at multiples of your note.""".md
                    }
                }
                control("microphone", "listen", false)
                markdownNote("Tick **listen** to open the microphone. [[back]] [[next]]")
            }
        }
        section("Masses and springs") {
            step("A mass between springs", id = "springs-one") {
                audience {
                    figureBeside("normal_modes") {
                        +"""
                        ## A mass between springs

                        It swings as a sine, at a pitch set by stiffness and
                        mass, however far it was pulled.""".md
                    }
                }
                control("normal_modes", "masses", 1.0)
                control("normal_modes", "mode", 1.0)
                control("normal_modes", "add the lowest", false)
                markdownNote("One mass has only one way to swing. [[back]] [[next]]")
            }
            step("Two masses, two ways to move", id = "springs-modes") {
                audience {
                    figureBeside("normal_modes") {
                        +"""
                        ## Two masses, two ways to move

                        Together, or against each other and a little faster.
                        Any motion mixes the two.""".md
                    }
                }
                control("normal_modes", "masses", 2.0)
                control("normal_modes", "mode", 1.0)
                control("normal_modes", "add the lowest", false)
                cue("The other mode") { control("normal_modes", "mode", 2.0) }
                cue("Both at once") { control("normal_modes", "add the lowest", true) }
                markdownNote("Next swaps mode 1 for mode 2, then Next mixes both. [[back]] [[next]]")
            }
            step("More masses, more modes", id = "springs-five") {
                audience {
                    figureBeside("normal_modes") {
                        +"""
                        ## More masses, more modes

                        Five masses, five modes. Higher modes wiggle more.""".md
                    }
                }
                control("normal_modes", "masses", 5.0)
                control("normal_modes", "mode", 3.0)
                control("normal_modes", "add the lowest", false)
                markdownNote("This is mode 3 of 5. Try the others one at a time. [[back]] [[next]]")
            }
            step("A string is many masses", id = "springs-string") {
                audience {
                    figureBeside("pluck") {
                        +"""
                        ## A string is many masses

                        A string's modes are whole numbers of half waves, at
                        whole multiples of the lowest pitch: the harmonics.""".md
                    }
                }
                control("pluck", "mode", 1.0)
                control("pluck", "length", 1.0)
                cue("Second mode") { control("pluck", "mode", 2.0) }
                cue("Third mode") { control("pluck", "mode", 3.0) }
                markdownNote("The string is shown slowed down. Next shows mode 2, then mode 3. [[back]] [[next]]")
            }
            step("Pluck it", id = "springs-pluck") {
                audience {
                    figureBeside("pluck") {
                        +"""
                        ## Pluck it

                        A pluck rings every mode at once. Near the middle it
                        is round; near the end, bright.""".md
                    }
                }
                control("pluck", "mode", 0.0)
                control("pluck", "length", 1.0)
                control("pluck", "pluck at", 0.25)
                cue("Near the end") { control("pluck", "pluck at", 0.06, glideMs = 800) }
                markdownNote(
                    "Press **pluck** (or P); Next moves the pluck near the end, then pluck again: brighter. [[back]] [[next]]",
                )
            }
            step("Shorter, tighter, lighter", id = "springs-fret") {
                audience {
                    figureBeside("pluck") {
                        +"""
                        ## Shorter, tighter, lighter: higher

                        Half the length, an octave up. Tighter is higher; heavier is lower.""".md
                    }
                }
                control("pluck", "mode", 0.0)
                control("pluck", "length", 1.0)
                cue("Half the length") { control("pluck", "length", 0.5, glideMs = 1500) }
                markdownNote("Pluck, then Next halves the string; pluck again an octave up. [[back]] [[next]]")
            }
        }
        section("Pressure waves") {
            step("A pulsing sphere", id = "waves-sphere") {
                audience {
                    figureBeside("pulsing_sphere") {
                        +"""
                        ## A pulsing sphere

                        Rings of high and low pressure. Their spacing is the
                        wavelength: 343 Hz makes one metre.""".md
                    }
                }
                control("pulsing_sphere", "frequency", 343.0)
                cue("Higher") { control("pulsing_sphere", "frequency", 1029.0, glideMs = 2000) }
                markdownNote("Next raises the frequency threefold; the rings crowd closer. [[back]] [[next]]")
            }
            step("Aimed sound", id = "waves-directional") {
                audience {
                    figureBeside("wall_plate") {
                        +"""
                        ## Aimed sound

                        A plate in a wall spreads low notes every way. Once the waves are shorter
                        than the plate, its edges interfere and the sound is aimed ahead.""".md
                    }
                }
                control("wall_plate", "frequency", 400.0)
                cue("Higher") { control("wall_plate", "frequency", 2500.0, glideMs = 2000) }
                markdownNote(
                    "Next raises the frequency past 2 kHz: the beam narrows. The strip is the pressure along the axis. [[back]] [[next]]",
                )
            }
            step("Later and quieter", id = "waves-distance") {
                audience {
                    figureBeside("speaker_ear") {
                        +"""
                        ## Further away: later and quieter

                        About 3 ms a metre. Twice as far, half the pressure swing.""".md
                    }
                }
                markdownNote("Drag the orange listener; the meters give the delay and the level. [[back]] [[next]]")
            }
            step("Decibels", id = "waves-decibels") {
                audience {
                    figureBeside("decibels") {
                        +"""
                        ## Decibels

                        Every ten times the pressure adds 20 dB. Twice the
                        distance costs about 6 dB.""".md
                    }
                }
                control("decibels", "pressure", 0.02)
                cue("Ten times the pressure") { control("decibels", "pressure", 0.2, glideMs = 1500) }
                markdownNote("Next multiplies the pressure by ten: 20 dB more. [[back]] [[next]]")
            }
            step("A moving source", id = "waves-doppler") {
                audience {
                    figureBeside("doppler") {
                        +"""
                        ## A moving source

                        Ahead of the source the rings crowd, and the pitch
                        is higher; behind, lower. Time is slowed a hundred
                        times.""".md
                    }
                }
                control("doppler", "speed", 150.0)
                control("doppler", "sound", false)
                markdownNote("Drag **speed**. Tick **sound** to hear it pass. [[back]] [[next]]")
            }
            step("Two speakers", id = "waves-two-speakers") {
                audience {
                    figureBeside("two_speakers") {
                        +"""
                        ## Two speakers

                        On the orange lines one speaker's push meets the other's pull and the tone nearly vanishes.""".md
                    }
                }
                control("two_speakers", "sound", false)
                markdownNote(
                    "Drag the orange listener onto an orange line and it goes quiet. Tick **sound** to hear it. [[back]] [[next]]",
                )
            }
            step("Echo", id = "waves-echo") {
                audience {
                    figureBeside("echo") {
                        +"""
                        ## Echo

                        Far from a wall, a second clap. Within about 50 ms,
                        closer than 8 m, one sound.""".md
                    }
                }
                markdownNote("Drag **distance to the wall**, then **clap**. [[back]] [[next]]")
            }
            step("A room", id = "waves-reverb") {
                audience {
                    figureBeside("reverb") {
                        +"""
                        ## A room

                        Bounces pile up into a tail: reverberation. Bigger,
                        harder rooms ring longer.""".md
                    }
                }
                control("reverb", "room size", 0.4)
                control("reverb", "soft walls", 0.3)
                cue("A big hall") { control("reverb", "room size", 0.9, glideMs = 1500) }
                markdownNote("Hold a key, then Next makes the room a hall. [[back]] [[next]]")
            }
        }
        section("Further reading") {
            step("Further reading", id = "further-reading") {
                audience {
                    +"""
                    # Further reading

                    - Eric J. Heller, [*Why You Hear What You Hear*](https://www.whyyouhearwhatyouhear.com/)
                      (Princeton, 2013): the physics of sound and its perception, with experiments to try.
                    - Neville H. Fletcher and Thomas D. Rossing, [*The Physics of Musical
                      Instruments*](https://archive.org/details/physicsofmusical0000flet) (Springer, 1991):
                      strings, plates, pipes and rooms in depth.
                    - Julius O. Smith III, [*Physical Audio Signal Processing*](https://ccrma.stanford.edu/~jos/pasp/),
                      free online: from waves on strings to digital models of them.""".md
                    p(classes = "muted") {
                        +"After “Sound” by Bartosz Ciechanowski, https://ciechanow.ski/sound/. Words and figures made in Field."
                    }
                }
                markdownNote("The end. [[back]] [[finish]]")
            }
        }
    }
    .arrangeBoxes()
    .present()
