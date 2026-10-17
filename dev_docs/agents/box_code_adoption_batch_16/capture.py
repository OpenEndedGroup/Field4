from pathlib import Path
import os,sys,re
R=Path.cwd();sys.path.insert(0,str(R/'public'));import docs_hooks as h
os.environ['FIELD_DOCS_RUNNER_MODE']='always'
class Config(dict):config_file_path=str(R/'public/zensical.yml')
state=h.RunnerState(Config(docs_dir=str(R/'public/docs')))
page=R/'public/docs/visualizers.md';text=page.read_text();lines=text.splitlines();index=0
for n,line in enumerate(lines):
 if h.FENCE_OPEN_RE.match(line):index+=1
 if 'name="mixed-controls"' in line:
  options=h.parse_runner_options(runner_name='diffstatic_runner',open_line=line,page_path=page,line_number=n+1,block_index=index)
  body=[]
  for v in lines[n+1:]:
   if v=='```':break
   body.append(v)
  code=h.extract_static_code(lines=body);break
else:raise AssertionError('missing mixed-control fence')
try:
 state.capture_with_environment(state.ensure_screenshot,code=code,options=options,page_path=page,block_index=index)
 state.save();state.log_summary()
finally:state.close()
print('mixed-controls capture regenerated through the docs runner')
