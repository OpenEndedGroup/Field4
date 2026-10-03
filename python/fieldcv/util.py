from __future__ import annotations

import numpy as np


def jsonable(value):
    """A result dict made JSON-serializable: arrays become lists, frames and dense fields are dropped.

    ``mask`` and ``flow`` are per-pixel and would make every line megabytes long, so they are left
    out; everything else survives.
    """
    if isinstance(value, dict):
        return {k: jsonable(v) for k, v in value.items() if k not in ("frame", "mask", "flow")}
    if isinstance(value, (list, tuple)):
        return [jsonable(v) for v in value]
    if isinstance(value, np.ndarray):
        return value.tolist()
    if isinstance(value, np.generic):
        return value.item()
    return value
