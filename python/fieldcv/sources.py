"""Where frames come from: a camera, a movie file, or a sorted sequence of image files.

Every source is an iterator of :class:`Frame`. A frame's image is a BGR ``uint8`` array (OpenCV's
native layout), already scaled by ``shrink``. Its ``time_ms`` is what the mediapipe detectors run
on, and it strictly increases: for files it is derived from the frame index and the frame rate, so
re-running a movie gives the same timestamps, and for a camera it is the wall clock.
"""

from __future__ import annotations

import glob as _glob
import time
from dataclasses import dataclass

import cv2
import numpy as np

DEFAULT_FPS = 50.0


@dataclass
class Frame:
    image: np.ndarray
    index: int
    time_ms: int
    path: str | None = None

    @property
    def width(self) -> int:
        return self.image.shape[1]

    @property
    def height(self) -> int:
        return self.image.shape[0]


def _shrunk(image: np.ndarray, shrink: float) -> np.ndarray:
    if shrink == 1.0:
        return image
    return cv2.resize(image, dsize=None, fx=shrink, fy=shrink, interpolation=cv2.INTER_AREA)


class Source:
    """Base class: iterate it for frames, ``close()`` it (or use ``with``) to release the device."""

    fps: float = DEFAULT_FPS

    def __iter__(self):
        return self

    def __next__(self) -> Frame:
        raise NotImplementedError

    def close(self) -> None:
        pass

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()


class _Capture(Source):
    def __init__(self, capture: cv2.VideoCapture, description: str, shrink: float):
        if not capture.isOpened():
            raise RuntimeError(f"could not open {description}")
        self._capture = capture
        self.shrink = shrink
        self.index = 0

    def _read(self) -> np.ndarray:
        ok, image = self._capture.read()
        if not ok or image is None:
            raise StopIteration
        return _shrunk(image, self.shrink)

    def close(self) -> None:
        self._capture.release()


class Camera(_Capture):
    """A webcam. ``width``/``height`` are requests; the device decides what it actually delivers.

    ``mirror=True`` flips each frame left-to-right, the way a selfie view does, so moving your right
    hand moves something on the right. mediapipe's ``handedness`` then reports the mirrored hand.
    """

    def __init__(self, device: int = 0, width: int | None = None, height: int | None = None, shrink: float = 1.0,
                 mirror: bool = False):
        capture = cv2.VideoCapture(device)
        if width:
            capture.set(cv2.CAP_PROP_FRAME_WIDTH, width)
        if height:
            capture.set(cv2.CAP_PROP_FRAME_HEIGHT, height)
        super().__init__(capture, f"camera {device}", shrink)
        self.fps = capture.get(cv2.CAP_PROP_FPS) or DEFAULT_FPS
        self.mirror = mirror
        self._t0 = time.monotonic()
        self._last_ms = -1

    def __next__(self) -> Frame:
        image = self._read()
        if self.mirror:
            image = cv2.flip(image, 1)
        t = int((time.monotonic() - self._t0) * 1000)
        t = max(t, self._last_ms + 1)
        self._last_ms = t
        frame = Frame(image, self.index, t)
        self.index += 1
        return frame


class Video(_Capture):
    """A movie file. ``loop=True`` rewinds at the end instead of stopping."""

    def __init__(self, path: str, skip_seconds: float = 0.0, shrink: float = 1.0,
                 fps: float | None = None, loop: bool = False):
        super().__init__(cv2.VideoCapture(path), path, shrink)
        self.path = path
        self.fps = fps or self._capture.get(cv2.CAP_PROP_FPS) or DEFAULT_FPS
        self.loop = loop
        if skip_seconds > 0:
            self._capture.set(cv2.CAP_PROP_POS_FRAMES, int(round(skip_seconds * self.fps)))

    def __next__(self) -> Frame:
        try:
            image = self._read()
        except StopIteration:
            if not self.loop or self.index == 0:
                raise
            self._capture.set(cv2.CAP_PROP_POS_FRAMES, 0)
            image = self._read()
        frame = Frame(image, self.index, int(round(self.index * 1000.0 / self.fps)), self.path)
        self.index += 1
        return frame


class Images(Source):
    """Image files matching a glob pattern, in sorted order (``frames/out_*.png``)."""

    def __init__(self, pattern: str, shrink: float = 1.0, fps: float = DEFAULT_FPS, loop: bool = False):
        self.paths = sorted(_glob.glob(pattern))
        self.loop = loop
        if not self.paths:
            raise RuntimeError(f"no files match {pattern!r}")
        self.shrink = shrink
        self.fps = fps
        self.index = 0

    def __next__(self) -> Frame:
        while self.index < len(self.paths) or (self.loop and self.index > 0):
            path = self.paths[self.index % len(self.paths)]
            image = cv2.imread(path)
            i = self.index
            self.index += 1
            if image is not None:
                return Frame(_shrunk(image, self.shrink), i, int(round(i * 1000.0 / self.fps)), path)
        raise StopIteration


class Arrays(Source):
    """Frames from arrays you already have (a list, or any iterable of BGR images)."""

    def __init__(self, images, fps: float = DEFAULT_FPS):
        self._images = iter(images)
        self.fps = fps
        self.index = 0

    def __next__(self) -> Frame:
        image = next(self._images)
        frame = Frame(np.ascontiguousarray(image), self.index, int(round(self.index * 1000.0 / self.fps)))
        self.index += 1
        return frame


def open_source(spec, **kwargs) -> Source:
    """``0`` → camera 0; a pattern with ``*``/``?`` → :class:`Images`; anything else → :class:`Video`.

    ``loop`` is accepted for every kind and ignored by a camera, which never ends.
    """
    if isinstance(spec, Source):
        return spec
    if isinstance(spec, int) or (isinstance(spec, str) and spec.isdigit()):
        kwargs.pop("loop", None)
        return Camera(int(spec), **kwargs)
    if any(c in spec for c in "*?["):
        return Images(spec, **kwargs)
    return Video(spec, **kwargs)
