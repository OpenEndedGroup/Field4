"""Manim scenes, drawn by Field.

Manim Community describes an animation; this package plays it without rendering it, records the
Bézier paths and colours of every frame, and hands them to Field as a
:class:`afield.lang.python.PathFrames`. A Kotlin box then draws any moment of it as FLines::

    from manim import *
    import fieldmanim

    class Intro(Scene):
        def construct(self):
            c = Circle(color=BLUE)
            self.play(Create(c))
            self.play(Transform(c, Square(color=YELLOW, fill_opacity=0.5)))
            self.wait()

    fieldmanim.bake(Intro, "intro")

and in a Kotlin box::

    val scene = NumpyShared.pathFrames("intro")
    lines(scene.at(1.5), w = 960.0, h = 540.0, framing = scene.framing(960.0, 540.0))

Nothing goes through Cairo and nothing is encoded to video. What gets recorded is what Manim's
Cairo camera would have painted: every VMobject's background stroke, fill and stroke, in drawing
order. Images and point clouds (``ImageMobject``, ``PMobject``) are not vector paths and are left
out; :class:`Baked` counts what was skipped. A gradient is drawn in its first colour.

``Text``, ``MarkupText`` and ``Paragraph`` are set in Field's own Iosevka unless they name a
``font``; :func:`use_font` changes that for every scene. ``MathTex`` and ``Tex`` are typeset by
LaTeX, which needs to be on ``PATH``.
"""

from __future__ import annotations

import tempfile
import warnings
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

import numpy as np

# pydub 0.25 (Manim's audio dependency) has regexes that warn when compiled. Field's interpreter
# writes no .pyc files, so they would be compiled, and print to the box's stderr, on every first
# import, unless the venv was installed with `uv sync --compile-bytecode`.
with warnings.catch_warnings():
    warnings.simplefilter("ignore", SyntaxWarning)
    import manim

import manimpango
from manim import MarkupText, PMobject, Scene, Text, VMobject, config, tempconfig
from manim.mobject.types.image_mobject import AbstractImageMobject
from manim.renderer.cairo_renderer import CairoRenderer

__all__ = ["bake", "Baked", "use_font", "FONT"]

#: Where Manim keeps what it writes for ``Text`` and ``MathTex`` (SVG and TeX caches). Manim's own
#: default is ``./media``, which inside Field is the checkout or wherever Field was started.
MEDIA_DIR = Path(tempfile.gettempdir()) / "fieldmanim"

if config.media_dir in ("./media", "media"):
    config.media_dir = str(MEDIA_DIR)

# Manim logs every animation at INFO; inside a box that is a page of noise per bake.
manim.logger.setLevel("WARNING")

#: The family Field sets its own text in: the Iosevka of its canvas, panels and talks.
FONT = "Iosevka Field"
_FONT_FILE = "IosevkaField-Light.ttf"


def _register_field_font() -> bool:
    """Make Field's Iosevka visible to Pango, from the checkout or, failing that, the JVM classpath."""
    checkout = Path(__file__).resolve().parents[2] / "src" / "commonMain" / "resources" / _FONT_FILE
    if checkout.exists():
        manimpango.register_font(str(checkout))
        return True
    try:  # a packaged Field has the font only inside its jar
        from java.lang import Thread

        stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(_FONT_FILE)
        if stream is None:
            return False
        try:
            data = bytes(b & 0xFF for b in stream.readAllBytes())
        finally:
            stream.close()
        copy = MEDIA_DIR / _FONT_FILE
        copy.parent.mkdir(parents=True, exist_ok=True)
        copy.write_bytes(data)
        manimpango.register_font(str(copy))
        return True
    except ImportError:
        return False


def use_font(family: str | None = FONT) -> None:
    """Set the font ``Text``, ``MarkupText`` and ``Paragraph`` use when they are not given one.

    ``use_font()`` is Field's Iosevka, which is the default once ``fieldmanim`` is imported;
    ``use_font("Helvetica Neue")`` is any font installed on the machine; ``use_font(None)`` goes
    back to Manim's own default, Pango's serif.
    """
    for cls in (Text, MarkupText):
        cls.set_default(font=family or "")


if _register_field_font():
    use_font(FONT)


