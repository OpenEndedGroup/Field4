"""Detectors: each one takes a :class:`~fieldcv.sources.Frame` and returns a dict of results.

Conventions shared by every detector:

* **Coordinates are normalized.** ``x`` is divided by the frame width and ``y`` by its height, so
  ``(0, 0)`` is the top-left corner and ``(1, 1)`` the bottom-right, whatever ``shrink`` was. Radii
  and lengths are divided by the width.
* **Results are numpy arrays**, not lists: a set of points is an ``(N, 2)`` ``float32`` array, a set
  of polylines is a list of them. They go into Field's shared buffers without conversion
  (:mod:`fieldcv.field`), and ``fieldcv.jsonable`` turns them into lists when you want JSON.
* **Nothing found is an empty array or ``None``**, never ``NaN``. A centroid of an empty mask is
  ``None``.
* **State lives on the instance.** Optical flow and the trackers remember the previous frame, so use
  one detector per stream, and call ``reset()`` when you seek.

``draw(image, result)`` paints a result onto a BGR image in place, for previews.
"""

from __future__ import annotations

import cv2
import numpy as np

from .sources import Frame


def _gray(image: np.ndarray) -> np.ndarray:
    return image if image.ndim == 2 else cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)


def _scale(width: int, height: int) -> np.ndarray:
    return np.array([1.0 / width, 1.0 / height], np.float32)


def _px(points: np.ndarray, image: np.ndarray) -> np.ndarray:
    """Normalized ``(N, 2+)`` points back to integer pixel coordinates of ``image``."""
    h, w = image.shape[:2]
    return np.round(points[..., :2] * (w, h)).astype(np.int32)


def _centroid(weights: np.ndarray) -> np.ndarray | None:
    """Weighted centroid of a 2-D array, normalized; ``None`` when the weights sum to zero."""
    m = cv2.moments(weights.astype(np.float32, copy=False))
    if m["m00"] <= 0:
        return None
    h, w = weights.shape
    return np.array([m["m10"] / m["m00"] / w, m["m01"] / m["m00"] / h], np.float32)


