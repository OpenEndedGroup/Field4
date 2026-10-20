"""Refresh saved inline sources through Field's codec, then matching sidecar mirrors."""
from pathlib import Path
import ast
import json
import shlex
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
OUT = ROOT/'build/repl-variable-rebinding'
sys.path.insert(0,str(ROOT))
from tools.check_box_idioms import MIRROR_FENCE, refresh_mirror_digests

rows = json.loads((OUT/'example-changes.json').read_text())
requests = []
for row in rows:
    path = ROOT/row['path']
    if path.suffix == '.kt' and (path.parent/'metadata.edn').is_file():
        requests.append(dict(directory=str(path.parent),sidecars=[path.name],updates=[]))
request = OUT/'metadata-request.json'
request.write_text(json.dumps(requests,indent=2)+'\n')
codec_tree = ast.parse((HERE.parent/'box_code_adoption_batch_79/codec.py').read_text())
code = next(ast.literal_eval(node.value) for node in codec_tree.body
    if isinstance(node,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='CODE' for t in node.targets))
code = code.replace('REQUEST', json.dumps(str(request)))
before = {str(Path(r['directory'])/'metadata.edn'): (Path(r['directory'])/'metadata.edn').read_text() for r in requests}
commands = ['uv','run','--group','backdoor','python','tools/field_backdoor.py','--json','--port','9739',
    '--command','new-box --name RebindingSourceCodec','--command','run @last --value '+shlex.quote(code)]
result = subprocess.run(commands,cwd=ROOT,capture_output=True,text=True)
(OUT/'codec.jsonl').write_text(result.stdout)
text = result.stdout;decoder = json.JSONDecoder();verdicts=[]
while text.strip():
    text=text.lstrip();value,end=decoder.raw_decode(text);verdicts.append(value);text=text[end:]
assert result.returncode==0 and verdicts and all(v.get('ok') for v in verdicts), result.stderr[-1000:]+result.stdout[-2000:]
metadata = [{'path':str(Path(p).relative_to(ROOT)),'before':old,'after':Path(p).read_text()} for p,old in before.items() if old!=Path(p).read_text()]
(OUT/'metadata-changes.json').write_text(json.dumps(metadata,indent=2)+'\n')
source={str((ROOT/r['path']).resolve()):r for r in rows}
mirrors=[];skipped=[]
for directory in {Path(p).parent for p in source}:
    path=directory/'document.md'
    if not path.is_file():continue
    old=path.read_text();edits=[]
    for match in MIRROR_FENCE.finditer(old):
        sidecar=(path.parent/match['path']).resolve();row=source.get(str(sidecar))
        if not row:continue
        if match['body'].rstrip('\n')!=row['before'].rstrip('\n'):
            skipped.append(str(sidecar.relative_to(ROOT)));continue
        edits.append((match.start('body'),match.end('body'),sidecar.read_text().rstrip()+'\n'))
    new=old
    for start,end,value in reversed(edits):new=new[:start]+value+new[end:]
    if new!=old:
        path.write_text(new);refresh_mirror_digests(path)
        mirrors.append({'path':str(path.relative_to(ROOT)),'before':old,'after':path.read_text()})
(OUT/'mirror-changes.json').write_text(json.dumps(mirrors,indent=2)+'\n')
print(json.dumps(dict(codecDocuments=len(metadata),mirrors=len(mirrors),retainedDrift=skipped)))
