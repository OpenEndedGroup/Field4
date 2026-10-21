"""Check approved source edits, overload-safe normalization and saved graph preservation."""
from pathlib import Path
import json,re,sys
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;sys.path.insert(0,str(H.parent/'box_code_adoption_batch_7'))
from verify_tokens import tokens
from literals import normalize
PLAIN=re.compile(r':code\s+("(?:\\.|[^"\\])*")');INLINE=re.compile(r'"code"\s+#afield/file-inline\s*\{"path"\s+("(?:\\.|[^"\\])*")\s+"inline"\s+("(?:\\.|[^"\\])*")');records=[]
for row in json.loads((H/'changes.json').read_text()):
 p=R/row['path'];before=row['after'];after=p.read_text();pattern,group=(INLINE,2) if 'fline_examples' in row['path'] else (PLAIN,1);a=list(pattern.finditer(before));b=list(pattern.finditer(after));assert len(a)==len(b);n=0;variadic=0;retained=0
 def strip(text,ms):
  for m in reversed(ms):text=text[:m.start(group)]+'"<CODE>"'+text[m.end(group):]
  return text
 assert strip(before,a)==strip(after,b),(p,'IDs, graph, inline paths and non-code properties')
 for u,v in zip(a,b):
  expected,collections,varargs=normalize(json.loads(u[group]));actual=json.loads(v[group]);assert tokens(expected)==tokens(actual),(p,'source tokens')
  assert re.findall(r'(?m)^\s*//%-[^\n]*',expected)==re.findall(r'(?m)^\s*//%-[^\n]*',actual)
  n+=collections;variadic+=varargs;retained+=len(re.findall(r'\blistOf\s*\(',actual))
  assert not re.search(r'\bscreenshot\("/Users/marc/',actual)
 records.append({'path':row['path'],'kotlinBodies':len(a),'constructorChanges':n,'linesVarargSites':variadic,'overloadSensitiveListCallsRetained':retained,'sourceAndContainerPreserved':True})
(H/'verification.json').write_text(json.dumps({'sources':records,'baseline':'adc1eb308','retainedListCallsReason':'Kotlin collection literals have no unique expected type in overloaded List.plus expressions; explicit listOf retains the original inferred type.'},indent=2)+'\n');print(json.dumps(records))