def contours_of(mask: np.ndarray, min_area: float = 0.0) -> list[np.ndarray]:
    """Outlines of the nonzero regions of ``mask`` as normalized ``(N, 2)`` arrays.

    ``min_area`` is a fraction of the image area, so ``0.001`` drops anything smaller than a tenth of
    a percent of the frame whatever its resolution.
    """
    h, w = mask.shape[:2]
    binary = (mask > 0).astype(np.uint8)
    found, _ = cv2.findContours(binary, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    scale = _scale(w, h)
    limit = min_area * w * h
    return [c.reshape(-1, 2).astype(np.float32) * scale for c in found if cv2.contourArea(c) > limit]


class Detector:
    name = "detector"
    color = (0, 255, 0)  # BGR, for draw()

    def __call__(self, frame: Frame) -> dict:
        raise NotImplementedError

    def reset(self) -> None:
        pass

    def draw(self, image: np.ndarray, result: dict) -> np.ndarray:
        return image

    def close(self) -> None:
        pass


# ---------------------------------------------------------------------------------------------
# Motion


class Flow(Detector):
    """Dense optical flow (DIS), plus a handful of whole-frame summaries.

    ``flow`` is the ``(H, W, 2)`` field in *pixels per frame*; everything else is normalized.
    """

    name = "flow"
    color = (255, 80, 255)

    def __init__(self, preset: int = cv2.DISOPTICAL_FLOW_PRESET_MEDIUM):
        self._dis = cv2.DISOpticalFlow.create(preset)
        self._dis.setUseSpatialPropagation(True)
        self.reset()

    def reset(self) -> None:
        self._prev = None
        self._flow = None

    def __call__(self, frame: Frame) -> dict:
        gray = _gray(frame.image)
        if self._prev is None or self._prev.shape != gray.shape:
            self._prev, self._flow = gray, None
        self._flow = self._dis.calc(self._prev, gray, self._flow)
        self._prev = gray

        u, v = self._flow[..., 0], self._flow[..., 1]
        magnitude = cv2.magnitude(u, v)
        h, w = gray.shape
        return dict(
            flow=self._flow,
            mean_motion=np.array([u.mean() / w, v.mean() / h], np.float32),
            motion_centroid=_centroid(magnitude),
            brightness_centroid=_centroid(gray),
            mean_bgr=np.asarray(cv2.mean(frame.image)[:3], np.float32),
        )

    @staticmethod
    def hsv(flow: np.ndarray, gain: float = 4.0) -> np.ndarray:
        """The usual flow picture: hue is direction, saturation is speed."""
        fx, fy = flow[..., 0], flow[..., 1]
        hsv = np.empty(flow.shape[:2] + (3,), np.uint8)
        hsv[..., 0] = ((np.arctan2(fy, fx) + np.pi) * (90 / np.pi)).astype(np.uint8)
        hsv[..., 1] = np.minimum(np.hypot(fx, fy) * gain, 255).astype(np.uint8)
        hsv[..., 2] = 255
        return cv2.cvtColor(hsv, cv2.COLOR_HSV2BGR)

    def draw(self, image: np.ndarray, result: dict, step: int = 16, gain: float = 3.0) -> np.ndarray:
        """Motion arrows on a ``step``-pixel grid; :meth:`hsv` is the whole-frame picture."""
        flow = result["flow"]
        h, w = flow.shape[:2]
        for y in range(step // 2, h, step):
            for x in range(step // 2, w, step):
                fx, fy = flow[y, x]
                if fx * fx + fy * fy > 0.25:
                    cv2.arrowedLine(image, (x, y), (int(x + fx * gain), int(y + fy * gain)), self.color, 1,
                                    cv2.LINE_AA, tipLength=0.3)
        return image


class Tracks(Detector):
    """Lucas–Kanade point tracks with a forward–backward check.

    ``tracks`` is a list of ``(n, 2)`` arrays, the last ``length`` positions of each live point,
    newest last; ``ids`` gives each track a number that stays with it for its whole life.
    """

    name = "tracks"
    color = (80, 255, 50)

    def __init__(self, length: int = 20, max_tracks: int = 200, max_corners: int = 5000,
                 quality: float = 0.1, min_distance: int = 7):
        self.length = length
        self.max_tracks = max_tracks
        self._lk = dict(winSize=(15, 15), maxLevel=2,
                        criteria=(cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 10, 0.03))
        self._features = dict(maxCorners=max_corners, qualityLevel=quality,
                              minDistance=min_distance, blockSize=7)
        self.reset()

    def reset(self) -> None:
        self._prev = None
        self._tracks: list[list[tuple[float, float]]] = []
        self._ids: list[int] = []
        self._next_id = 0

    def __call__(self, frame: Frame) -> dict:
        gray = _gray(frame.image)
        if self._prev is not None and self._prev.shape == gray.shape and self._tracks:
            p0 = np.float32([t[-1] for t in self._tracks]).reshape(-1, 1, 2)
            p1, _, _ = cv2.calcOpticalFlowPyrLK(self._prev, gray, p0, None, **self._lk)
            back, _, _ = cv2.calcOpticalFlowPyrLK(gray, self._prev, p1, None, **self._lk)
            good = np.abs(p0 - back).reshape(-1, 2).max(-1) < 1
            tracks, ids = [], []
            for t, i, (x, y), ok in zip(self._tracks, self._ids, p1.reshape(-1, 2), good):
                if ok:
                    t.append((float(x), float(y)))
                    del t[:-self.length]
                    tracks.append(t)
                    ids.append(i)
            self._tracks, self._ids = tracks, ids

        if len(self._tracks) < self.max_tracks:
            mask = np.full_like(gray, 255)
            for x, y in (t[-1] for t in self._tracks):
                cv2.circle(mask, (int(x), int(y)), 5, 0, -1)
            corners = cv2.goodFeaturesToTrack(gray, mask=mask, **self._features)
            if corners is not None:
                for x, y in corners.reshape(-1, 2)[: self.max_tracks - len(self._tracks)]:
                    self._tracks.append([(float(x), float(y))])
                    self._ids.append(self._next_id)
                    self._next_id += 1

        self._prev = gray
        scale = _scale(frame.width, frame.height)
        return dict(tracks=[np.float32(t) * scale for t in self._tracks],
                    ids=np.asarray(self._ids, np.int64))

    def draw(self, image: np.ndarray, result: dict) -> np.ndarray:
        lines = [_px(t, image) for t in result["tracks"]]
        cv2.polylines(image, lines, False, self.color, 1, cv2.LINE_AA)
        for t in lines:
            cv2.circle(image, tuple(int(v) for v in t[-1]), 2, self.color, -1, cv2.LINE_AA)
        return image


# ---------------------------------------------------------------------------------------------
# Foreground


class _Foreground(Detector):
    color = (20, 140, 255)

    def __init__(self, contours: bool, min_area: float):
        self.contours = contours
        self.min_area = min_area

    def _result(self, mask: np.ndarray) -> dict:
        out = dict(mask=mask, centroid=_centroid(mask), coverage=float(np.count_nonzero(mask)) / mask.size)
        if self.contours:
            out["contours"] = contours_of(mask, self.min_area)
        return out

    def draw(self, image: np.ndarray, result: dict) -> np.ndarray:
        image[result["mask"] == 0] //= 3
        if "contours" in result:
            cv2.polylines(image, [_px(c, image) for c in result["contours"]], True, self.color, 1, cv2.LINE_AA)
        return image


class Background(_Foreground):
    """Background subtraction; ``mask`` is ``uint8`` 0/255 where the frame differs from the model.

    ``kind`` is ``"MOG2"`` or ``"KNN"`` (core OpenCV) or one of the contrib ``cv2.bgsegm``
    subtractors: ``"GMG"``, ``"CNT"``, ``"GSOC"``, ``"LSBP"``, ``"MOG"``. The old pythonhead default
    was GMG, which needs ~120 frames to learn before its mask means anything.
    """

    name = "background"

    def __init__(self, kind: str = "MOG2", contours: bool = False, min_area: float = 0.0, open_px: int = 3):
        super().__init__(contours, min_area)
        self.kind = kind
        self._kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (open_px, open_px)) if open_px else None
        self.reset()

    def reset(self) -> None:
        core = {"MOG2": cv2.createBackgroundSubtractorMOG2, "KNN": cv2.createBackgroundSubtractorKNN}
        if self.kind in core:
            self._model = core[self.kind]()
        else:
            self._model = getattr(cv2.bgsegm, f"createBackgroundSubtractor{self.kind}")()

    def __call__(self, frame: Frame) -> dict:
        mask = self._model.apply(frame.image)
        mask = np.where(mask > 127, 255, 0).astype(np.uint8)  # MOG2/KNN mark shadows 127
        if self._kernel is not None:
            mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, self._kernel)
        return self._result(mask)


class LumaKey(_Foreground):
    """Foreground is whatever is darker than ``threshold`` (or brighter, with ``below=False``)."""

    name = "luma"

    def __init__(self, threshold: float = 128, below: bool = True, contours: bool = False, min_area: float = 0.0):
        super().__init__(contours, min_area)
        self.threshold = threshold
        self.below = below

    def __call__(self, frame: Frame) -> dict:
        mode = cv2.THRESH_BINARY_INV if self.below else cv2.THRESH_BINARY
        _, mask = cv2.threshold(_gray(frame.image), self.threshold, 255, mode)
        return self._result(mask)


# ---------------------------------------------------------------------------------------------
# Shapes


class Lines(Detector):
    """Straight segments (OpenCV's fast line detector); ``segments`` is ``(N, 2, 2)``."""

    name = "lines"
    color = (255, 160, 60)

    def __init__(self, merge: bool = True, distance_threshold: float = 10, length_threshold: int = 10):
        self._fld = cv2.ximgproc.createFastLineDetector(
            length_threshold=length_threshold, distance_threshold=distance_threshold, do_merge=merge)

    def __call__(self, frame: Frame) -> dict:
        found = self._fld.detect(_gray(frame.image))
        if found is None:
            return dict(segments=np.zeros((0, 2, 2), np.float32))
        return dict(segments=found.reshape(-1, 2, 2) * _scale(frame.width, frame.height))

    def draw(self, image: np.ndarray, result: dict) -> np.ndarray:
        cv2.polylines(image, list(_px(result["segments"], image)), False, self.color, 1, cv2.LINE_AA)
        return image


class Circles(Detector):
    """Hough circles; ``circles`` is ``(N, 3)`` of ``cx, cy, r`` (``r`` divided by the width)."""

    name = "circles"
    color = (60, 60, 255)

    def __init__(self, min_radius: int = 5, max_radius: int = 50, min_distance: int = 10,
                 edge_threshold: float = 150, accumulator_threshold: float = 30):
        self.params = dict(dp=1, minDist=min_distance, param1=edge_threshold, param2=accumulator_threshold,
                           minRadius=min_radius, maxRadius=max_radius)

    def __call__(self, frame: Frame) -> dict:
        found = cv2.HoughCircles(cv2.medianBlur(_gray(frame.image), 5), cv2.HOUGH_GRADIENT, **self.params)
        if found is None:
            return dict(circles=np.zeros((0, 3), np.float32))
        w, h = frame.width, frame.height
        return dict(circles=found.reshape(-1, 3) * np.float32([1 / w, 1 / h, 1 / w]))

    def draw(self, image: np.ndarray, result: dict) -> np.ndarray:
        w = image.shape[1]
        for cx, cy, r in result["circles"]:
            centre = (int(cx * w), int(cy * image.shape[0]))
            cv2.circle(image, centre, int(r * w), self.color, 2, cv2.LINE_AA)
            cv2.circle(image, centre, 2, self.color, 3, cv2.LINE_AA)
        return image


# ---------------------------------------------------------------------------------------------
# Landmarks (mediapipe)


def _mediapipe():
    try:
        import mediapipe as mp
    except ImportError as e:
        raise ImportError("the face, pose and hand detectors need mediapipe: `uv sync --inexact --group cv`") from e
    return mp


def _connections(items) -> np.ndarray:
    return np.array([(c.start, c.end) for c in items], np.int32).reshape(-1, 2)


def _landmarks(groups, points: int) -> np.ndarray:
    """mediapipe's nested landmark lists as an ``(people, points, 3)`` float32 array."""
    if not groups:
        return np.zeros((0, points, 3), np.float32)
    return np.array([[(p.x, p.y, p.z) for p in g] for g in groups], np.float32)


class _Landmarker(Detector):
    """Shared plumbing for mediapipe's landmarkers, run in VIDEO mode on ``frame.time_ms``."""

    connections: np.ndarray
    _key = "points"
    dots = True

    def __init__(self):
        self._landmarker = None
        self._last_ms = -1

    def _create(self, mp):
        raise NotImplementedError

    def _detect(self, frame: Frame):
        mp = _mediapipe()
        if self._landmarker is None:
            self._landmarker = self._create(mp)
        # mediapipe refuses a timestamp that does not increase, which a looping Video would produce.
        t = max(frame.time_ms, self._last_ms + 1)
        self._last_ms = t
        rgb = cv2.cvtColor(frame.image, cv2.COLOR_BGR2RGB)
        return self._landmarker.detect_for_video(mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb), t)

    def reset(self) -> None:
        self.close()

    def close(self) -> None:
        if self._landmarker is not None:
            self._landmarker.close()
            self._landmarker = None
        self._last_ms = -1

    def draw(self, image: np.ndarray, result: dict) -> np.ndarray:
        for person in result[self._key]:
            px = _px(person, image)
            for a, b in self.connections:
                cv2.line(image, tuple(map(int, px[a])), tuple(map(int, px[b])), self.color, 1, cv2.LINE_AA)
            if self.dots:
                for a in np.unique(self.connections):
                    cv2.circle(image, tuple(map(int, px[a])), 2, self.color, -1, cv2.LINE_AA)
        return image


