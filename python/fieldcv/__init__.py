"""fieldcv — webcam, movie and image-sequence computer vision for Field's Python boxes.

The cleaned-up descendant of *pythonhead*, the out-of-process OpenCV/mediapipe helper that older
Field versions ran as a subprocess and read back as JSON over stdout. Here the same detectors run
inside Field's own Python interpreter, and their results reach Field's graphics through shared
memory rather than text (:mod:`fieldcv.field`).

    import fieldcv as cv4
    cv4.Pipeline(cv4.sample("swing"), cv4.Pose(), cv4.Hands(), cv4.Face()).show().start()

Needs the optional ``cv`` dependency group: ``uv sync --inexact --group cv``.
"""

from .detectors import (Background, Circles, Detector, Face, Flow, Hands, Lines, LumaKey, Pose, Tracks,
                        contours_of, edges)
from .pipeline import Pipeline
from .samples import SAMPLES, sample, sample_path
from .sources import Arrays, Camera, Frame, Images, Source, Video, open_source
from .util import jsonable

__all__ = [
    "Arrays", "Background", "Camera", "Circles", "Detector", "Face", "Flow", "Frame", "Hands", "Images",
    "Lines", "LumaKey", "Pipeline", "Pose", "Source", "Tracks", "Video", "contours_of", "edges", "jsonable",
    "open_source", "SAMPLES", "sample", "sample_path",
]
