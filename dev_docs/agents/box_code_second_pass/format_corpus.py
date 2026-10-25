"""Audit the current frozen corpus using real editor formatters in private copies.

Source hashes and per-occurrence states make this resumable without treating old
formatter receipts as current coverage. This command does not mutate teaching code.
"""
from pathlib import Path
from collections import Counter
import argparse,hashlib,importlib.util,json,re,subprocess,sys
R=Path(__file__).resolve().parents[3];sys.path.insert(0,str(R))
from tools.inventory_box_code import Inventory,digest
OUT=R/'build/box-code-revision/mechanical-final'
def prepare():
 texts={}
 class Capture(Inventory):
  def add(self,path,locator,code,*args,**kwargs):
   n=len(self.rows);super().add(path,locator,code,*args,**kwargs)
   if len(self.rows)>n:texts[self.rows[-1]['id']]=code
 tracked=subprocess.check_output(['git','ls-files','-z'],cwd=R).decode().split('\0')
 inv=Capture(R,filter(None,tracked));inv.run()
 groups={x['group'] for x in json.loads((R/'dev_docs/agents/box_code_first_pass/scope.json').read_text())['groups']}
 bodies={};occurrences=[]
 for row in inv.rows:
  if row['role'] not in {'source','docs-fence'} or row['language'] not in {'kotlin','kotlinjs','glsl'} or row['group'] not in groups:continue
  code=texts[row['id']];markers=list(re.finditer(r'^:::(?:static|before|after)[ \t]*\n',code,re.M))
  ranges=[(m.end(),markers[i+1].start() if i+1<len(markers) else len(code)) for i,m in enumerate(markers)] if markers else [(0,len(code))]
  for index,(a,b) in enumerate(ranges):
   original=code[a:b];folds=[]
   def carry(m):
    folds.append(m[0]);return f'/* BOX_CODE_FOLD_{len(folds)-1} */'
   prepared=re.sub(r'^[ \t]*:::(?:fold[^\n]*|endfold|)[ \t]*$',carry,original,flags=re.M)
   sha=digest(prepared);key=(row['language'],sha)
   body=bodies.setdefault(key,{'language':row['language'],'sha256':sha,'code':prepared,'occurrences':[]})
   occurrence={k:row[k] for k in ['id','path','property','group','line','locator','source_sha256']};occurrence.update(state=index,start=a,end=b,folds=folds,original_sha256=digest(original));body['occurrences'].append(occurrence);occurrences.append(occurrence)
 OUT.mkdir(parents=True,exist_ok=True)
 (OUT/'bodies.jsonl').write_text(''.join(json.dumps(v)+'\n' for v in bodies.values()))
 (OUT/'scope.json').write_text(json.dumps({'source_commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=R,text=True).strip(),'groups':len({x['group'] for x in occurrences}),'sourceOccurrences':len({x['id'] for x in occurrences}),'states':len(occurrences),'uniqueBodies':len(bodies),'languages':dict(Counter(x['language'] for x in bodies.values())),'missingFrozenGroups':sorted(groups-{x['group'] for x in occurrences}),'inventoryFindings':inv.findings},indent=2)+'\n')
 print(json.loads((OUT/'scope.json').read_text())|{'inventoryFindings':len(inv.findings)},flush=True)
def main():
 p=argparse.ArgumentParser();p.add_argument('--prepare-only',action='store_true');p.add_argument('--resume',action='store_true');a=p.parse_args()
 if not a.resume:prepare()
 if a.prepare_only:return
 source=R/'dev_docs/agents/box_code_first_pass/mechanical.py';spec=importlib.util.spec_from_file_location('mechanical_current',source);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);m.RUN=OUT;m.OUT=OUT/'mechanical';m.main()
if __name__=='__main__':main()
