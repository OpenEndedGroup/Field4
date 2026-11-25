# fieldmanim — Manim drives, Field draws

[Manim Community](https://www.manim.community/) describes an animation. `fieldmanim` plays it without
rendering it, records the Bézier paths and colours of every frame, and publishes them as an
`afield.lang.python.PathFrames`. Kotlin boxes then draw any moment of the scene as FLines: looped
on Field's clock, scrubbed, or stepped one `play()` at a time from a talk's cues.

```python
from manim import *
import fieldmanim

class Intro(Scene):
    def construct(self):
        c = Circle(color=BLUE)
        self.play(Create(c))
        self.play(Transform(c, Square(color=YELLOW, fill_opacity=0.5)))

fieldmanim.bake(Intro, "intro").show()
```

```kotlin
val scene = NumpyShared.pathFrames("intro")
lines(scene.at(1.5), w = 960.0, h = 540.0, framing = scene.framing())
```

Install into the venv Field's JEP is bound to:

```bash
UV_PROJECT_ENVIRONMENT=<that venv> uv sync --inexact --compile-bytecode --group manim
```

The user page is [`public/docs/manim.md`](../../public/docs/manim.md): baking, drawing from Kotlin,
beats and the player, talks, what is and is not drawn. `examples/unit_circle.yaml` is that page's
document as a transcript; its captures run from it.