@dataclass
class Baked:
    """What :func:`bake` published: the name a Kotlin box asks for, and how much of it there is."""

    name: str
    fps: float
    frames: int
    snapshots: int
    paths: int
    beats: list[str] = field(default_factory=list)
    skipped: dict[str, int] = field(default_factory=dict)

    @property
    def duration(self) -> float:
        return self.frames / self.fps if self.fps else 0.0

    def frames_object(self):
        """The Kotlin :class:`PathFrames` this was published to."""
        from afield.lang.python import NumpyShared

        return NumpyShared.pathFrames(self.name)

    def show(self, seconds: float | None = None, channel: str = "manim", width: float = 960, height: float = 540) -> None:
        """Draw the frame at ``seconds`` (the last frame by default) in this box's output, as SVG."""
        from afield.lang.python import PythonInterface

        frames = self.frames_object()
        t = frames.getDuration() if seconds is None else float(seconds)
        frames.draw(PythonInterface.thisBox, t, channel, float(width), float(height))

    def __repr__(self) -> str:
        skipped = f", skipped {self.skipped}" if self.skipped else ""
        return (f"<Baked {self.name!r}: {self.duration:.2f} s, {self.frames} frames at {self.fps:g} fps, "
                f"{len(self.beats)} beats ({', '.join(self.beats)}){skipped}>")


class _NoFiles:
    """Stands in for Manim's SceneFileWriter: the recorder writes no movie, image or cache."""

    def __getattr__(self, name):
        return lambda *args, **kwargs: None


class _Snapshot:
    __slots__ = ("points", "subpaths", "styles", "path_subpaths")

    def __init__(self, points, subpaths, styles, path_subpaths):
        self.points = points
        self.subpaths = subpaths
        self.styles = styles
        self.path_subpaths = path_subpaths

    def same_as(self, other: "_Snapshot | None") -> bool:
        return (other is not None
                and np.array_equal(self.path_subpaths, other.path_subpaths)
                and np.array_equal(self.subpaths, other.subpaths)
                and np.array_equal(self.styles, other.styles)
                and np.array_equal(self.points, other.points))


def _animation_name(animation) -> str:
    """``Create``, or for ``.animate`` the method it animates: ``animate.set_value``."""
    methods = getattr(animation, "methods", None)
    if type(animation).__name__ == "_MethodAnimation" and methods:
        return "animate." + ".".join(getattr(m.method, "__name__", "method") for m in methods)
    return type(animation).__name__


class _Recorder(CairoRenderer):
    """A Cairo renderer that paints nothing and records each frame's paths instead.

    Manim's ``Scene`` drives its renderer through ``play`` (an animation or a ``wait``), ``render``
    (one frame of an animation) and ``add_frame``; everything else here is the Cairo renderer's
    file and caching bookkeeping, which a recording has no use for.
    """

    def __init__(self, fps: float):
        super().__init__(skip_animations=False)
        self.fps = fps
        self.snapshots: list[_Snapshot] = []
        self.frame_snapshots: list[int] = []
        self.skipped: dict[str, set[int]] = {}
        self.beats: list[tuple[int, str]] = []
        self.scene: Scene | None = None

    def init_scene(self, scene, session_spec=None, file_writer_settings=None) -> None:
        self.scene = scene
        self.file_writer = _NoFiles()

    def play(self, scene, *args, **kwargs) -> None:
        scene.compile_animation_data(*args, **kwargs)
        # one beat per play() or wait(), named after its animations: "Create", "Transform + FadeIn"
        names = [_animation_name(a) for a in (scene.animations or [])]
        self.beats.append((len(self.frame_snapshots), " + ".join(dict.fromkeys(names)) or "Wait"))
        scene.begin_animations()
        if scene.is_current_animation_frozen_frame():
            self.add_frame(None, num_frames=int(scene.duration * self.fps))
        else:
            scene.play_internal()
        self.num_plays += 1

    def update_frame(self, *args, **kwargs) -> None:
        pass

    def get_frame(self):
        return None

    def save_static_frame_data(self, scene, static_mobjects):
        self.static_image = None
        return None

    def render(self, scene, time, moving_mobjects) -> None:
        self.add_frame(None)

    def add_frame(self, frame, num_frames: int = 1) -> None:
        if num_frames <= 0:
            return
        snap = self._snapshot()
        if not snap.same_as(self.snapshots[-1] if self.snapshots else None):
            self.snapshots.append(snap)
        self.frame_snapshots.extend([len(self.snapshots) - 1] * num_frames)
        self.time += num_frames / self.fps

    def scene_finished(self, scene) -> None:
        pass

    def _snapshot(self) -> _Snapshot:
        camera = self.camera
        points: list[np.ndarray] = []
        subpaths: list[tuple[int, int]] = []
        styles: list[tuple[float, ...]] = []
        path_subpaths: list[int] = []

        for mob in camera.get_mobjects_to_display(self.scene.mobjects):
            if not isinstance(mob, VMobject):
                # Cairo draws point clouds and images too; anything else (a ValueTracker) it ignores
                if isinstance(mob, (PMobject, AbstractImageMobject)):
                    self.skipped.setdefault(type(mob).__name__, set()).add(id(mob))
                continue
            pts = camera.transform_points_pre_display(mob, mob.points)
            if len(pts) == 0:
                continue
            geometry = []
            for start, end in mob.get_subpath_split_indices_from_points(pts, n_dims=2):
                start, end = int(start), int(end)
                k = (end - start) // 4
                if k == 0:
                    continue
                seg = pts[start:start + 4 * k, :2]
                # Manim stores each cubic as (anchor, handle, handle, anchor); Field wants a start
                # point and then (handle, handle, anchor) per cubic.
                flat = np.concatenate([seg[:1], seg.reshape(k, 4, 2)[:, 1:, :].reshape(-1, 2)])
                closed = bool(mob.consider_points_equals_2d(pts[start], pts[end - 1]))
                geometry.append((flat, closed))
            if not geometry:
                continue

            def emit(rgba, width: float, filled: bool) -> None:
                points.extend(g for g, _ in geometry)
                subpaths.extend((len(g), int(c)) for g, c in geometry)
                styles.append((*map(float, rgba[:4]), float(width), 1.0 if filled else 0.0))
                path_subpaths.append(len(geometry))

            # The same order as Camera.display_vectorized: background stroke, fill, stroke.
            bg_width = mob.get_stroke_width(background=True)
            if bg_width > 0:
                rgba = camera.get_stroke_rgbas(mob, background=True)[0]
                if rgba[3] > 0:
                    emit(rgba, bg_width, False)
            rgba = camera.get_fill_rgbas(mob)[0]
            if rgba[3] > 0:
                emit(rgba, 0.0, True)
            width = mob.get_stroke_width()
            if width > 0:
                rgba = camera.get_stroke_rgbas(mob)[0]
                if rgba[3] > 0:
                    emit(rgba, width, False)

        return _Snapshot(
            np.concatenate(points).astype(np.float32) if points else np.zeros((0, 2), np.float32),
            np.asarray(subpaths, np.int32).reshape(-1, 2),
            np.asarray(styles, np.float32).reshape(-1, 6),
            np.asarray(path_subpaths, np.int32),
        )


