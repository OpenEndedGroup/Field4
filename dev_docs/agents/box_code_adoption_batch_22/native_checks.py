"""Exercise real native views through rerun and Stop, checking their renderer attachments."""
from pathlib import Path
import importlib.util,asyncio,json,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('native22',H.parent/'box_code_adoption_batch_19/native_checks.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.OUT=R/'build/box-code-revision/adoption-22/native';m.OUT.mkdir(parents=True,exist_ok=True)
async def fragment(label,uid,code):
 return await m.macro(label,[{'execute':{'box':uid,'property':'code','type':'fragment','code':code}}])
async def main():
 renderer=(R/'public/docs/assets/canonical_graphics/611072da-1af6-4d2d-85bf-07a9ba191aca_code_.kt').read_text().replace('Options.enableHDR = true','Options.enableHDR = false').replace('Window("Field")','Window("Private lifecycle verification")')
 cases=[('direct',(R/'dev_docs/agents/demos/direct_lighting/example.kt').read_text(),{}),('learned',(R/'dev_docs/agents/demos/learned_from_module/example.kt').read_text(),{kind:(R/f'dev_docs/agents/demos/learned_from_module/{kind}.glsl').read_text() for kind in ['vertex','fragment']})]
 text=(R/'public/docs/direct_lighting.md').read_text();body=next(text[a:b] for a,b in n.d.sections(text) if 'val study = DirectLightingDemo(' in text[a:b]);cases.append(('guide',body,{}))
 records=[]
 for label,body,props in cases:
  props={'code':body,**props}
  done=m.OUT/(label+'_detached.json')
  prior=m.OUT/(label+'.yaml')
  if done.exists() and json.loads(done.read_text()).get('ok') and prior.exists() and yaml.safe_load(prior.read_text())['operations'][0]['create']['properties']==props:
   records.append({'example':label,'nativeRendered':True,'replacementAlive':True,'activeClosedOnStop':True,'rendererAttachmentsRestored':True,'retainedMatchingRun':True})
   continue
  await m.macro('renderer_'+label,[{'create':{'box':'renderer','properties':{'code':renderer}}},{'launch':{'box':'renderer'}},{'await':{}}],True)
  await fragment('baseline_'+label,'renderer','val initialTargets=renderer.renderList.preTargets.toMap();val initialModules=renderer.renderList.modules.toMap();println("recorded renderer baseline")')
  await m.macro(label,[{'create':{'box':'subject','name':label,'properties':props}},{'connect':{'parent':'renderer','child':'subject'}},{'launch':{'box':'subject'}},{'await':{}}])
  await m.batch(label+'_render',['wait-native-renderer subject --frames 20 --timeout 60',f'native-screenshot {m.OUT}/{label}.png --uid subject --wait-frames 4 --timeout 60'])
  await fragment(label+'_retain','subject','val firstOwned=OWNED;check(renderer.renderList.preTargets.isNotEmpty());println("first view rendered")'.replace('OWNED','study' if label=='guide' else 'ownedLab'))
  await m.batch(label+'_rerun',['launch subject','wait-native-renderer subject --frames 20 --timeout 60'])
  # Retiring run closes the previous resource before replacement. The replacement remains registered.
  active='study' if label=='guide' else 'ownedLab'
  await fragment(label+'_alive','subject',f'''check(renderer.renderList.preTargets.isNotEmpty())
val activeOwned={active}
val closedField=activeOwned.javaClass.getDeclaredField("closed").apply {{ isAccessible=true }}
check(!closedField.getBoolean(activeOwned))
println("replacement alive after previous run retired")''')
  await m.batch(label+'_stop',['stop subject','lifecycle-census subject --expect-stopped'])
  await fragment(label+'_closed','subject','check(closedField.getBoolean(activeOwned));println("exact active view closed")')
  await m.batch(label+'_cleanup_frames',['wait-native-renderer renderer --frames 5 --timeout 60'])
  await fragment(label+'_detached','renderer','check(renderer.renderList.preTargets==initialTargets) { renderer.renderList.preTargets.keys.toString() };check(renderer.renderList.modules==initialModules) { renderer.renderList.modules.keys.toString() };println("all view attachments removed")')
  await m.batch(label+'_remove',['delete-box subject'])
  await m.batch('renderer_stop_'+label,['stop renderer','lifecycle-census renderer --expect-stopped'])
  records.append({'example':label,'nativeRendered':True,'replacementAlive':True,'activeClosedOnStop':True,'rendererAttachmentsRestored':True})
 (m.OUT/'verification.json').write_text(json.dumps(records,indent=2)+'\n')
if __name__=='__main__':asyncio.run(main())
