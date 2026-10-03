# 2 · things to try - select a line and run it (Alt-Enter runs the line under the cursor)

use("hands")                                # just hands: pinch thumb and index to draw
clear()                                     # wipe the strokes

use("hands", "face", "pose")                # landmarks only
use("tracks", "flow")                       # motion: point tracks plus dense optical flow
inset_view = motion                         # ...and show the flow field in the inset

use("background", "tracks")                 # what's moving against the background; outlines in orange
inset_view = mask

# the inset is any function of the results that returns an image: write your own
inset_view = lambda r: cv2.applyColorMap(cv2.cvtColor(r["frame"].image, cv2.COLOR_BGR2GRAY), cv2.COLORMAP_TURBO)
inset_view = lambda r: cv2.stylization(r["frame"].image, sigma_s=40, sigma_r=0.3)   # slow: watch fps in box 3

inset_view = edges
use("tracks", "pose", "hands", "face")      # back to the start
