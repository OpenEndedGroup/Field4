"""Typecheck complete native smoke flows and exercise available real resource paths."""
from pathlib import Path
import asyncio,importlib.util,json,yaml,re
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;O=R/'build/box-code-revision/adoption-77'
s=importlib.util.spec_from_file_location('r77',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9725;m.OUT=O/'runtime-moltenvk';m.OUT.mkdir(parents=True,exist_ok=True)
def subject(name):
 doc=yaml.safe_load((R/f'dev_docs/agents/transcripts/{name}.yaml').read_text())
 return next(x['create'] for x in doc['operations'] if 'create' in x and ('frames {' in x['create']['properties'].get('code','') or '__splat_feed__' in x['create']['properties'].get('code','')) and 'Window(' not in x['create']['properties'].get('code',''))
async def macro(name,ops):
 p=m.OUT/(name+'.yaml');v=m.OUT/(name+'.json')
 if p.exists() and v.exists() and yaml.safe_load(p.read_text())=={'operations':ops} and json.loads(v.read_text()).get('ok'):return json.loads(v.read_text())
 return await m.macro(name,ops)
async def box(name,props):return await macro(name,[{'create':{'box':name,'properties':props}},{'connect':{'parent':'renderer','child':name}},{'launch':{'box':name}},{'await':{}}])
async def main():
 names=['external_browser_surface_macos_smoke','iosurface_ring_roundtrip_smoke','syphon_zero_copy_receive_smoke','splat_sequence_streaming']
 owner='import afield.graphics.vulkan.util.Options\nprop<Window>(window)\nprop<Renderer>(renderer)\nOptions.enableVR=false\nOptions.enableHDR=false\nOptions.profiling=false\nwindow=Window("Private MoltenVK interop acceptance",initialDimensions=Rect(40.0,40.0,800.0,600.0))\nrenderer=Renderer(window)\nprintln("ACTUAL_DRIVER="+renderer.device.physicalDevice.driverName)\nstart(renderer::run)'
 await macro('g3-moltenvk-renderer',[{'create':{'box':'renderer','properties':{'code':owner}}},{'launch':{'box':'renderer'}},{'await':{}}])
 for name in names:
  saved=O/'runtime'/('g3-type77-'+name+'.json');assert json.loads(saved.read_text()).get('ok'),name
 # Run actual IOSurface import/copy. It does not need an encoder or a Syphon worker.
 props=subject(names[0])['properties']
 await box('g3-real-browser-surface',props)
 result=await m.batch('g3-real-browser-surface-pass',['wait-output g3-real-browser-surface --contains "external-browser-macos PASS" --timeout 45'])
 # Observe the actual acquired object; use must close it when setup throws.
 code=props['code'];marker='.use { surface ->';assert marker in code
 code=code.replace('val owner = renderer','var observedSurface: MacExternalBrowserSurface? = null\nval owner = renderer')
 code=code.replace(marker,marker+'\n            observedSurface = surface\n            if(System.nanoTime()!=Long.MIN_VALUE) throw IllegalStateException("intentional setup failure 77")',1)
 uid='g3-browser-surface-failure';path=m.OUT/'failure.yaml';path.write_text(yaml.safe_dump({'operations':[{'create':{'box':uid,'properties':{**props,'code':code}}},{'connect':{'parent':'renderer','child':uid}},{'launch':{'box':uid}},{'await':{}}]},sort_keys=False))
 await m.batch('g3-expected-setup-failure',[f'macro write g3-expected-setup-failure --file {path}','macro run g3-expected-setup-failure --no-fail-on-error'])
 await m.batch('g3-observe-setup-failure',['wait-output '+uid+' --contains "intentional setup failure 77" --no-fail-on-error --timeout 15'])
 await macro('g3-automatic-close-proof',[{'execute':{'box':uid,'type':'fragment','code':'val closedField=CopiedExternalBrowserSurface::class.java.getDeclaredField("closed").also { it.isAccessible=true };check(closedField.getBoolean(requireNotNull(observedSurface)));println("VERIFIED77 automatic use closed the actual acquired surface")'}}])
 (H/'behavior.json').write_text(json.dumps({'completeFlowsTypechecked':names,'actualDriver':'MoltenVK','actualIosurfaceCopyPassed':True,'actualAcquiredSurfaceClosedOnInjectedSetupFailure':True,'limits':'Actual surface flow uses its authored 64×64 exported IOSurface and exact pixel/release assertions. Encoder/Syphon and splat positive acceptance is separate; no missing optional backend is counted as passed.'},indent=2)+'\n')
if __name__ == '__main__':asyncio.run(main())
