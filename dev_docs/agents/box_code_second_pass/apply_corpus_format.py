"""Apply verified editor-format candidates to explicit source spans, with hash fences.

Metadata-only programs need Field's codec and remain explicit follow-up work.
Generated files, foreign languages and rejected preservation checks are never guessed.
"""
from pathlib import Path
from collections import Counter,defaultdict
import argparse,copy,hashlib,importlib.util,json,re,subprocess,sys,yaml
R=Path(__file__).resolve().parents[3];OUT=R/'build/box-code-revision/mechanical-final'
sys.path.insert(0,str(R))
from tools.inventory_box_code import digest

def restored(code,folds):
 for i,fold in enumerate(folds):
  code,n=re.subn(rf'^([^\n]*?)/\* BOX_CODE_FOLD_{i} \*/[ \t]*(?:\n|$)',lambda m:(m[1].rstrip()+'\n' if m[1].strip() else '')+fold+'\n',code,flags=re.M)
  assert n==1
 return code

def yaml_node(text,locator):
 node=yaml.compose(text);key=None;flow=False
 for part in locator.split('/')[1:]:
  if isinstance(node,yaml.MappingNode):
   flow=flow or bool(node.flow_style)
   key,node=next((k,v) for k,v in node.value if k.value==part)
  elif isinstance(node,yaml.SequenceNode):
   flow=flow or bool(node.flow_style);node=node.value[int(part)]
  else:raise ValueError('Unexpected YAML locator '+locator)
 return key,node,flow

def main():
 p=argparse.ArgumentParser();p.add_argument('--apply',action='store_true');a=p.parse_args()
 plan=OUT/'apply-plan.json'
 if a.apply and plan.exists() and json.loads(plan.read_text()).get('applied'):
  completed=json.loads((OUT/'changes.json').read_text())
  assert all((R/x['path']).read_text()==x['after'] for x in completed),'Source changed after recorded application'
  print('Reusing completed source-format receipt for',len(completed),'files');return
 bodies={x['language']+'-'+x['sha256']:x for x in map(json.loads,(OUT/'bodies.jsonl').read_text().splitlines())}
 records=list(map(json.loads,(OUT/'mechanical/reconciled-results.jsonl').read_text().splitlines()));edits=defaultdict(list);deferred=[];seen=set();source_hashes={};preserved=Counter()
 for row in records:
  if row['status']!='candidate':continue
  if row.get('code_tokens_preserved') is not True or not row.get('fragment_markers_preserved'):continue
  body=bodies[row['key']];path=OUT/'mechanical/inputs'/(row['key']+('_code_.kt' if row['language']=='kotlin' else '.yaml'))
  candidate=path.read_text() if row['language']=='kotlin' else yaml.safe_load(path.read_text())['fragment']
  # Untrimmed raw strings are data. Restore any formatter indentation changes exactly.
  if row['language']=='kotlin':
   token=re.compile(r'"""[\s\S]*?"""');before=list(token.finditer(body['code']));after=list(token.finditer(candidate));assert len(before)==len(after)
   for x,y in reversed(list(zip(before,after))):
    if not re.match(r'\s*\.trim(?:Indent|Margin)\s*\(',body['code'][x.end():]) and x[0]!=y[0]:candidate=candidate[:y.start()]+x[0]+candidate[y.end():]
  for occurrence in body['occurrences']:
   source=R/occurrence['path'];text=source.read_text();key=(occurrence['id'],occurrence['state'])
   if key in seen:continue
   seen.add(key)
   sha=hashlib.sha256(text.encode()).hexdigest();assert sha==occurrence['source_sha256'],('source changed since audit',source)
   source_hashes[occurrence['path']]=sha;code=restored(candidate,occurrence['folds'])
   if occurrence['locator']=='source':
    assert occurrence['start']==0 and occurrence['end']==len(text),(source,'unexpected standalone state')
    edits[source].append((0,len(text),code));continue
   if source.suffix in {'.edn','.json'}:
    deferred.append({'id':occurrence['id'],'reason':'metadata source requires Field codec'});continue
   locator=occurrence['locator'];base=0;container=text
   fence=re.match(r'fence:(\d+)(.*)',locator)
   if fence:
    lines=text.splitlines(keepends=True);line=int(fence[1]);base=sum(map(len,lines[:line]));end=line
    opening=re.match(r'^\s*(`{3,}|~{3,})',lines[line-1])[1]
    while end<len(lines) and not re.match(r'^\s*'+re.escape(opening[0])+'{'+str(len(opening))+r',}\s*$',lines[end]):end+=1
    container=''.join(lines[line:end]);locator=fence[2].lstrip('/')
    if not locator:
     a0=occurrence['start'];b0=occurrence['end'];assert digest(container[a0:b0])==occurrence['original_sha256'],(str(source),occurrence['id'],repr(container[:120]),occurrence)
     edits[source].append((base+a0,base+b0,code.rstrip()+'\n'));continue
    locator='yaml/'+locator
   if not locator.startswith('yaml/'):
    deferred.append({'id':occurrence['id'],'reason':'unsupported container locator'});continue
   k,node,flow=yaml_node(container,locator)
   if flow or node.style=='>':
    deferred.append({'id':occurrence['id'],'reason':'flow/folded YAML needs explicit handling'});continue
   assert occurrence['start']==0 and occurrence['end']==len(node.value),(source,locator,'unexpected scalar state')
   assert digest(node.value)==occurrence['original_sha256'],(source,locator,'scalar changed')
   indent=k.start_mark.column+2;start=node.start_mark.index;end=node.end_mark.index
   if node.style=='|':
    replacement=('|\n' if code.endswith('\n') else '|-\n')+''.join(' '*indent+l+'\n' if l else '\n' for l in code.rstrip('\n').splitlines())
   else:
    # Preserve an inline header comment and the original line ending.
    line_end=container.find('\n',end)
    if line_end<0:line_end=len(container)
    suffix=container[end:line_end]
    if suffix.strip() and not suffix.lstrip().startswith('#'):
     deferred.append({'id':occurrence['id'],'reason':'quoted YAML has non-comment suffix'});continue
    end=line_end;replacement='|-'+(' '+suffix.strip() if suffix.strip() else '')+'\n'+'\n'.join(' '*indent+l if l else '' for l in code.rstrip('\n').splitlines())
   edits[source].append((base+start,base+end,replacement))
 changes=[]
 for source,items in sorted(edits.items()):
  before=source.read_text();after=before;last=len(before)+1
  for start,end,code in sorted(items,reverse=True):
   assert end<=last,(source,'overlapping source spans');last=start;after=after[:start]+code+after[end:]
  if source.suffix in {'.yaml','.yml'}:
   # Original foreign code and metadata stay exactly equal except selected code scalar values.
   yaml.safe_load(after)
  if after!=before:changes.append({'path':str(source.relative_to(R)),'before':before,'after':after})
  if a.apply:source.write_text(after)
 summary={'applied':a.apply,'files':len(changes),'formattedSourceSpans':sum(map(len,edits.values())),'deferredOccurrences':deferred,'statuses':dict(Counter(x['status'] for x in records)),'baseline':json.loads((OUT/'scope.json').read_text())['source_commit']}
 (OUT/'apply-plan.json').write_text(json.dumps(summary,indent=2)+'\n')
 (OUT/'changes.json').write_text(json.dumps(changes,indent=2)+'\n')
 print(json.dumps(summary|{'deferredOccurrences':len(deferred)}),flush=True)
if __name__=='__main__':main()