class Face(_Landmarker):
    """Face mesh: ``faces`` is ``(F, 478, 3)``; ``connections`` are the contour edges."""

    name = "face"
    _key = "faces"
    color = (255, 220, 80)
    dots = False

    def __init__(self, max_faces: int = 1):
        super().__init__()
        self.max_faces = max_faces
        mp = _mediapipe()
        self.connections = _connections(mp.tasks.vision.FaceLandmarksConnections.FACE_LANDMARKS_CONTOURS)

    def _create(self, mp):
        from .models import model_path
        v = mp.tasks.vision
        return v.FaceLandmarker.create_from_options(v.FaceLandmarkerOptions(
            base_options=mp.tasks.BaseOptions(model_asset_path=model_path("face")),
            running_mode=v.RunningMode.VIDEO, num_faces=self.max_faces))

    def __call__(self, frame: Frame) -> dict:
        return dict(faces=_landmarks(self._detect(frame).face_landmarks, 478))


class Pose(_Landmarker):
    """Body pose: ``poses`` is ``(P, 33, 3)``, ``visibility`` is ``(P, 33)``.

    ``model`` is ``"heavy"`` (what pythonhead used) or ``"lite"`` (several times faster).
    """

    name = "pose"
    _key = "poses"
    color = (200, 80, 255)

    def __init__(self, model: str = "heavy", max_poses: int = 1):
        super().__init__()
        self.model = model
        self.max_poses = max_poses
        mp = _mediapipe()
        self.connections = _connections(mp.tasks.vision.PoseLandmarksConnections.POSE_LANDMARKS)

    def _create(self, mp):
        from .models import model_path
        v = mp.tasks.vision
        return v.PoseLandmarker.create_from_options(v.PoseLandmarkerOptions(
            base_options=mp.tasks.BaseOptions(model_asset_path=model_path(f"pose_{self.model}")),
            running_mode=v.RunningMode.VIDEO, num_poses=self.max_poses))

    def __call__(self, frame: Frame) -> dict:
        found = self._detect(frame).pose_landmarks
        visibility = np.array([[p.visibility or 0.0 for p in g] for g in found], np.float32).reshape(len(found), 33)
        return dict(poses=_landmarks(found, 33), visibility=visibility)


