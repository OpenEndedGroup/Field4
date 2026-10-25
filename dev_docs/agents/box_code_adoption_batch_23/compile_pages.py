"""Compile every real runner body on both edited pages under normal box imports."""
from pathlib import Path
import importlib.util,asyncio,json,yaml,sys
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig23',H/'rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m
sys.path.insert(0,str(R/'public'));import docs_hooks as h
async def main():
 paths=[];retained=[];selected=[];failed={14,17,47,50,52,54}
 for page in ['fline_examples.md','animation_time_model.md']:
  lines=(R/'public/docs'/page).read_text().splitlines()
  for i,line in enumerate(lines):
   if not line.startswith('```diffstatic_runner'):continue
   body=[]
   for v in lines[i+1:]:
    if v=='```':break
    body.append(v)
   code=h.extract_static_code(lines=body);i=len(paths);p=m.OUT/f'compile-{i}.yaml';same=p.exists() and yaml.safe_load(p.read_text())['operations'][0]['create']['properties']['code']==code
   if '--repair' in sys.argv and i not in failed and same:retained.append(i)
   else:selected.append(str(p))
   p.write_text(yaml.safe_dump({'operations':[{'create':{'box':'source','properties':{'code':code}}}]},sort_keys=False));paths.append(str(p))
 template=yaml.safe_load((R/'build/box-code-revision/adoption-20/runtime/compile_sources.yaml').read_text())['operations'][0]['create']['properties']['code'];a=template.index('for ((i,path) in ')+len('for ((i,path) in ');b=template.index('.withIndex()',a);code=template[:a]+'listOf('+','.join(json.dumps(p) for p in selected)+')'+template[b:]
 await m.program('compile_page_sources',code)
 await m.batch('compile_page_wait',['wait-output subject --channel compiled --timeout 180'])
 await m.macro('compile_page_verdict',[{'execute':{'box':'subject','property':'code','type':'fragment','code':'compileJob.get();println("all runner bodies compiled")'}}])
 (m.OUT/'compiled-pages.json').write_text(json.dumps({'bodies':len(paths),'compiledThisRun':len(selected),'retainedExactPreviouslyCompiled':len(retained),'pages':['fline_examples.md','animation_time_model.md']},indent=2)+'\n')
if __name__=='__main__':asyncio.run(main())
