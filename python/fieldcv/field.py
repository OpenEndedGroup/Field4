"""The bridge to Field: numpy views of JVM memory that Kotlin boxes read directly.

Only importable inside Field (it needs JEP's ``afield`` Java package).

    from fieldcv import field
    cam = field.image("camera", 720, 1280)    # a (720, 1280, 4) uint8 numpy view
    cam.write(frame.image)                    # BGR → BGRA into the shared memory, then publish
    tracks = field.lines("tracks")
    tracks.publish(result["tracks"])          # a list of (n, 2) arrays

and in a Kotlin box under the renderer::

    prop<LoadedTexture>(camera) = NumpyShared.image("camera").texture()
    NumpyShared.lines("tracks").lines()       // List<FLine>, normalized coordinates

Both writes are safe from a :class:`~fieldcv.Pipeline` worker thread. See ``NumpyShared.kt`` for
why every buffer is triple-copied and why that is the cheap part.
"""

from __future__ import annotations

import cv2
import numpy as np

from afield.lang.python import NumpyShared, PythonInterface


def current_box_id() -> str | None:
    box = PythonInterface.thisBox
    return None if box is None else str(box.getId())


class SharedImage:
    """A shared ``(height, width, 4)`` BGRA image; :attr:`array` is the memory Kotlin will read."""

    def __init__(self, name: str, height: int = 0, width: int = 0):
        self._k = NumpyShared.image(name, height, width)
        self.name = name
        self.array: np.ndarray = self._k.array()

    @property
    def shape(self):
        return self.array.shape

    def write(self, image: np.ndarray, publish: bool = True) -> None:
        """Copy a BGR, BGRA or grayscale image in, resizing it if it is not this image's size."""
        h, w = self.array.shape[:2]
        if image.shape[:2] != (h, w):
            image = cv2.resize(image, (w, h), interpolation=cv2.INTER_AREA)
        if image.dtype != np.uint8:
            image = cv2.convertScaleAbs(image, alpha=255.0 if image.dtype.kind == "f" else 1.0)
        if image.ndim == 2:
            cv2.cvtColor(image, cv2.COLOR_GRAY2BGRA, dst=self.array)
        elif image.shape[2] == 3:
            cv2.cvtColor(image, cv2.COLOR_BGR2BGRA, dst=self.array)
        else:
            self.array[...] = image
        if publish:
            self.publish()

    def publish(self) -> None:
        self._k.publish()

    def __repr__(self) -> str:
        return f"<SharedImage {self.name!r} {self.array.shape[1]}x{self.array.shape[0]}>"


class SharedLines:
    """Shared polylines: publish a list of ``(n, 2)`` (or wider; extra columns are dropped) arrays."""

    def __init__(self, name: str):
        self._k = NumpyShared.lines(name)
        self.name = name
        self._views()

    def _views(self) -> None:
        self._points: np.ndarray = self._k.points()
        self._counts: np.ndarray = self._k.counts()

    def publish(self, polylines) -> None:
        polylines = [np.asarray(p, np.float32).reshape(len(p), -1)[:, :2] for p in polylines if len(p)]
        total = sum(len(p) for p in polylines)
        if total > len(self._points) or len(polylines) > len(self._counts):
            self._k.reserve(total, len(polylines))
            self._views()
        if polylines:
            np.concatenate(polylines, out=self._points[:total])
            self._counts[: len(polylines)] = [len(p) for p in polylines]
        self._k.publish(total, len(polylines))

    def draw(self, channel: str = "lines", width: float = 480, height: float = 270) -> None:
        """Preview the published lines as SVG in the current box's output."""
        NumpyShared.draw(PythonInterface.thisBox, self._k, channel, float(width), float(height))

    def __repr__(self) -> str:
        return f"<SharedLines {self.name!r}>"


class Preview:
    """Show images in the output of the box that made this, from any thread.

    ``display`` writes to whichever box is running *now*, which on a pipeline's worker thread is
    nobody in particular, so a preview remembers its box when it is created. Each :meth:`show`
    replaces the last picture, as a JPEG ``width`` pixels wide.
    """

    def __init__(self, channel: str = "preview", width: int = 640, quality: int = 80):
        self._box = PythonInterface.thisBox
        self.channel = channel
        self.width = width
        self.quality = quality
        if self._box is not None:
            from afield import OutputsKt
            OutputsKt.displayOpen(self._box, channel)

    def show(self, image: np.ndarray) -> None:
        import base64
        h, w = image.shape[:2]
        if w != self.width:
            image = cv2.resize(image, (self.width, round(h * self.width / w)), interpolation=cv2.INTER_AREA)
        ok, jpeg = cv2.imencode(".jpg", image, [cv2.IMWRITE_JPEG_QUALITY, self.quality])
        html = (f'<img src="data:image/jpeg;base64,{base64.b64encode(jpeg.tobytes()).decode()}" '
                f'style="width:{self.width}px;height:{image.shape[0]}px;display:block">')
        if self._box is not None:
            self._box.display(self.channel, html)


def image(name: str, height: int = 0, width: int = 0) -> SharedImage:
    return SharedImage(name, height, width)


def lines(name: str) -> SharedLines:
    return SharedLines(name)
