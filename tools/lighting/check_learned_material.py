#!/usr/bin/env python3
"""Verify that editing a GLSL consumer leaves its learned kernel and texture intact."""
import argparse,json,subprocess,time,shutil
from pathlib import Path
from PIL import Image,ImageChops,ImageStat
ROOT=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser();p.add_argument('--port',type=int,required=True);p.add_argument('--server-log',type=Path,required=True);a=p.parse_args()
w=ROOT/'build/neural-tools/material';w.mkdir(parents=True,exist_ok=True)
read='''val kernel=renderer.renderList.preTargets["editable learned material"] as afield.four.neural.LearnedShader
val capture=Class.forName("afield.four.reconstruction.ReconstructionCapture")
val pixels=capture.getMethod("read",afield.four.Renderer::class.java,afield.four.texture.HasImageView::class.java).invoke(capture.getField("INSTANCE").get(null),renderer,learnedImage) as FloatArray
check(pixels.all { it.isFinite() })
'''
(w/'before.kt').write_text(read+'''prop<afield.four.neural.LearnedShader>(kernelBefore)=kernel
prop<FloatArray>(pixelsBefore)=pixels
prop<Long>(imageBefore)=learnedImage.imageView.vkImageView
prop<Any>(shaderBefore)=renderer.renderList.modules.getValue("editable learned material").current.fi
org.lwjgl.glfw.GLFW.glfwSetWindowSize(window.windowHandle,1920,1080)
''')
(w/'after.kt').write_text(read+'''check(kernel===kernelBefore)
check(learnedImage.imageView.vkImageView==imageBefore)
check(pixels.contentEquals(pixelsBefore)) { "The frozen network's raw pixels changed" }
check(renderer.renderList.modules.getValue("editable learned material").current.fi!==shaderBefore)
println("LEARNED MATERIAL EDIT CHECK DONE: same kernel, image and raw RGB; new GLSL")
''')
original=ROOT/'dev_docs/agents/demos/learned_shader/material.frag'
(w/'edited.frag').write_text(original.read_text().replace('base.gbr,.65','base.brg,.9').replace('st.y*160','st.y*32'))
commands=['macro write learned --file dev_docs/agents/transcripts/learned_shader.yaml','macro run learned','stop learned_shader','launch learned_material','sleep 2',
 'new-box --name MaterialChecks','connect learned_material @last',f'run @last --file {w}/before.kt',
 f'native-screenshot {w}/before.png --uid agent_graphics_context --wait-frames 12',
 f'set learned_material fragment --file {w}/edited.frag','action learned_material moduleHere_reload --property fragment','sleep 1',f'run @last --file {w}/after.kt',
 f'native-screenshot {w}/after.png --uid agent_graphics_context --wait-frames 12',
 'stop learned_material','sleep 1','stop agent_graphics_context','sleep 1']
doc='learned-material-'+str(time.time_ns());start=a.server_log.stat().st_size
cmd=['uv','run','--project','.','--group','backdoor','python','tools/field_backdoor.py','--json','--attach-existing','--port',str(a.port),'--document',doc]
for c in commands:cmd+=['--command',c]
r=subprocess.run(cmd,cwd=ROOT,capture_output=True,text=True,timeout=240)
(w/'run.json').write_text(r.stdout);(w/'stderr.txt').write_text(r.stderr)
if r.returncode:
 subprocess.run(cmd[:cmd.index('--command')]+['--no-fail-on-error','--command','stop learned_material','--command','stop learned_shader','--command','stop agent_graphics_context'],cwd=ROOT,capture_output=True,timeout=60)
assert r.returncode==0,'Inspect material/run.json and stderr.txt'
rest=r.stdout;records=[];decoder=json.JSONDecoder()
while rest.strip():
 rec,n=decoder.raw_decode(rest.lstrip());rest=rest.lstrip()[n:];assert rec['ok'];records.append(rec)
log=a.server_log.read_bytes()[start:].decode(errors='replace');assert 'VUID-' not in log and '[error,execution]' not in log
before=Image.open(w/'before.png').convert('RGB');after=Image.open(w/'after.png').convert('RGB')
# Wayland's scale factor can make framebuffer pixels larger than the requested window.
assert before.size==after.size and before.width>=1920 and before.height>=1080
assert before.width*9==before.height*16
# The edited treatment changes the right half; the learned RGB on the left stays the same.
half=before.width//2
left=(0,0,half,before.height);right=(half,0,before.width,before.height)
left_error=max(ImageStat.Stat(ImageChops.difference(before.crop(left),after.crop(left))).mean)
right_change=max(ImageStat.Stat(ImageChops.difference(before.crop(right),after.crop(right))).mean)
assert left_error<.05 and right_change>5,(left_error,right_change)
proof={'captureSize':list(before.size),'commandsPassed':len(records),'sameKernel':True,'sameOutputImage':True,'bitIdenticalRawRgb':True,'glslRecompiled':True,'leftMeanByteError':left_error,'rightMeanByteChange':right_change,'vulkanValidationErrors':0}
(ROOT/'dev_docs/lighting/results/neural-tools/material-edit.json').write_text(json.dumps(proof,indent=2)+'\n')
shutil.copyfile(w/'before.png',ROOT/'public/docs/assets/learned_shader/material.png')
print('LEARNED MATERIAL CHECK DONE '+json.dumps(proof))
