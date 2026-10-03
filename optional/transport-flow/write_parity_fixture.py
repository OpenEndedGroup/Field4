"""Regenerate the small cross-framework oracle explicitly; tests never write it."""
import argparse
import json
from pathlib import Path
import tempfile

import numpy as np
import torch

from transport_flow import Options, TransportFlow

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("output", type=Path)
args = parser.parse_args()
torch.manual_seed(42)
model = TransportFlow(Options(width=5, depth=2), 2)
points = np.array([[-.7, .4], [.1, -.5], [.9, .3]], dtype=np.float32)
with tempfile.TemporaryDirectory() as temporary:
    export = Path(temporary) / "model.json"
    model.export(export)
    data = {"model": json.loads(export.read_text()), "points": points.tolist(), "cases": []}
for t, tau, start in ((0., 0., 0.), (.5, .25, 0.), (1., 1., 0.), (1.2, -.1, 0.), (0., .7, 1.)):
    data["cases"].append({"transportTime": t, "movieTime": tau, "fromTransportTime": start,
                          "velocity": model.velocity(points, t, tau).tolist(),
                          "transport": model.transport(points, t, tau, steps=32, start_time=start).tolist()})
args.output.parent.mkdir(parents=True, exist_ok=True)
torch.manual_seed(43)
static = TransportFlow(Options(width=5, depth=2))
with tempfile.TemporaryDirectory() as temporary:
    export = Path(temporary) / "model.json"
    static.export(export)
    data["static"] = {"model": json.loads(export.read_text()),
                      "velocity": static.velocity(points, .6).tolist(),
                      "transport": static.transport(points, .6).tolist()}
args.output.write_text(json.dumps(data, indent=2, allow_nan=False) + "\n")
