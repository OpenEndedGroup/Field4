"""Recapture only the two real three-box probe assets through the docs runner."""
from pathlib import Path
import os,sys
R=Path(__file__).resolve().parents[3];sys.path.insert(0,str(R/'public'));import docs_hooks as h
os.environ['FIELD_DOCS_RUNNER_MODE']='always'
class Config(dict):config_file_path=str(R/'public/zensical.yml')
state=h.RunnerState(Config(docs_dir=str(R/'public/docs')))
page=R/'public/docs/field_probes.md';lines=page.read_text().splitlines();index=0
try:
 for n,line in enumerate(lines):
  if h.FENCE_OPEN_RE.match(line):index+=1
  if not any(f'name="{name}"' in line for name in ['field-probes-galaxy','field-probes-card']):continue
  options=h.parse_runner_options(runner_name='diffstatic_runner',open_line=line,page_path=page,line_number=n+1,block_index=index)
  body=[]
  for v in lines[n+1:]:
   if v=='```':break
   body.append(v)
  code=h.extract_static_code(lines=body)
  method=state.ensure_video if 'field-probes-card' in line else state.ensure_screenshot
  state.capture_with_environment(method,code=code,options=options,page_path=page,block_index=index)
 state.save();state.log_summary()
finally:state.close()
print('actual galaxy and probe card recaptured')
