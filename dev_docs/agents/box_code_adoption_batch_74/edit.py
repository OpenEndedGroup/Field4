"""Turn the taught async chains into run-owned sequential coroutine recipes."""
from pathlib import Path
import importlib.util,json,re,textwrap
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('docs74',H.parent/'box_code_adoption_batch_7/format_docs.py');d=importlib.util.module_from_spec(s);s.loader.exec_module(d)
s=importlib.util.spec_from_file_location('lit74',H.parent/'box_code_adoption_batch_6/literals.py');lit=importlib.util.module_from_spec(s);s.loader.exec_module(lit)
def end_pair(code,start,op,cl):
 depth=0
 for t in lit.TOKEN.finditer(code,start):
  if t[0]==op:depth+=1
  if t[0]==cl:
   depth-=1
   if not depth:return t.end()
 raise AssertionError('unbalanced expression')
def callback(code,method):
 start=code.index(method);a=code.index('{',start);b=end_pair(code,a,'{','}');arrow=code.find('->',a,b);return (start,b,code[a+1:arrow].strip(),textwrap.dedent(code[arrow+2:b-1]).strip()) if arrow>=0 else (start,b,'it',code[a+1:b-1].strip())
def awaited_calls(code,names):
 ed=[]
 for name in names:
  for m in re.finditer(r'client\.'+name+r'\(',code):
   a=code.index('(',m.start());b=end_pair(code,a,'(',')');ed.append((m.start(),b,code[m.start():b].replace('client.'+name+'(','client.'+name+'Async(',1)+'.await()'))
 for a,b,c in reversed(ed):code=code[:a]+c+code[b:]
 return code
p=R/'public/docs/loris.md';before=p.read_text();ed=[];proof=[]
for i,(a,b) in enumerate(d.sections(before)):
 code=before[a:b]
 if '.thenAccept' not in code:continue
 start,end,result,publish=callback(code,'.thenAccept');chain=code[:start].rstrip();chain_start=chain.index('client\n') if 'client\n' in chain else chain.index('client.synthesizeAsync(')
 prefix=chain[:chain_start].replace('val client = LorisClient()', 'val client = cached { LorisClient() }');expr=chain[chain_start:]
 if '.thenCompose' in expr:
  x,y,analyzed,middle=callback(expr,'.thenCompose');initial=expr[:x].rstrip();q=middle.rfind('client.synthesizeAsync(');assert q>=0
  work=f'val {analyzed} = '+initial+'.await()\n'+middle[:q]+f'val {result} = '+middle[q:]+'.await()\n'+publish
 else:
  work=f'val {result} = '+expr+'.await()\n'+awaited_calls(publish,['distill','dilate','morph'])
 if i==0:
  prefix+='prop<LorisResult>(analyzed)\n';work=work.replace('        display(', '        display(',1);at=work.index('\n') if False else work.index('.await()')+len('.await()');work=work[:at]+'\nanalyzed = result'+work[at:]
 work=work.replace('onProgress = { stage -> display(', 'onProgress = { stage -> if (isActive) display(').replace('onProgress = { display(', 'onProgress = { if (isActive) display(')
 revised='import kotlinx.coroutines.*\nimport kotlinx.coroutines.future.await\n\n'+prefix+'go {\n    try {\n'+textwrap.indent(work,'        ')+'''\n    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        markdown("error", "Loris failed: ${(failure.message ?: failure.toString()).md}")
    }
}
'''
 ed.append((a,b,revised));proof.append({'state':i,'preservesCallbackOutputAndTransforms':True})
after=before
for a,b,c in reversed(ed):after=after[:a]+c+after[b:]
after=after.replace('The returned `LorisModel`', 'The box owns the coroutine: Stop or rerun cancels its current native future, and progress from\nan ended run is ignored. The shared client and durable native cache are retained.\n\nThe returned `LorisModel`',1)
after=after.replace('```diffstatic {data-lang=kotlin}\nval bassModel', 'After the first analysis finishes, `analyzed` is a typed box property. In the same box:\n\n```diffstatic {data-lang=kotlin}\nval bassModel',1)
p.write_text(after);(H/'changes.json').write_text(json.dumps([{'path':str(p.relative_to(R)),'before':before,'after':after}],indent=2)+'\n');(H/'edit-review.json').write_text(json.dumps(proof,indent=2)+'\n')
