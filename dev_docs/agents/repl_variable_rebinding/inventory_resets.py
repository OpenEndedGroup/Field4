"""Read-only inventory of declaration/reset pairs in parsed, current teaching code."""
from pathlib import Path
import json,re,subprocess,sys
R=Path(__file__).resolve().parents[3];sys.path.insert(0,str(R))
from tools.inventory_box_code import Inventory
pairs=[]
class Capture(Inventory):
 def add(self,path,locator,code,*args,**kwargs):
  n=len(self.rows);super().add(path,locator,code,*args,**kwargs)
  if len(self.rows)==n:return
  row=self.rows[-1]
  if row['role'] not in {'source','docs-fence'} or row['language']!='kotlin' or row['property']=='preamble':return
  lines=code.splitlines()
  for i,line in enumerate(lines):
   m=re.match(r'^(\s*)var\s+(\w+)(?:\s*:\s*[^=]+)?\s*=\s*(.+?)\s*$',line)
   if not m:continue
   j=i+1
   while j<len(lines) and (not lines[j].strip() or lines[j].strip().startswith('//') and not re.match(r'//\s*%-',lines[j].strip())):j+=1
   if j==len(lines):continue
   reset=re.match(r'^'+re.escape(m[1])+re.escape(m[2])+r'\s*=\s*(.+?)\s*$',lines[j])
   if reset:pairs.append({'path':path,'locator':locator,'line':row['line']+i,'name':m[2],'declaration':line.strip(),'reset':lines[j].strip(),'source_sha256':row['source_sha256']})
tracked=subprocess.check_output(['git','ls-files','-z'],cwd=R).decode().split('\0');inv=Capture(R,filter(None,tracked));inv.run()
out=R/'build/repl-variable-rebinding/reset-inventory.json';out.write_text(json.dumps(pairs,indent=2)+'\n');print(len(pairs),'adjacent pairs in',len({x['path'] for x in pairs}),'files')
for row in pairs:print(json.dumps(row))
