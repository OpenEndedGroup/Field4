"""Use the actual editor import pass and ktfmt/GLSL formatter on explicit source paths."""
from pathlib import Path
import json,subprocess
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent
paths=sorted({r['path'] for r in json.loads((HERE/'changes.json').read_text())})
subprocess.run([str(ROOT/'gradlew'),'formatBoxCode','--args=--imports '+' '.join(paths)],cwd=ROOT,check=True)
print(len(paths),'explicit sources formatted')
