# 1 · the webcam, and everything fieldcv can find in it
#
# Run this once. It starts a Pipeline on a worker thread and returns straight away; the
# frames and everything detected in them go to shared memory under the names below, where
# the graphics boxes on the right read them with NumpyShared.image(...) / .lines(...).
import os, math
import cv2, numpy as np
import fieldcv as cv4
from fieldcv import field

H, W = 360, 640                                    # what we publish; the camera is resized to fit

def open_input():
    try:
        return cv4.Camera(0, width=1280, height=720, mirror=True)
    except RuntimeError as e:
        movie = os.path.expanduser("~/Desktop/pythonhead/015.mp4")
        print(f"{e} - if macOS asked about the camera, allow it and re-run. Using {movie} meanwhile.")
        return cv4.Video(movie, loop=True)

# every detector, built once; `use(...)` in box 2 chooses which ones run
detectors = dict(
    tracks=cv4.Tracks(length=15, max_tracks=300),
    pose=cv4.Pose(model="lite"),
    hands=cv4.Hands(max_hands=2),
    face=cv4.Face(max_faces=2),
    flow=cv4.Flow(),
    background=cv4.Background("MOG2", contours=True, min_area=0.002),
)

camera = field.image("camera", H, W)               # the picture, full window
inset = field.image("inset", H, W)                 # a second picture, top right: see `inset_view`
channels = {n: field.lines(n) for n in ["tracks", "skeleton", "hands", "face", "contours", "strokes"]}

# --- the inset: any function of the frame and the results that returns an image -----------
def edges(r):
    return cv2.Canny(cv2.cvtColor(r["frame"].image, cv2.COLOR_BGR2GRAY), 60, 140)

def motion(r):
    return cv4.Flow.hsv(r["flow"]) if "flow" in r else edges(r)

def mask(r):
    return r["mask"] if "mask" in r else edges(r)

inset_view = edges

# --- pinch to draw: thumb tip (4) near index tip (8) paints, opening your fingers lifts the pen
strokes, pen_down = [], False

def draw_with_fingers(r):
    global pen_down
    hands = r.get("hands", np.zeros((0, 21, 3)))
    if len(hands) == 0:
        pen_down = False
        return
    tip, thumb = hands[0][8, :2], hands[0][4, :2]
    pinched = math.dist(tip * (W, H), thumb * (W, H)) < 0.06 * W
    if pinched and not pen_down:
        strokes.append([])
    if pinched:
        strokes[-1].append((tip + thumb) / 2)
    pen_down = pinched

def clear():
    strokes.clear()

# --- called on the worker thread for every frame ------------------------------------------
def publish(r):
    camera.write(r["frame"].image)
    inset.write(inset_view(r))
    draw_with_fingers(r)
    channels["tracks"].publish(r.get("tracks", []))
    channels["skeleton"].publish(cv4.edges(r["poses"], detectors["pose"].connections) if "poses" in r else [])
    channels["hands"].publish(cv4.edges(r["hands"], detectors["hands"].connections) if "hands" in r else [])
    channels["face"].publish(cv4.edges(r["faces"], detectors["face"].connections) if "faces" in r else [])
    channels["contours"].publish([np.vstack([c, c[:1]]) for c in r.get("contours", [])])
    channels["strokes"].publish([np.asarray(s) for s in strokes if len(s) > 1])

def use(*names):
    """Run just these detectors, e.g. use("hands", "face"). The pipeline keeps running."""
    p.detectors = [detectors[n] for n in names]
    return p

p = cv4.Pipeline(open_input(), on_result=publish)
use("tracks", "pose", "hands", "face")
p.start()
