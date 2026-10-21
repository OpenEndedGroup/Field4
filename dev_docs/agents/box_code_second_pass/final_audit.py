"""Read-only residual idiom audit, restricted to the frozen review groups."""
from pathlib import Path
import gzip,importlib.util,json,subprocess,sys,collections
R=Path(__file__).resolve().parents[3];sys.path.insert(0,str(R))
from tools.inventory_box_code import Inventory
s=importlib.util.spec_from_file_location('literals_final',Path(__file__).parent.parent/'box_code_adoption_batch_6/literals.py');lit=importlib.util.module_from_spec(s);s.loader.exec_module(lit)
frozen=json.load(gzip.open(R/'dev_docs/agents/box_code_revision_pilot/inventory.json.gz'))
groups={x['group'] for x in frozen['targets'] if x['role'] in {'source','docs-fence'} and x['language'] in {'kotlin','kotlinjs','glsl'}}
class Audit(Inventory):
 def __init__(self,*a):super().__init__(*a);self.residual=[];self.reviewed=[]
 def add(self,path,locator,code,*args,**kwargs):
  previous=len(self.rows);super().add(path,locator,code,*args,**kwargs)
  if len(self.rows)==previous:return
  row=self.rows[-1]
  if row['group'] not in groups or row['role'] not in {'source','docs-fence'} or row['language']!='kotlin':return
  self.reviewed.append(row['id']);tokens=[t for t in lit.TOKEN.finditer(code) if not t[0].startswith(('//','/*','"',"'"))]
  for i,t in enumerate(tokens):
   if t[0] not in {'listOf','setPropertyRaw','getPropertyRaw','bindPushConstant'}:continue
   try:_,count=lit.convert(code[t.start():]) if t[0]=='listOf' else ('',0)
   except ValueError:count=-1
   self.residual.append({'path':path,'locator':locator,'line':row['line']+code[:t.start()].count('\n'),'symbol':t[0],'following':code[t.start():t.start()+180].split('\n')[0],'literalCandidate':t[0]=='listOf' and i+1<len(tokens) and tokens[i+1][0]=='(' and count!=0})
tracked=subprocess.check_output(['git','ls-files','-z'],cwd=R).decode().split('\0');audit=Audit(R,filter(None,tracked));audit.run()
out=R/'build/box-code-revision/final-audit.json';out.write_text(json.dumps({'frozenGroups':len(groups),'reviewedKotlinOccurrences':len(audit.reviewed),'residual':audit.residual},indent=2)+'\n')
print(json.dumps({'reviewed':len(audit.reviewed),'counts':dict(collections.Counter(x['symbol'] for x in audit.residual)),'literalCandidates':sum(x['literalCandidate'] for x in audit.residual)}))
for x in audit.residual:
 if x['literalCandidate'] or x['symbol']=='setPropertyRaw':print(json.dumps(x))
