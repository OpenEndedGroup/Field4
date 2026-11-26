"""Execute the actual Slinky starting place with default and edited initial controls."""
from pathlib import Path
import re
import shlex
import subprocess
import sys

import yaml

root = Path(__file__).resolve().parents[3]
source = root / "dev_docs/agents/transcripts/slinky_escalator.yaml"
out = root / "build/slinky-catchup-proof"
out.mkdir(parents=True, exist_ok=True)
args = [sys.executable, "tools/field_backdoor.py", "--json", "--port", "9788", "--document", "slinky_catchup_proof"]
for index, (depth, period, slow) in enumerate([(1.5, 0.0, 5), (1.8, 0.09, 3)]):
    transcript = yaml.safe_load(source.read_text())
    box = transcript["operations"][0]["create"]
    code = box["properties"]["code"]
    if index:
        for name, value in (("treadDepth", f"{depth}f"), ("stepPeriod", f"{period}f"), ("slowMotion", str(slow))):
            code, changes = re.subn(rf"(?m)^var {name} = .*$", f"var {name} = {value}", code)
            assert changes == 1, name
    box["properties"]["code"] = code.replace(
        "shown = maxOf(shown, now - 0.1)",
        f"""shown = maxOf(shown, now - 0.1)
    if (scene.time > 0f && !proved) {{
        check(abs(scene.stairs.run - {depth}f) < 1e-5f)
        check(abs(scene.stepPeriod - {period}f) < 1e-5f)
        check(slowMotion == {slow})
        check(scene.lines().isNotEmpty())
        proved = true
        println("SLINKY_CATCHUP_{index}_PASSED")
    }}""",
    ).replace("var shown = 0.0", "var shown = 0.0\nvar proved = false")
    uid = f"slinky_proof_{index}"
    box["box"] = uid
    for op in transcript["operations"][1:]:
        options = next(iter(op.values()))
        if "box" in options:
            options["box"] = uid
    path = out / f"case-{index}.yaml"
    path.write_text(yaml.safe_dump(transcript, sort_keys=False))
    args += ["--command", f"macro write slinky_{index} --file {shlex.quote(str(path))}",
             "--command", f"macro run slinky_{index} --timeout 180"]
log = out / "behavior.log"
with log.open("w") as stream:
    subprocess.run(args, cwd=root, stdout=stream, stderr=subprocess.STDOUT, check=True)
for index in range(2):
    assert f'"html": "SLINKY_CATCHUP_{index}_PASSED' in log.read_text(), log
print("Slinky simulated and produced FLines at default and edited tread, period and playback settings.")
