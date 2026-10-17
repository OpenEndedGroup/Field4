"""Exercise the real runner captures on both mechanically revised teaching pages."""
from pathlib import Path
import sys,os,json
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;O=R/'build/box-code-revision/adoption-23/captures';O.mkdir(parents=True,exist_ok=True)
sys.path.insert(0,str(R/'public'));import docs_hooks as h
os.environ['FIELD_DOCS_RUNNER_MODE']='always'
class Config(dict):config_file_path=str(R/'public/zensical.yml')
state=h.RunnerState(Config(docs_dir=str(R/'public/docs')));records=[]
try:
 for path in ['fline_examples.md','animation_time_model.md']:
  page=R/'public/docs'/path;lines=page.read_text().splitlines();index=0
  for n,line in enumerate(lines):
   if h.FENCE_OPEN_RE.match(line):index+=1
   if not line.startswith('```diffstatic_runner'):continue
   if not any(key in line for key in ['assets/operator_transforms.png','assets/fline_exact_booleans.png','assets/fline_exact_offsets.png','animation-running-stopping']):continue
   options=h.parse_runner_options(runner_name='diffstatic_runner',open_line=line,page_path=page,line_number=n+1,block_index=index)
   body=[]
   for v in lines[n+1:]:
    if v=='```':break
    body.append(v)
   code=h.extract_static_code(lines=body)
   method=state.ensure_video if 'video=' in line else state.ensure_screenshot
   print(path,index,line,flush=True)
   state.capture_with_environment(method,code=code,options=options,page_path=page,block_index=index)
   records.append({'page':path,'block':index,'ok':True});state.save();(O/'verification.json').write_text(json.dumps(records,indent=2)+'\n')
 (O/'actual-counts.json').write_text(json.dumps({'screenshots':state.screenshots_run,'videos':state.videos_run,'cacheHits':state.cache_hits,'assetsReused':state.assets_reused,'failures':len(state.failures)},indent=2)+'\n')
 assert state.screenshots_run==3 and state.videos_run==1 and not state.failures
 state.log_summary()
finally:state.close()
print('actual page runner captures verified',flush=True)
