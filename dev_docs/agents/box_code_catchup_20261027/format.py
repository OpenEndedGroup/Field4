"""Use actual editor imports/ktfmt/GLSL formatting on the frozen delta, with token fences."""
from pathlib import Path
import argparse,collections,json,re,shlex,subprocess
import session as S
ROOT=S.ROOT;HERE=S.HERE;OUT=S.OUT
TOK=S.load('tokens',HERE.parent/'box_code_first_pass/mechanical.py')
def prepare():
 inventory=json.loads((OUT/'bodies.json').read_text());wanted={x['path'] for x in inventory};old={p:{x['sha256'] for x in inventory if x['path']==p} for p in wanted}
 rows=[];bodies={};hashes={}
 for p in sorted(wanted):
  text=(ROOT/p).read_text();hashes[p]=S.digest(text)
  original=subprocess.check_output(['git','show',f"{json.loads((S.HERE/'scope.json').read_text())['head']}:{p}"],cwd=ROOT,text=True)
  selected={i for i,r in enumerate(S.spans(p,original)) if r['sha256'] in old[p]}
  for i,r in enumerate(S.spans(p,text)):
   if i not in selected or r['language'] not in {'kotlin','glsl'}:continue
   folds=[]
   def carry(m):folds.append(m[0]);return f'/* BOX_CODE_FOLD_{len(folds)-1} */'
   plain=re.sub(r'^[ \t]*:::(?:fold[^\n]*|endfold|)[ \t]*$',carry,r['code'],flags=re.M)
   key=r['language']+'-'+S.digest(plain);target=OUT/'format-inputs'/(key+('_code_.kt' if r['language']=='kotlin' else '.yaml'));target.parent.mkdir(parents=True,exist_ok=True)
   if key not in bodies:
    target.write_text(plain if r['language']=='kotlin' else 'fragment: |\n'+''.join('  '+l+'\n' for l in plain.splitlines()))
    bodies[key]=dict(before=plain,target=str(target.relative_to(ROOT)),language=r['language'],compiled=bool(re.match(r'\s*package\s',plain)))
   rows.append(dict(r,key=key,folds=folds))
 plan=dict(hashes=hashes,rows=rows,bodies=bodies);(OUT/'format-plan.json').write_text(json.dumps(plan,indent=2)+'\n');print(len(rows),'spans',len(bodies),'unique candidates',flush=True)
def run():
 p=json.loads((OUT/'format-plan.json').read_text())
 with (OUT/'format.log').open('w') as log:
  for compiled in [False,True]:
   targets=[x['target'] for x in p['bodies'].values() if x.get('compiled',False)==compiled]
   if not targets:continue
   command=['./gradlew','formatBoxCode','--args='+shlex.join(([] if compiled else ['--imports'])+targets)]
   r=subprocess.run(command,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT)
   if r.returncode:raise SystemExit('Formatter failed; inspect format.log')
def apply():
 import yaml
 p=json.loads((OUT/'format-plan.json').read_text());log=(OUT/'format.log').read_text();assert 'BUILD SUCCESSFUL' in log
 skips=[l for l in log.splitlines() if l.startswith('skipped ') and any(x['target'] in l for x in p['bodies'].values())];assert not skips,skips
 for name,h in p['hashes'].items():assert S.digest((ROOT/name).read_text())==h,('changed source',name)
 formatted={};count=0
 for key,r in p['bodies'].items():
  before=r['before'];after=(ROOT/r['target']).read_text()
  if r['language']=='glsl':after=yaml.safe_load(after)['fragment']
  else:
   a=list(re.finditer(r'"""[\s\S]*?"""',before));b=list(re.finditer(r'"""[\s\S]*?"""',after));assert len(a)==len(b)
   for x,y in reversed(list(zip(a,b))):after=after[:y.start()]+x[0]+after[y.end():]
  punctuation={',',';'} if r['language']=='kotlin' else set()
  assert [t for t in TOK.tokens(before,r['language']) if t not in punctuation]==[t for t in TOK.tokens(after,r['language']) if t not in punctuation],key
  assert TOK.markers(before)==TOK.markers(after),key
  formatted[key]=after;count+=before.rstrip()!=after.rstrip()
 edits=collections.defaultdict(list)
 for r in p['rows']:
  code=formatted[r['key']]
  for i,fold in enumerate(r['folds']):
   code,n=re.subn(rf'^([^\n]*?)/\* BOX_CODE_FOLD_{i} \*/[ \t]*(?:\n|$)',lambda m:(m[1].rstrip()+'\n' if m[1].strip() else '')+fold+'\n',code,flags=re.M);assert n==1
  if r['kind']=='yaml':
   assert r['style']=='|';code=('|\n' if code.endswith('\n') else '|-\n')+''.join(' '*r['indent']+l+'\n' if l else '\n' for l in code.rstrip('\n').splitlines())
  else:code=code.rstrip()+'\n'
  edits[r['path']].append((r['start'],r['end'],code))
 changed=[]
 for name,spans in edits.items():
  path=ROOT/name;before=path.read_text();after=before
  for a,b,c in sorted(spans,reverse=True):after=after[:a]+c+after[b:]
  if before!=after:path.write_text(after);changed.append(name)
 receipt=dict(spans=len(p['rows']),uniqueBodies=len(p['bodies']),formattedBodies=count,changedPaths=changed,skips=skips,tokenLiteralsFoldsAndFragmentMarkersVerified=True,formatter='Actual Field editor imports, ktfmt and GLSL formatting')
 (HERE/'format-verification.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt,indent=2))
if __name__=='__main__':
 a=argparse.ArgumentParser();a.add_argument('action',choices=['prepare','run','apply']);args=a.parse_args();globals()[args.action]()
