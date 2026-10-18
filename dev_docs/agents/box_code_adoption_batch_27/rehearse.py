"""Exercise the exact authored WFC body and native graphics transcripts."""
import asyncio,importlib.util,json,sys,yaml
from pathlib import Path
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig27',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m
m.OUT=R/'build/box-code-revision/adoption-27/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
rows=json.loads((H/'changes.json').read_text())
async def main():
 hashes=[]
 row=next(r for r in rows if r['path'].endswith('/wfc_worked_example.yaml'))
 for label in ['before','current']:
  text=row['before'] if label=='before' else (R/row['path']).read_text()
  ops=yaml.safe_load(text)['operations'];code=next(o['create']['properties']['code'] for o in ops if 'fun traceArt' in o.get('create',{}).get('properties',{}).get('code',''))
  hashes.append(await m.program('wfc-'+label,code,'proof(listOf(grid.toList(), sockets.map { traceArt(it).toList() }))'))
 assert hashes[0]==hashes[1],hashes
 for name,renderer,subject in [('docs_capture/first_graphics_native','first_graphics_renderer','first_graphics_triangle'),('petri_sparks','ps_renderer','ps_sparks')]:
  ops=yaml.safe_load((R/f'dev_docs/agents/transcripts/{name}.yaml').read_text())['operations']
  label=name.split('/')[-1]
  await m.macro(label,ops,True)
  await m.batch(label+'-native',[f'wait-native-renderer {renderer} --frames 30 --timeout 60',f'native-screenshot {m.OUT}/{label}.png --uid {renderer} --wait-frames 4 --timeout 60'])
  if label=='petri_sparks':
   await m.macro('petri-counts',[{'execute':{'box':subject,'property':'code','type':'fragment','code':'check(slots == 24 && perBurst == 1400 && perRing == 2400);check(particles == 40800);println("40800 particles agree with SLOTS/PER/DUST")'}}])
   await m.batch('petri-rerun',[f'launch {subject}',f'wait-native-renderer {renderer} --frames 30 --timeout 60',f'native-screenshot {m.OUT}/petri_sparks-rerun.png --uid {renderer} --wait-frames 4 --timeout 60'])
  await m.batch(label+'-stop',[f'stop {subject}',f'stop {renderer}'])
 (m.OUT/'verification.json').write_text(json.dumps({'wfcBeforeAfterHash':hashes,'nativeScenes':['first_graphics_native','petri_sparks','petri_sparks-rerun']},indent=2)+'\n')
asyncio.run(main())
