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

import torch  # noqa: E402

import fieldpen  # noqa: E402
from fieldpen import Config, HandwritingModel  # noqa: E402

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


if __name__ == "__main__":
    main()