class Hands(_Landmarker):
    """Hands: ``hands`` is ``(H, 21, 3)``, ``handedness`` a list of ``"Left"``/``"Right"``."""

    name = "hands"
    _key = "hands"
    color = (40, 220, 255)

    def __init__(self, max_hands: int = 2):
        super().__init__()
        self.max_hands = max_hands
        mp = _mediapipe()
        self.connections = _connections(mp.tasks.vision.HandLandmarksConnections.HAND_CONNECTIONS)

    def _create(self, mp):
        from .models import model_path
        v = mp.tasks.vision
        return v.HandLandmarker.create_from_options(v.HandLandmarkerOptions(
            base_options=mp.tasks.BaseOptions(model_asset_path=model_path("hand")),
            running_mode=v.RunningMode.VIDEO, num_hands=self.max_hands))

    def __call__(self, frame: Frame) -> dict:
        found = self._detect(frame)
        return dict(hands=_landmarks(found.hand_landmarks, 21),
                    handedness=[h[0].category_name if h else "" for h in found.handedness])


def edges(points: np.ndarray, connections: np.ndarray) -> list[np.ndarray]:
    """Landmarks as two-point polylines, one per connection: ``(P, N, 3)`` → a list of ``(2, 2)``.

    This is the shape :meth:`fieldcv.field.SharedLines.publish` and ``FLine`` want for a skeleton.
    """
    return [person[pair, :2] for person in points for pair in connections]
