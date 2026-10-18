"""Regenerate only the figures whose authored visible content changed."""
from pathlib import Path
import sys,json,os,logging
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;OUT=R/'build/box-code-revision/adoption-45/captures';OUT.mkdir(parents=True,exist_ok=True)
sys.path[:0]=[str(R/'tools'),str(R/'public')]
import docs_hooks as hooks
from build_zensical_docs import DocsConfig,GeneratedFiles
selected={'assets/first_steps/first-look.mp4'} if '--video-only' in sys.argv else {'assets/first_steps/first-look.mp4','assets/moving_around_the_canvas/bookmarks_panel.png'}
os.environ['FIELD_DOCS_RUNNER_MODE']='incremental' if '--resume' in sys.argv else 'always';os.environ['FIELD_DOCS_RUNNER_PORT']='9717'
logging.basicConfig(level=logging.INFO,format='%(levelname)s %(message)s')
completed=[]
class SelectedCaptures(hooks.RunnerState):
 def capture_with_environment(self,method,**kwargs):
  options=kwargs['options'];asset=options.video or options.audio or options.screenshot
  if asset not in selected:return
  super().capture_with_environment(method,**kwargs);completed.append(asset)
config=DocsConfig(config_file_path=R/'public/zensical.yml',docs_dir=R/'public/docs',site_dir=R/'public/field4');state=SelectedCaptures(config)
try:
 for name in ['first_steps.md','moving_around_the_canvas.md']:
  page=R/'public/docs'/name
  result=hooks.process_markdown(markdown=page.read_text(),page_path=page,state=state,files=GeneratedFiles());(OUT/name).write_text(result)
 assert not state.failures and not state.environment_outcomes,(state.failures,state.environment_outcomes)
 assert set(completed)==selected,(selected,completed)
 (OUT/('coverage-video.json' if '--video-only' in sys.argv else 'coverage.json')).write_text(json.dumps({'selected':sorted(selected),'completed':completed,'actualDocsHook':True,'screenshotsRun':state.screenshots_run,'videosRun':state.videos_run,'uiDrivesRun':state.ui_drives_run,'cacheHits':state.cache_hits},indent=2)+'\n')
finally:state.close()
