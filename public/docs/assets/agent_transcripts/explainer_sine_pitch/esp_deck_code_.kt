import kotlinx.html.*

// Four slides: prose, a live figure and its slider, a cue that moves the slider, and
// the same figure heard. "sine" is the box that draws and plays the wave.
presentation(title = "A sine wave and its pitch") {
        step("A sine wave and its pitch", id = "opening") {
            audience {
                +"""
                # A sine wave and its pitch

                A sound is air pushed back and forth against your ear. The smoothest
                push is a sine wave: one even swing, repeated.

                How often the swing repeats is what you hear as pitch.
            """
                    .md
                p(classes = "muted") {
                    +"After “Sound” by Bartosz Ciechanowski, ciechanow.ski/sound. Words and figures made in Field."
                }
            }
            markdownNote("The question: what makes one note higher than another? [[next]]")
        }
        step("How often it repeats", id = "wave") {
            audience {
                figureBeside("sine") {
                    +"""
                    ## How often it repeats

                    The line is twenty milliseconds of the wave. Drag **frequency**:
                    as the number rises, more swings fit into the same time.

                    Frequency counts swings per second, in hertz.
                """
                        .md
                }
            }
            control("sine", "frequency", 220.0)
            control("sine", "sound", false)
            markdownNote("Let someone drag the slider. [[back]] [[next]]")
        }
        step("Twice as often", id = "octave") {
            audience {
                figureBeside("sine") {
                    +"""
                    ## Twice as often

                    220 Hz is the A below middle C. Doubling the frequency fits
                    twice as many swings in, and we hear the same note an octave up.
                """
                        .md
                }
            }
            control("sine", "frequency", 220.0)
            control("sine", "sound", false)
            cue("Up an octave") { control("sine", "frequency", 440.0, glideMs = 1500) }
            markdownNote("Next glides the slider from 220 to 440 Hz. [[back]] [[next]]")
        }
        step("Hear it", id = "listen") {
            audience {
                figureBeside("sine") {
                    +"""
                    ## Hear it

                    Tick **sound**, then drag **frequency**. The wave you see is the
                    tone you hear: one box draws it and plays it.
                """
                        .md
                }
            }
            cue("Sound on") { control("sine", "sound", true) }
            markdownNote("Next turns the sound on; leaving the talk stops it. [[back]] [[finish]]")
        }
    }
    .present()
