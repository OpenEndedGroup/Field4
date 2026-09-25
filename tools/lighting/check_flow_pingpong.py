#!/usr/bin/env python3
"""Run the editable ping-pong -> optical flow -> sampled texture Starting point on the GPU."""
import argparse
import json
from pathlib import Path
import subprocess
import time

ROOT=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser();p.add_argument('--port',type=int,required=True);p.add_argument('--server-log',type=Path,required=True)
a=p.parse_args();work=ROOT/'build/neural-tools/pingpong';work.mkdir(parents=True,exist_ok=True)
assets=ROOT/'public/docs/assets/optical_flow'
toggle=work/'toggle.js';toggle.write_text('''(async() => {
for(let i=0;i<100;i++) {
 const label=Array.from(document.querySelectorAll('.field-checkbox label')).find(e=>e.textContent.trim()==='Animate source');
 if(label){const input=document.getElementById(label.htmlFor);input.click();return input.checked;}
 await new Promise(r=>setTimeout(r,100));
} throw new Error('Missing animate control');
})()''')
reset=work/'reset.js';reset.write_text('''(() => {const button=Array.from(document.querySelectorAll('.field-button')).find(e=>e.textContent.includes('Reset the pair'))?.querySelector('button');if(!button)throw new Error('Reset absent');button.click();return true;})()''')
for stationary in [False,True]:
    # This is diagnostic readback only, using the existing internal capture helper via reflection.
    # The example and ordinary texture consumption do not read any images back to the CPU.
    (work/('paused.kt' if stationary else 'moving.kt')).write_text('''
check(history.frameCount==2 && motion.width==240 && motion.height==135)
check(previousImage.imageView.vkImageView!=currentImage.imageView.vkImageView)
val capture=Class.forName("afield.four.reconstruction.ReconstructionCapture")
val instance=capture.getField("INSTANCE").get(null)
val read=capture.getMethod("read",afield.four.Renderer::class.java,afield.four.texture.HasImageView::class.java)
val pixels=read.invoke(instance,renderer,motion) as FloatArray
check(pixels.all { it.isFinite() })
val speeds=(0 until motion.width*motion.height).map { i -> kotlin.math.sqrt(pixels[i*4]*pixels[i*4]+pixels[i*4+1]*pixels[i*4+1]) }.sorted()
val errors=(4 until motion.height-4).flatMap { y -> (4 until motion.width-4).map { x ->
    val i=(y*motion.width+x)*4
    val dx=pixels[i]-expectedMotion.x;val dy=pixels[i+1]-expectedMotion.y
    kotlin.math.sqrt(dx*dx+dy*dy)
} }.sorted()
val before=read.invoke(instance,renderer,previousImage) as FloatArray
val after=read.invoke(instance,renderer,currentImage) as FloatArray
val difference=before.indices.maxOf { kotlin.math.abs(before[it]-after[it]) }
''' + ('check(speeds[speeds.size/2]<.125f && speeds[(speeds.size*.95).toInt()]<.25f && speeds.last()<.5f && difference==0f) { "Paused pair retained motion: median=${speeds[speeds.size/2]}, p95=${speeds[(speeds.size*.95).toInt()]}, max=${speeds.last()}, image difference $difference" }' if stationary else 'check(errors[errors.size/2]<.5f && errors[(errors.size*.95).toInt()]<1f && difference>.1f) { "Moving field mismatch: expected=$expectedMotion, medianError=${errors[errors.size/2]}, p95Error=${errors[(errors.size*.95).toInt()]}, difference=$difference" }') +
        '\nprintln("PINGPONG '+('PAUSED' if stationary else 'MOVING')+' CHECK DONE median=${speeds[speeds.size/2]} p95=${speeds[(speeds.size*.95).toInt()]} max=${speeds.last()} difference=$difference medianError=${errors[errors.size/2]} p95Error=${errors[(errors.size*.95).toInt()]}")\n')
size=work/'size.kt';size.write_text('org.lwjgl.glfw.GLFW.glfwSetWindowSize(window.windowHandle,1920,1080)')
doc='flow-pingpong-'+str(time.time_ns());start=a.server_log.stat().st_size
commands=['macro write flow --file dev_docs/agents/transcripts/optical_flow_pingpong.yaml','macro run flow','sleep 2',
    'new-box --name FlowChecks','connect optical_flow_pingpong @last',f'run @last --file {size}',
    f'run @last --file {work}/moving.kt',f'native-screenshot {work}/pingpong.png --uid agent_graphics_context --wait-frames 12',
    f'screenshot-box optical_flow_pingpong {work}/controls.png --padding 16',
    f'page-eval --file {toggle}','sleep 1',f'run @last --file {work}/paused.kt',
    f'page-eval --file {reset}','sleep 1',f'run @last --file {work}/paused.kt',
    f'page-eval --file {toggle}','sleep 1',f'run @last --file {work}/moving.kt',
    'launch optical_flow_pingpong','launch flow_texture','sleep 1',f'run @last --file {work}/moving.kt',
    'stop flow_texture','stop optical_flow_pingpong','sleep 1',
    'launch optical_flow_pingpong','launch flow_texture','sleep 1',
    'stop agent_graphics_context','sleep 1','stop flow_texture','stop optical_flow_pingpong']
cmd=['uv','run','--project','.','--group','backdoor','python','tools/field_backdoor.py','--json','--attach-existing','--port',str(a.port),'--document',doc,'--with-playwright']
for c in commands:cmd+=['--command',c]
r=subprocess.run(cmd,cwd=ROOT,capture_output=True,text=True,timeout=300)
(work/'run.json').write_text(r.stdout);(work/'stderr.txt').write_text(r.stderr)
if r.returncode:
    subprocess.run(['uv','run','--project','.','--group','backdoor','python','tools/field_backdoor.py','--json','--attach-existing','--port',str(a.port),'--document',doc,'--no-fail-on-error',
        '--command','stop flow_texture','--command','stop optical_flow_pingpong','--command','stop agent_graphics_context'],cwd=ROOT,capture_output=True,timeout=60)
assert r.returncode==0,'Inspect build/neural-tools/pingpong/run.json and stderr.txt'
records=[];rest=r.stdout;decoder=json.JSONDecoder()
while rest.strip():
    rec,n=decoder.raw_decode(rest.lstrip());rest=rest.lstrip()[n:];assert rec['ok'];records.append(rec)
log=a.server_log.read_bytes()[start:].decode(errors='replace')
assert 'VUID-' not in log and '[error,execution]' not in log,'Inspect server log'
assert 'PINGPONG MOVING CHECK DONE' in log and 'PINGPONG PAUSED CHECK DONE' in log
measurements=[event['payload']['html'].strip() for record in records for event in record.get('events',[]) if event.get('payload',{}).get('html','').startswith('PINGPONG ') and 'CHECK DONE' in event['payload']['html']]
proof={'measurements':measurements,'commandsPassed':len(records),'movingPair':True,'pausedPairSubpixelMotion':True,'reset':True,'rerunParentAndConsumer':True,'normalStop':True,'rendererStoppedBeforeChildren':True,'vulkanValidationErrors':0}
(ROOT/'dev_docs/lighting/results/neural-tools/pingpong.json').write_text(json.dumps(proof,indent=2)+'\n')
# The committed image is untouched until the entire live check, including teardown, passes.
import shutil
shutil.copyfile(work/'pingpong.png',assets/'pingpong.png')
print('PINGPONG TEXTURE CHECK DONE')
