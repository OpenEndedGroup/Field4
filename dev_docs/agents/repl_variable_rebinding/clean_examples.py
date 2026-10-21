"""Collapse reviewed adjacent constant resets in parsed teaching-code containers."""
from pathlib import Path
import argparse,importlib.util,json,re,sys,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;O=R/'build/repl-variable-rebinding';sys.path.insert(0,str(R))
def load(name,path):
 s=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
lit=load('lit_rebind',H.parent/'box_code_adoption_batch_6/literals.py');docs=load('docs_rebind',H.parent/'box_code_adoption_batch_7/format_docs.py')
a=argparse.ArgumentParser();a.add_argument('--apply',action='store_true');args=a.parse_args()
proof=json.loads((O/'regression-passed.json').read_text())
assert proof['ok'],'Runtime regression must pass before cleanup'
import hashlib
assert all(hashlib.sha256((R/path).read_bytes()).hexdigest()==sha for path,sha in proof['sourceHashes'].items()),'Runtime changed after verification'
review=json.loads((O/'reset-inventory.json').read_text());paths=sorted({x['path'] for x in review});rows=[];counts=[]
def convert(code):
 lines=code.splitlines(keepends=True);remove=set();changed=[]
 for i,line in enumerate(lines):
  m=re.match(r'^(\s*var\s+(\w+)(?:\s*:\s*[^=]+)?\s*=\s*)(.+?)(\r?\n)?$',line)
  if not m:continue
  j=i+1
  while j<len(lines) and (not lines[j].strip() or lines[j].strip().startswith('//') and not re.match(r'//\s*%-',lines[j].strip())):j+=1
  if j==len(lines):continue
  reset=re.match(r'^\s*'+re.escape(m[2])+r'\s*=\s*(.+?)(\r?\n)?$',lines[j])
  if not reset:continue
  def parts(s):
   tokens=[x for x in lit.TOKEN.finditer(s) if not x[0].startswith(('//','/*'))]
   return [x[0] for x in tokens],s[tokens[-1].end():] if tokens else ''
  before,oldComment=parts(m[3]);after,comment=parts(reset[1])
  if before!=after or not re.fullmatch(r'[-+]?\d+(?:\.\d+)?[fFL]?', ''.join(before)):continue
  assert not oldComment.strip() or oldComment==comment,(line,lines[j])
  suffix=comment or oldComment
  lines[i]=m[1]+m[3][:len(m[3])-len(oldComment)]+suffix+('\n' if line.endswith('\n') else '')
  remove.add(j);changed.append(m[2])
 code=''.join(x for i,x in enumerate(lines) if i not in remove)
 code=code.replace('// Change a reset assignment with Alt-Enter; declarations belong to a full launch.','// Edit these declarations with Alt-Enter while the scene runs.')
 code=code.replace('Edit this assignment and Alt+Enter it','Edit this value and Alt+Enter its declaration').replace('Alt+Enter this assignment','Alt+Enter this declaration')
 return code,changed
for name in paths:
 p=R/name;before=p.read_text();after=before;edits=[];changed=[]
 def body(code):
  new,found=convert(code);changed.extend(found);return new
 if p.suffix=='.kt':after=body(before)
 elif p.suffix=='.md':
  for x,y in docs.sections(before):
   new=body(before[x:y])
   if new!=before[x:y]:edits.append((x,y,new))
 else:
  def visit(node):
   if isinstance(node,yaml.MappingNode):
    for k,v in node.value:
     if k.value=='code' and isinstance(v,yaml.ScalarNode):
      new=body(v.value)
      if new!=v.value:
       assert v.style=='|',(name,v.style)
       indent=k.start_mark.column+2;replacement=('|\n' if new.endswith('\n') else '|-\n')+''.join(' '*indent+l+'\n' if l else '\n' for l in new.rstrip('\n').splitlines());edits.append((v.start_mark.index,v.end_mark.index,replacement))
     else:visit(v)
   elif isinstance(node,yaml.SequenceNode):
    for v in node.value:visit(v)
  visit(yaml.compose(before))
 for x,y,new in sorted(edits,reverse=True):after=after[:x]+new+after[y:]
 if name.endswith('frame_history_reprojection.yaml'):after=after.replace('Alt-Enter reset assignments for its live controls.','Alt-Enter declarations for its live controls.')
 assert len(changed)==sum(x['path']==name for x in review),(name,changed)
 if p.suffix=='.yaml':yaml.safe_load(after)
 assert before!=after;rows.append({'path':name,'before':before,'after':after});counts.append({'path':name,'variables':changed})
 if args.apply:p.write_text(after)
p=R/'public/docs/first_steps.md';before=p.read_text();after=before.replace('`petals = 5` (the second line)','`var petals = 5` (the second line)').replace(r'"\npetals = 5 "',r'"\nvar petals = 5 "').replace(r'"\npetals = 8 "',r'"\nvar petals = 8 "').replace('"petals = 5".length','"var petals = 5".length').replace('the shipped five-petal assignment was not loaded','the shipped five-petal declaration was not loaded')
assert before!=after;rows.append({'path':str(p.relative_to(R)),'before':before,'after':after})
if args.apply:p.write_text(after)
p=R/'dev_docs/agents/demos/frame_history_reprojection/README.md';before=p.read_text();after=before.replace('The reset assignments at the top of the accumulation box are deliberate: change an assignment and Alt-Enter it. Redeclaring a variable would leave the old loop reading its previous variable.','Change a `var` declaration at the top of the accumulation box and Alt-Enter it. Field resets older mutable bindings of the same name and type, so the running loop picks up the new value.').replace('so assignment edits reach the shader','so declaration edits reach the shader')
assert before!=after;rows.append({'path':str(p.relative_to(R)),'before':before,'after':after})
if args.apply:p.write_text(after)
(O/'example-changes.json').write_text(json.dumps(rows,indent=2)+'\n');(H/'example-review.json').write_text(json.dumps({'applied':args.apply,'pairs':sum(len(x['variables']) for x in counts),'files':counts,'firstStepsDriverTargetsDeclaration':True},indent=2)+'\n');print(sum(len(x['variables']) for x in counts),'pairs;',len(rows),'authored paths')
