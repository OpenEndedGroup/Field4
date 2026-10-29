"""Refresh matching sidecar mirrors and their existing Field source digests only."""
from pathlib import Path
import importlib.util,json,sys
R=Path(__file__).resolve().parents[3];OUT=R/'build/box-code-revision/adoption-79';sys.path.insert(0,str(R))
from tools.check_box_idioms import MIRROR_FENCE,refresh_mirror_digests
receipt=OUT/'mirror-changes.json'
if receipt.exists():
 records=json.loads(receipt.read_text())
 assert all((R/x['path']).read_text()==x['after'] for x in records),'Mirror changed after recorded refresh'
 print('Reusing completed mirror receipt for',len(records),'files');sys.exit(0)
rows=json.loads((OUT/'changes.json').read_text());source={str((R/x['path']).resolve()):x for x in rows};mirrors=sorted({(R/x['path']).parent/'document.md' for x in rows if ((R/x['path']).parent/'document.md').is_file()});changes=[];skipped=[]
for path in mirrors:
 before=path.read_text();edits=[]
 for match in MIRROR_FENCE.finditer(before):
  sidecar=(path.parent/match['path']).resolve();row=source.get(str(sidecar))
  if row is None:continue
  if match['body'].rstrip('\n')!=row['before'].rstrip('\n'):
   skipped.append({'mirror':str(path.relative_to(R)),'sidecar':match['path'],'reason':'pre-existing mirror drift'});continue
  edits.append((match.start('body'),match.end('body'),sidecar.read_text().rstrip()+'\n'))
 text=before
 for a,b,code in reversed(edits):text=text[:a]+code+text[b:]
 if text!=before:
  path.write_text(text);refresh_mirror_digests(path);changes.append({'path':str(path.relative_to(R)),'before':before,'after':path.read_text()})
(OUT/'mirror-changes.json').write_text(json.dumps(changes,indent=2)+'\n');(OUT/'mirror-report.json').write_text(json.dumps({'files':len(changes),'retainedDrift':skipped},indent=2)+'\n');print('mirror files refreshed',len(changes),'pre-existing drift retained',len(skipped))
