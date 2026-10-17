"""Recapture only the two real three-box probe assets through the docs runner."""
from pathlib import Path
import os,sys
R=Path(__file__).resolve().parents[3];sys.path.insert(0,str(R/'public'));import docs_hooks as h
os.environ['FIELD_DOCS_RUNNER_MODE']='always'
class Config(dict):config_file_path=str(R/'public/zensical.yml')
state=h.RunnerState(Config(docs_dir=str(R/'public/docs')))
records=[]
try:
 for pageName,keys in [
  ('afield_four_graphics.md',['four-buffer-triangles','four-printf']),
  ('spectral_examples.md',['assets/spectral_examples/streaming_features.png']),
  ('statistics_examples.md',['assets/statistics_examples/rolling_regime.png'])]:
  if '--stats-only' in sys.argv and pageName!='statistics_examples.md':continue
  page=R/'public/docs'/pageName;lines=page.read_text().splitlines();index=0
  for n,line in enumerate(lines):
   if h.FENCE_OPEN_RE.match(line):index+=1
   if not line.startswith('```diffstatic_runner') or not any(key in line for key in keys):continue
   options=h.parse_runner_options(runner_name='diffstatic_runner',open_line=line,page_path=page,line_number=n+1,block_index=index)
   body=[]
   for v in lines[n+1:]:
    if v=='```':break
    body.append(v)
   code=h.extract_static_code(lines=body)
   print(pageName,index,flush=True)
   state.capture_with_environment(state.ensure_screenshot,code=code,options=options,page_path=page,block_index=index)
   records.append({'page':pageName,'block':index});state.save()
 assert state.screenshots_run==(1 if '--stats-only' in sys.argv else 4) and state.cache_hits==0 and state.assets_reused==0 and not state.failures
 out=R/('build/box-code-revision/adoption-25/captures-stats-refinement.json' if '--stats-only' in sys.argv else 'build/box-code-revision/adoption-25/captures.json')
 out.write_text(__import__('json').dumps({'screenshots':state.screenshots_run,'failures':len(state.failures),'captures':records},indent=2)+'\n')
 state.save();state.log_summary()
finally:state.close()
print('four actual math captures regenerated')
