"""Recapture changed figures through the real docs hook, with normal environment checks and metadata."""
from pathlib import Path
import sys,json,re,os,logging,importlib.util
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;OUT=R/'build/box-code-revision/adoption-38/captures';OUT.mkdir(parents=True,exist_ok=True)
sys.path[:0]=[str(R/'tools'),str(R/'public'),str(H.parent/'box_code_adoption_batch_7')]
import docs_hooks as hooks
from build_zensical_docs import DocsConfig,GeneratedFiles
from verify_tokens import tokens
row=json.loads((H/'changes.json').read_text())[0];page=R/row['path'];current=page.read_text()
fence=re.compile(r'^(`{3,})([^\n]*)\n(.*?)^\1[ \t]*$',re.M|re.S)
a=[m for m in fence.finditer(row['before']) if '_runner' in m[2]];b=[m for m in fence.finditer(current) if '_runner' in m[2]];assert len(a)==len(b)
selected=set()
for x,y in zip(a,b):
 if tokens(x[3])!=tokens(y[3]):
  attrs=re.findall(r'\b(?:screenshot|video)="([^"]+)"',y[2]);assert attrs;selected.update(attrs)
assert len(selected)>=3
os.environ['FIELD_DOCS_RUNNER_MODE']='incremental' if '--resume' in sys.argv else 'always';os.environ['FIELD_DOCS_RUNNER_PORT']='9704'
logging.basicConfig(level=logging.INFO,format='%(levelname)s %(message)s')
class SelectedCaptures(hooks.RunnerState):
 def capture_with_environment(self,method,**kwargs):
  options=kwargs['options'];asset=options.video if method.__name__=='ensure_video' else options.audio if method.__name__=='ensure_audio' else options.screenshot
  if asset not in selected:return
  super().capture_with_environment(method,**kwargs)
  completed.append(asset)
completed=[];config=DocsConfig(config_file_path=R/'public/zensical.yml',docs_dir=R/'public/docs',site_dir=R/'public/field4');state=SelectedCaptures(config)
try:
 result=hooks.process_markdown(markdown=current,page_path=page,state=state,files=GeneratedFiles())
 (OUT/'functions_examples.md').write_text(result)
 assert not state.failures and not state.environment_outcomes,(state.failures,state.environment_outcomes)
 assert set(completed)==selected,(selected,completed)
 (OUT/'coverage.json').write_text(json.dumps({'selected':sorted(selected),'completed':completed,'actualDocsHook':True,'screenshotsRun':state.screenshots_run,'videosRun':state.videos_run,'cacheHits':state.cache_hits},indent=2)+'\n')
finally:
 state.close()
