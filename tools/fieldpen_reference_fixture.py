"""Write the torch fixtures `PenModelTest` compares Kotlin's learned pen with.

    uv run --project . --group pen python tools/fieldpen_reference_fixture.py

For the bundled Distill model and a small random two-layer model (also exported as a
`.fieldpen.json`, in float32 and float16), records the head's raw output after the
start-of-sequence step and after each of a fixed list of displacements, into
`src/jvmTest/resources/afield/pen/`.
"""

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "python"))

import math  # noqa: E402

import torch  # noqa: E402

import fieldpen  # noqa: E402
from fieldpen import Config, HandwritingModel  # noqa: E402
from fieldpen.sense import with_sense  # noqa: E402

OUT = ROOT / "src/jvmTest/resources/afield/pen"
INPUTS = [[0.2, 0.1, 0], [0.1, -0.3, 0], [-0.2, 0.25, 1], [1.5, 0.0, 0],
          [0.3, 0.3, 0], [0.0, -0.4, 0], [0.25, 0.1, 1], [0.8, -0.2, 0]]


def run(model):
    state, out = model.zero_state(), []
    with torch.no_grad():
        for x in [[0.0, 0.0, 0.0]] + INPUTS:
            raw, state = model(torch.tensor(x, dtype=torch.float32)[None, None], state)
            out.append([round(v, 7) for v in raw[0, 0].tolist()])
    return dict(inputs=INPUTS, raw=out)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "distill_reference.json").write_text(json.dumps(run(fieldpen.load_model())))
    torch.manual_seed(3)
    tiny = HandwritingModel(Config(8, 3, 2)).eval()
    fieldpen.export_for_field(tiny, OUT / "tiny.fieldpen.json")
    fieldpen.export_for_field(tiny, OUT / "tiny.f16.fieldpen.json", half=True)
    (OUT / "tiny_reference.json").write_text(json.dumps(run(tiny)))

    # A sensing pen: Kotlin must compute what it sees from the displacements alone and agree with
    # torch, which is given the sense columns `fieldpen.sense` computes. The path circles twice, so
    # the second lap sees the first in every direction, then lifts and crosses it.
    torch.manual_seed(5)
    seeing = HandwritingModel(Config(8, 3, 1, inputs=21, sense_sectors=16, sense_radius=3.0, sense_recent=8)).eval()
    fieldpen.export_for_field(seeing, OUT / "seeing.fieldpen.json")
    deltas, last = [[0.0, 0.0, 0.0]], (1.5, 0.0)
    for i in range(1, 70):
        a = i * 2 * math.pi / 32
        r = 1.5 - i * 0.004
        p = (r * math.cos(a), r * math.sin(a))
        deltas.append([p[0] - last[0], p[1] - last[1], 1.0 if i == 60 else 0.0])
        last = p
    deltas += [[0.3, 0.05, 0.0]] * 12
    rows = with_sense(deltas, 16, 3.0, 8)
    state, out = seeing.zero_state(), []
    with torch.no_grad():
        for x in [[0.0] * 21] + rows.tolist():
            raw, state = seeing(torch.tensor(x, dtype=torch.float32)[None, None], state)
            out.append([round(v, 7) for v in raw[0, 0].tolist()])
    (OUT / "seeing_reference.json").write_text(json.dumps(dict(
        inputs=deltas, sense=[[round(v, 7) for v in r[3:]] for r in rows.tolist()], raw=out)))


if __name__ == "__main__":
    main()
