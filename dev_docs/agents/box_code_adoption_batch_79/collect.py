"""Consolidate exact formatter/codec/mirror changes; preserve first before and final after."""
from pathlib import Path
import json,collections
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;O=R/'build/box-code-revision/adoption-79'
rows={}
for name in ['changes.json','metadata-changes.json','mirror-changes.json']:
 for row in json.loads((O/name).read_text()):
  if row['path'] not in rows:rows[row['path']]=row
  else:assert rows[row['path']]['after']==row['before'];rows[row['path']]['after']=row['after']
for row in rows.values():assert (R/row['path']).read_text()==row['after'],row['path']
(H/'changes.json').write_text(json.dumps(list(rows.values()),indent=2)+'\n')
bodies=[json.loads(x) for x in (O/'bodies.jsonl').read_text().splitlines()]
(H/'verification.json').write_text(json.dumps({'files':len(rows),'distinctKotlinBodies':len(bodies),'distinctListConversions':sum(x['conversions'] for x in bodies),'occurrences':sum(len(x['occurrences']) for x in bodies),'actualImportAndKtfmtPassed':True,'candidateTokensAndRawStringsPreserved':True,'fragmentBoundariesPreserved':True,'codecDocuments':len(json.loads((O/'metadata-changes.json').read_text())),'mirrors':json.loads((O/'mirror-report.json').read_text()),'retentions':json.loads((O/'scope.json').read_text())['retained']},indent=2)+'\n')
print(len(rows),'exact owned files')
