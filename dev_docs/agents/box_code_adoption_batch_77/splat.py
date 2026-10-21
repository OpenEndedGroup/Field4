"""Exercise authored splat playback with production-written input frames and Stop cleanup."""
from pathlib import Path
import asyncio,importlib.util,json
H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('r77splat',H/'rehearse.py');r=importlib.util.module_from_spec(s);s.loader.exec_module(r)
async def main():
 directory=r.O/'splat-input';directory.mkdir(exist_ok=True)
 fixture='''import afield.graphics.render.SplatCloud
import afield.graphics.scene.CompressedSplatPlyWriter
import java.io.File
val fixtureDir=File("DIRECTORY").also { it.mkdirs() }
for(i in 0..1) {
 val fixtureCloud=SplatCloud(floatArrayOf(-0.2f+i*0.1f,0f,1.2f,0.2f,0.1f,1.4f),floatArrayOf(1f,0.2f,0.1f,0.1f,0.5f,1f),floatArrayOf(0.8f,0.9f),floatArrayOf(1f,0f,0f,0f,1f,0f,0f,0f),floatArrayOf(0.05f,0.05f,0.05f,0.05f,0.05f,0.05f),2)
 CompressedSplatPlyWriter.write(File(fixtureDir,"frame_$i.compressed.ply"),fixtureCloud)
}
println("SPLAT_INPUT_READY")'''.replace('DIRECTORY',str(directory))
 await r.box('g3-splat-input',{'code':fixture})
 props=r.subject('splat_sequence_streaming')['properties'];code=props['code'].replace('File(System.getProperty("user.home"), "Downloads/splat-sequence")','File("'+str(directory)+'")').replace('val slots = 15','val slots = 2')
 await r.box('g3-splat-playback',{**props,'code':code})
 await r.m.batch('g3-splat-decoded',['wait-output g3-splat-playback --contains "decode(s)" --timeout 20'])
 check='check(renderer.renderList.modules.containsKey("splatStream"));check(renderer.renderList.preTargets.containsKey("__splat_feed__"));check(renderer.renderList.preTargets.containsKey("__splat_report__"));println("SPLAT_REGISTERED")'
 await r.macro('g3-splat-before-stop',[{'execute':{'box':'renderer','type':'fragment','code':check}}])
 await r.macro('g3-splat-stop',[{'stop':{'box':'g3-splat-playback'}},{'wait':{'seconds':0.5}},{'execute':{'box':'renderer','type':'fragment','code':'check(!renderer.renderList.modules.containsKey("splatStream"));check(!renderer.renderList.preTargets.containsKey("__splat_feed__"));check(!renderer.renderList.preTargets.containsKey("__splat_report__"));println("SPLAT_STOP_CLEAN")'}}])
 p=H/'behavior.json';d=json.loads(p.read_text());d['splat']={'productionWrittenInputFrames':2,'actualPlaybackDecoded':True,'stopRemovedCloudAndBothCallbacks':True};p.write_text(json.dumps(d,indent=2)+'\n')
asyncio.run(main())
