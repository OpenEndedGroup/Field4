"""What a sensing pen sees of the ink around it.

The canonical definition; `afield.ai.learning.pen.PenSense` in Kotlin computes the same numbers and
`PenModelTest` holds the two together.

At point `i` of a drawing (absolute positions in model units, in the order they were drawn), the
pen sees every earlier point except its own last `recent` ones: points `0 .. i - recent`. Around
the pen, `sectors` equal wedges of direction are measured anticlockwise from +x, with sector `k`
covering angles `[-pi + k * 2pi / sectors, -pi + (k + 1) * 2pi / sectors)` of
`atan2(dy, dx)`. Each sector's value is `1 - d / radius` for the nearest visible point in it
within `radius` (so 1 is touching and 0 is out of reach), or 0 if there is none.

Two more values follow the sectors: the way to the middle of everything visible,
`tanh((cx - x) / CENTRE_SCALE)` and the same for y, where `(cx, cy)` is the mean of the visible
points (0 and 0 while there are none). The sectors say what is close; these say where the mass of
the drawing is, which is how a pen on the rim of a shape can tell which side is inside.
"""
from __future__ import annotations

import math

import numpy as np

# model units over which the pull towards the middle of the ink saturates
CENTRE_SCALE = 10.0


def sector_of(dx, dy, sectors):
    """The wedge a direction falls in, as `PenSense.sectorOf` computes it."""
    k = int(math.floor((math.atan2(dy, dx) + math.pi) / (2 * math.pi) * sectors))
    return min(max(k, 0), sectors - 1)


def sense_features(points, sectors=16, radius=3.0, recent=8):
    """`(N, sectors + 2)` float32: row `i` is what the pen sees at `points[i]`."""
    p = np.asarray(points, dtype=np.float64)
    out = np.zeros((len(p), sectors + 2), dtype=np.float32)
    grid = {}
    visible = 0
    sx = sy = 0.0
    for i in range(len(p)):
        # points 0 .. i - recent become visible as the pen reaches i
        while visible <= i - recent:
            gx, gy = math.floor(p[visible, 0] / radius), math.floor(p[visible, 1] / radius)
            grid.setdefault((gx, gy), []).append(visible)
            sx += p[visible, 0]
            sy += p[visible, 1]
            visible += 1
        x, y = p[i]
        if visible:
            out[i, sectors] = math.tanh((sx / visible - x) / CENTRE_SCALE)
            out[i, sectors + 1] = math.tanh((sy / visible - y) / CENTRE_SCALE)
        gx, gy = math.floor(x / radius), math.floor(y / radius)
        row = out[i]
        for cx in (gx - 1, gx, gx + 1):
            for cy in (gy - 1, gy, gy + 1):
                for j in grid.get((cx, cy), ()):
                    dx, dy = p[j, 0] - x, p[j, 1] - y
                    d = math.hypot(dx, dy)
                    if d < radius:
                        k = sector_of(dx, dy, sectors)
                        v = 1.0 - d / radius
                        if v > row[k]:
                            row[k] = v
    return out


def with_sense(deltas, sectors=16, radius=3.0, recent=8):
    """`[dx, dy, lift]` rows with what the pen sees after each point appended: the rows a sensing
    pen is trained on. Positions are the running sum of the displacements, from (0, 0)."""
    # positions summed in float64, as Kotlin's pen sums them: rounding the displacements first can
    # move a point that sits on a sector boundary into the next sector
    d = np.asarray(deltas, dtype=np.float64)
    points = np.cumsum(d[:, :2], axis=0)
    return np.concatenate([d.astype(np.float32), sense_features(points, sectors, radius, recent)], axis=1)
