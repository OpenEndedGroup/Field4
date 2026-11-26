# Manim Scenes

Bring a [Manim](https://www.manim.community/) scene into Field and draw it as
[FLines](fline_examples.md). A Python box plays the scene without rendering it and keeps the shapes
of every frame. Kotlin boxes can then loop it on Field's clock, scrub it from a slider, or step
through its animations from the slides of a [talk](presentation_studio.md).

## A scene in a Python box

A Manim scene is a class with a `construct` method, written exactly as Manim's own documentation
writes it. This one turns a point around a circle and traces its height as a sine wave.
`fieldmanim.bake` plays the scene and publishes it to Field under a name, here `unit_circle`, and
`show` draws one moment of it in the box's output:

```diffstatic_runner {data-lang=python screenshot="assets/manim/scene.png" capture="box" open-outputs="true" wait-output="true" wait-output-kind="display" wait-output-channel="manim" wait="2" timeout="300" w="900" requires="jep,python:manim" embed=true name="manim-scene" alt="A Python box's output: on a black frame, a blue circle on the left with a white radius to a yellow dot, and to its right a yellow sine wave three quarters drawn along a grey axis, a dashed grey line joining the dot to the end of the wave."}
:::static
from manim import *
import fieldmanim

class UnitCircle(Scene):
    def construct(self):
        circle = Circle(radius=1.5, color=BLUE).shift(LEFT * 4)
        axis = Line(LEFT * 1.5, RIGHT * 4.6, color=GREY)
        theta = ValueTracker(0)

        def tip():
            return circle.point_at_angle(theta.get_value())

        def wave_end():
            t = theta.get_value()
            return np.array([-1.5 + 0.9 * t, 1.5 * np.sin(t), 0])

        radius = always_redraw(lambda: Line(circle.get_center(), tip()))
        dot = always_redraw(lambda: Dot(tip(), color=YELLOW))
        reach = always_redraw(lambda: DashedLine(tip(), wave_end(), color=GREY))
        wave = always_redraw(lambda: ParametricFunction(
            lambda t: np.array([-1.5 + 0.9 * t, 1.5 * np.sin(t), 0]),
            t_range=[0, max(theta.get_value(), 1e-3)], color=YELLOW))

        self.play(Create(circle), Create(axis))
        self.play(FadeIn(radius, dot))
        self.add(reach, wave)
        self.play(theta.animate.set_value(TAU), run_time=4, rate_func=linear)
        self.play(Write(Text("sin θ", font_size=40).next_to(axis, RIGHT)))
        self.wait()

scene = fieldmanim.bake(UnitCircle, "unit_circle")
scene.show(5.0)
scene
```

The value of the last line, which Field prints beside it, says what was baked: eight seconds at 30 frames a second, in five beats, one for
each `play` and `wait`. Baking is quick, because nothing is drawn while it happens. `show(seconds)` draws the scene at that moment, and with no argument its last frame.

`bake` also takes a function of the scene in place of a class
(`def intro(s): s.play(Create(Circle()))`), a frame rate (`fps=60`), and any Manim config option
for that one bake, such as `background_color=WHITE` or `frame_width=10`. Running the cell again
bakes the scene again under the same name, and every box drawing it picks up the new version.

Text is set in Field's own Iosevka, the face of its canvas and its talks, unless a `Text` names a
`font`. `fieldmanim.use_font("Helvetica Neue")` changes the default for every scene to any font on
your machine, and `fieldmanim.use_font(None)` goes back to Manim's own. `MathTex` and `Tex` are
typeset by LaTeX, which has to be installed and on your `PATH`.

## Drawing it from Kotlin

Any Kotlin box can find the scene by its name. `scene.draw(thisBox, seconds)` draws the frame at
that moment in the box's output, on a 960 by 540 canvas that keeps its framing as shapes come and
go. Called on every frame, it sends a new drawing only when the frame has changed. This box loops
the scene against Field's own animation clock:

```diffstatic_runner {data-lang=kotlin video="assets/manim/loop.mp4" capture="box" macro-file="../../python/fieldmanim/examples/unit_circle.yaml" run-box="manim_loop" capture-box="manim_loop" execution="launch" open-outputs="true" duration="8" fps="20" timeout="300" requires="jep,python:manim" embed=true name="manim-loop" alt="The unit circle scene playing in a Kotlin box's output: the circle and axis are drawn, a radius and dot fade in, the dot travels once round the circle while the yellow wave is traced to its right, the label sin θ is written, and the scene starts again."}
:::static
val scene = NumpyShared.pathFrames("unit_circle")
val t0 = AnimationClock.nowSeconds()

loop {
    scene.draw(thisBox, AnimationClock.nowSeconds() - t0, loop = true)
}
```

Python is no longer involved. The box asks for a moment and gets the shapes for it, so the scene can
run at any speed, backwards, or from a slider. Without `loop = true` it holds its last frame after
the end.

`scene.at(seconds)` is the same frame as a list of FLines, to draw with `lines(...)` alongside
lines of your own, or to change first. `scene.at(t, 480.0, 270.0)` is another size, with stroke
widths scaled to match, `background = false` leaves out the black background so the scene can go
over something else, and `framing = scene.framing()` keeps the canvas framed as `draw` does.
`scene.frame(i)` asks by frame number, and `scene.duration`, `scene.fps` and `scene.frameCount` say
how much there is.

## One beat at a time

Each `play` or `wait` in a scene is a *beat*. `scene.beats` lists them with their names and times:
here `Create`, `FadeIn`, `animate.set_value`, `Write` and `Wait`. A player steps through them.
Asked for the next beat, it plays that beat at the scene's own speed. Asked for any other, earlier
or further on, it shows the scene as it is after that beat straight away.

This box puts a player behind a `beat` slider. Moving the slider by one plays one animation:

```diffstatic {data-lang=kotlin}
val scene = NumpyShared.pathFrames("unit_circle")
val play = scene.player()

controlGroup {
    slider("beat", 0.0, scene.beats.size.toDouble(), step = 1.0, initialValue = 0.0) { play.toBeat(it.toInt()) }
}

loop {
    play.draw(thisBox)
}
```

```diffstatic_runner {data-lang=kotlin capture="ui" region="box" macro-file="../../python/fieldmanim/examples/unit_circle.yaml" capture-box="manim_figure" video="assets/manim/beats.mp4" fps="20" timeout="300" requires="jep,python:manim" embed=true hidden=true name="manim-beats" alt="The beat slider steps from 1 to 5: the circle and axis are drawn, the radius and dot fade in, the sweep traces the wave at the scene's own speed, the label is written; then the slider goes back to 2 and the figure shows the radius and dot again at once."}
// Step the figure as a talk's cues would: one beat, then the next, then back.
val figure = thisBox.named("unit circle figure")
ui.pause(800)
for ((beat, ms) in [1 to 1300, 2 to 1300, 3 to 4400, 4 to 1500, 5 to 1000]) {
    figure.setControl("beat", beat.toDouble())
    ui.pause(ms)
}
figure.setControl("beat", 2.0)
ui.pause(1500)
```

That slider is what a talk moves. On a slide, `figureBeside("unit circle figure")` shows the box's
output beside the prose. `control("unit circle figure", "beat", n)` sets where the scene stands
when the slide comes up, and each cue plays the next beat:

```diffstatic {data-lang=kotlin}
presentation(title = "Where sine comes from") {
    step("Round and along", id = "round") {
        audience {
            figureBeside("unit circle figure") {
                +"""
                    ## Round and along

                    Turn a point around a circle and follow its height.
                    Laid out along a line, the heights are a sine wave.""".md
            }
        }
        control("unit circle figure", "beat", 2.0)
        cue("Go round") { control("unit circle figure", "beat", 3.0) }
        cue("Name it") { control("unit circle figure", "beat", 4.0) }
    }
}.present()
```

Coming back to the slide sets the beat to 2 again, and the player shows the circle with its radius
at once rather than playing the scene back to it. The talk starts the figure box when its slide
comes up and stops it when the talk moves on.
[Live figures beside prose](presentation_studio.md#live-figures-beside-prose) has the rest of how
figures, controls and cues work.

## Parts by name, and depth

To draw or follow one part of a scene from Kotlin, name it in Python with `fieldmanim.tag`. It
returns the mobject, so it can wrap the constructor, and everything inside a tagged group carries
the group's name unless a part has a tag of its own:

```diffstatic {data-lang=python}
cells = fieldmanim.tag(VGroup(*[Square(0.3) for _ in range(18)]).arrange(RIGHT, buff=0), "copies")
rod = fieldmanim.tag(Line(LEFT, RIGHT, color=YELLOW).shift(DOWN * 2), "rod")
...
self.play(Transform(cells, fives), rod.animate.shift(OUT * 2 + RIGHT * 3), run_time=2)

fieldmanim.bake(Recut, "recut", depth=True)
```

A tag survives a `Transform`: the paths `cells` becomes are still `copies`. In Kotlin,
`scene.tags` lists the names, `scene.center("rod", t)` is where that part is at `t` (the centre
of its bounding box, or `null` when it is not on screen) and `scene.visible("rod", t)` says
whether it is there at all.

`scene.units(t)` is the frame in the scene's own coordinates rather than on a canvas: x right
and y up from the centre of the frame, in Manim's units, with stroke widths in the same units.
`scene.units(t, "rod")` is the tagged part alone. With `depth=True`, `bake` keeps every point's
z as well, in the scene's coordinates and without Manim's camera, so the lines can be placed in
a 3D scene and looked at by Field's camera. Here the rod's centre goes from `(-1, -2, 0)` to
`(3, -2, 2)` as it moves out. Without `depth`, z is zero. Either way, `at` and `draw` draw the
same 2D picture.

## What gets drawn

A bake records what Manim's own renderer would have painted on every frame: each shape's
background stroke, fill and stroke, in Manim's drawing order, with stroke widths at the same
proportion of the frame. Scenes built from `VMobject`s, which is nearly all of Manim (shapes, curves,
graphs, `Text`, axes and number lines), come out complete, updaters and `always_redraw` included.

Some things are drawn differently or not at all:

- A colour gradient is drawn in its first colour.
- Line joins and caps are drawn in Field's style, not Manim's.
- `ImageMobject` and point clouds (`PMobject`) are not paths. They are left out, and the baked
  summary says how many there were, for instance `skipped {'ImageMobject': 1}`.

## Installing

`fieldmanim` lives in `python/fieldmanim` in the Field checkout, which Field puts on Python's path
when it starts. Manim itself is an optional dependency group. Install it into the virtual
environment Field's Python uses (the one chosen in the welcome screen's Python settings):

```diffstatic {data-lang=bash}
UV_PROJECT_ENVIRONMENT=.venv uv sync --inexact --compile-bytecode --group manim
```

`--inexact` adds packages without removing anything already there, and `--compile-bytecode` makes
Manim's first import in a session quick and quiet. Restart Field afterwards. For `MathTex` and
`Tex`, install a LaTeX distribution as well: MacTeX on macOS, TeX Live elsewhere.

## How it works

Manim normally draws each frame with Cairo and encodes the frames into a video. `fieldmanim`
replaces Manim's renderer with one that paints nothing. It runs the scene's `construct` as Manim
would, so `play`, `wait`, updaters and rate functions behave as they always do. At each frame it
records the Bézier control points and colours of every shape on screen. Frames that look alike, such
as the frames of a `wait`, are kept once.

The recording goes to Field in one call, as flat arrays of points and counts, and is held in a
[`PathFrames`](reference/languages.md#pathframes-kt) by name. Turning a frame into FLines happens in
Kotlin, the first time that frame is asked for at that size, and the result is cached. A scene
is drawn from Field's runloop like any other drawing, and never waits on the Python interpreter.

Manim is developed by the Manim Community and released under the MIT license.

## Where to go next

- [Presenting with Field](presentation_studio.md): the talks a figure goes on, and
  [Live figures beside prose](presentation_studio.md#live-figures-beside-prose) for controls and cues.
- [Python in a box](python.md): how Python boxes share one interpreter with each other and with
  Kotlin.
- [Frames and the animation loop](animation_time_model.md): `loop`, `AnimationClock` and what
  happens when a box stops.
- [FLine examples](fline_examples.md): what else to do with the lines a frame is made of.
- [Computer vision in Python](python_cv.md): the other direction, Python finding shapes in video for
  Kotlin to draw.