def _scene_class(scene) -> type[Scene]:
    if isinstance(scene, type) and issubclass(scene, Scene):
        return scene
    if callable(scene):
        construct: Callable[[Scene], None] = scene
        return type(getattr(scene, "__name__", "FieldScene"), (Scene,), {"construct": lambda self: construct(self)})
    raise TypeError(f"bake() wants a Scene subclass or a function of the scene, not {scene!r}")


def bake(scene, name: str | None = None, fps: float = 30, **manim_config) -> Baked:
    """Play ``scene`` without rendering it and publish every frame's paths to Field as ``name``.

    ``scene`` is a ``Scene`` subclass, or a function that takes the scene and does what
    ``construct`` would (``def intro(s): s.play(Create(Circle()))``). ``name`` defaults to the
    scene's class or function name. Any other keyword is a Manim config option for this bake
    (``background_color=WHITE``, ``frame_width=10``).

    A Kotlin box reads the result with ``NumpyShared.pathFrames(name)``; baking again under the
    same name replaces it, and a box drawing it picks up the new one on its next frame.
    """
    cls = _scene_class(scene)
    name = name or cls.__name__
    options = {"frame_rate": fps, "disable_caching": True, "progress_bar": "none",
               "write_to_movie": False, "save_last_frame": False, **manim_config}
    with tempconfig(options):
        recorder = _Recorder(float(config.frame_rate))
        instance = cls(renderer=recorder)
        instance.setup()
        instance.construct()
        instance.tear_down()
        if not recorder.frame_snapshots:
            recorder.add_frame(None)  # a scene that only adds things is one still frame
        background = np.asarray(config.background_color.to_rgba(), np.float32).copy()
        background[3] *= float(config.background_opacity)
        unit_w, unit_h = float(config.frame_width), float(config.frame_height)

    snaps = recorder.snapshots
    points = np.concatenate([s.points for s in snaps]).astype(np.float32)
    subpaths = np.concatenate([s.subpaths for s in snaps]).astype(np.int32)
    styles = np.concatenate([s.styles for s in snaps]).astype(np.float32)
    path_subpaths = np.concatenate([s.path_subpaths for s in snaps]).astype(np.int32)
    snapshot_paths = np.asarray([len(s.path_subpaths) for s in snaps], np.int32)
    frame_snapshots = np.asarray(recorder.frame_snapshots, np.int32)
    beat_starts = np.asarray([start for start, _ in recorder.beats], np.int32)
    beat_names = "\n".join(name.replace("\n", " ") for _, name in recorder.beats)

    from afield.lang.python import NumpyShared

    NumpyShared.pathFrames(name).publish(
        float(recorder.fps), unit_w, unit_h, background,
        points.ravel(), subpaths.ravel(), styles.ravel(),
        path_subpaths, snapshot_paths, frame_snapshots,
        beat_starts, beat_names,
    )
    return Baked(name, recorder.fps, len(frame_snapshots), len(snaps), int(snapshot_paths.max(initial=0)),
                 [name for _, name in recorder.beats],
                 {kind: len(ids) for kind, ids in recorder.skipped.items()})
