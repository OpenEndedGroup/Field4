"""Real formatter plus token/string/boundary checks before hash-fenced source application."""
from pathlib import Path
import importlib.util,json,re,subprocess,sys
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;O=R/'build/box-code-revision/adoption-79'
sys.path.insert(0,str(R));sys.path.insert(0,str(H.parent/'box_code_adoption_batch_7'))
def load(name,p):
 s=importlib.util.spec_from_file_location(name,p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
tok=load('tok79',H.parent/'box_code_adoption_batch_7/verify_tokens.py');mech=load('mech79',H.parent/'box_code_first_pass/mechanical.py')
bodies=[json.loads(x) for x in (O/'bodies.jsonl').read_text().splitlines()]
paths=[str((O/'mechanical/inputs'/('kotlin-'+x['sha256']+'_code_.kt')).relative_to(R)) for x in bodies]
with (O/'mechanical/formatter.log').open('w') as log:subprocess.run([str(R/'gradlew'),'formatBoxCode','--args=--imports '+' '.join(paths)],cwd=R,stdout=log,stderr=subprocess.STDOUT,check=True)
log=(O/'mechanical/formatter.log').read_text();records=[]
for body,relative in zip(bodies,paths):
 key='kotlin-'+body['sha256'];p=R/relative;candidate=p.read_text();before=body['candidate']
 skipped=next((line for line in log.splitlines() if line.startswith('skipped ') and relative in line),None)
 record={'key':key,'language':'kotlin','occurrences':body['occurrences']}
 if skipped:record.update(status='parse-review',detail=skipped)
 else:
  raw=re.compile(r'"""[\s\S]*?"""');aa=list(raw.finditer(before));bb=list(raw.finditer(candidate));assert len(aa)==len(bb)
  for x,y in reversed(list(zip(aa,bb))):
   if not re.match(r'\s*\.trim(?:Indent|Margin)\s*\(',before[x.end():]):candidate=candidate[:y.start()]+x[0]+candidate[y.end():]
  assert tok.tokens(before)==tok.tokens(candidate),relative
  assert mech.markers(before)==mech.markers(candidate),relative
  p.write_text(candidate);record.update(status='candidate',code_tokens_preserved=True,fragment_markers_preserved=True,conversions=body['conversions'])
 records.append(record)
(O/'mechanical/reconciled-results.jsonl').write_text(''.join(json.dumps(x)+'\n' for x in records))
apply=load('apply79',H.parent/'box_code_second_pass/apply_corpus_format.py');apply.OUT=O;sys.argv=['apply','--apply'];apply.main()
