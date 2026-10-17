"""Apply hash-checked reviewed drafts, then use collection literals in Kotlin scalars."""
from pathlib import Path
import json,hashlib,subprocess,sys
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent
for row in json.loads((HERE/'drafts.json').read_text()):
 p=ROOT/row['path'];assert hashlib.sha256(p.read_bytes()).hexdigest()==row['beforeSha256'],p;p.write_text(row['after'])
 if p.suffix=='.yaml':subprocess.run([sys.executable,str(HERE.parent/'box_code_adoption_batch_6/literals.py'),str(p.relative_to(ROOT))],cwd=ROOT,check=True)
