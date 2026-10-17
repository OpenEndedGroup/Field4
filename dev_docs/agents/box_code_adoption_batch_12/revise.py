"""Apply the reviewed tutorial explanations and house collection syntax."""
from pathlib import Path
import json,subprocess,sys,importlib.util
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('literals',HERE.parent/'box_code_adoption_batch_6/literals.py');lit=importlib.util.module_from_spec(spec);spec.loader.exec_module(lit)
rows=json.loads((HERE/'changes.json').read_text())
for path in sorted({r['path'] for r in rows}):
 p=ROOT/path;s=p.read_text()
 for r in [r for r in rows if r['path']==path]:
  assert s.count(r['old'])==1,(path,r['old']);s=s.replace(r['old'],r['new'])
 p.write_text(s)
 subprocess.run([sys.executable,str(HERE.parent/'box_code_adoption_batch_6/literals.py'),path],cwd=ROOT,check=True)
