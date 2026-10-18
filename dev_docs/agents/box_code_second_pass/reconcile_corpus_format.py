"""Distinguish Kotlin layout punctuation from executable changes in formatter output."""
from pathlib import Path
from collections import Counter
import importlib.util,json,re,yaml
R=Path(__file__).resolve().parents[3];OUT=R/'build/box-code-revision/mechanical-final'
spec=importlib.util.spec_from_file_location('strict_formatter',R/'dev_docs/agents/box_code_first_pass/mechanical.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
bodies={x['language']+'-'+x['sha256']:x for x in map(json.loads,(OUT/'bodies.jsonl').read_text().splitlines())};results=[]
for row in map(json.loads,(OUT/'mechanical/results.jsonl').read_text().splitlines()):
 if row['status'] in {'parse-review','language-review'}:results.append(row);continue
 body=bodies[row['key']];path=OUT/'mechanical/inputs'/(row['key']+('_code_.kt' if row['language']=='kotlin' else '.yaml'))
 code=path.read_text() if row['language']=='kotlin' else yaml.safe_load(path.read_text())['fragment'];before=body['code'];restorations=0
 if row['language']=='kotlin':
  raw=re.compile(r'"""[\s\S]*?"""');a=list(raw.finditer(before));b=list(raw.finditer(code));assert len(a)==len(b)
  for x,y in reversed(list(zip(a,b))):
   if x[0]!=y[0]:code=code[:y.start()]+x[0]+code[y.end():];restorations+=1
  left=[t for t in m.tokens(before,'kotlin') if t not in {',',';'}];right=[t for t in m.tokens(code,'kotlin') if t not in {',',';'}]
  path.write_text(code)
 else:left=m.tokens(before,'glsl');right=m.tokens(code,'glsl')
 row['rawLiteralsRestored']=restorations;row['code_tokens_preserved']=left==right
 row['fragment_markers_preserved']=m.markers(before)==m.markers(code)
 row['status']=('already-formatted' if before.rstrip('\n')==code.rstrip('\n') else 'candidate') if left==right and row['fragment_markers_preserved'] else 'preservation-review'
 results.append(row)
(OUT/'mechanical/reconciled-results.jsonl').write_text(''.join(json.dumps(x)+'\n' for x in results))
summary={'bodies':len(results),'statuses':dict(Counter(x['status'] for x in results)),'rawLiteralsRestored':sum(x.get('rawLiteralsRestored',0) for x in results),'rule':'Kotlin formatter may change commas/semicolons; all other executable tokens match and raw literals are restored exactly. GLSL punctuation remains exact.'}
(OUT/'mechanical/reconciled-summary.json').write_text(json.dumps(summary,indent=2)+'\n');print(json.dumps(summary))
