#!/usr/bin/env python3
"""Run the editable MRT->SR and RTXDI->RR graphs, controls, rerun, and Stop through Field."""
import argparse,json,subprocess,time
from pathlib import Path
import numpy as np
from PIL import Image
ROOT=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser();p.add_argument('--port',type=int,required=True);p.add_argument('--server-log',type=Path,required=True)
a=p.parse_args();work=ROOT/'build/neural-tools/plumbing';work.mkdir(parents=True,exist_ok=True)
assets=ROOT/'public/docs/assets/reconstruction_plumbing';assets.mkdir(parents=True,exist_ok=True)
start=a.server_log.stat().st_size;report={}
for page,short,label in [('reconstruction_plumbing','sr','Bypass reconstruction'),('rr_plumbing','rr','Bypass to raw lighting')]:
 toggle=work/f'{short}-toggle.js'
 toggle.write_text('''(() => {const label=Array.from(document.querySelectorAll('.field-checkbox label')).find(e=>e.textContent.trim()===LABEL);
 if(!label)throw new Error('Control absent');const input=document.getElementById(label.htmlFor);input.click();return input.checked;})()'''.replace('LABEL',json.dumps(label)))
 cut=work/'cut.js';cut.write_text('''(() => {const button=Array.from(document.querySelectorAll('.field-button')).find(e=>e.textContent.includes('Cut / reset history'))?.querySelector('button');if(!button)throw new Error('Reset absent');button.click();return true;})()''')
 check=work/f'{short}-check.kt';check.write_text(('check(source.width==1280 && source.height==720 && source.imageViewArray.size==3)\n' if short=='sr' else 'check(guides.width==1920 && guides.height==1080 && guides.imageViewArray.size==5)\ncheck(scene.lighting.mode==afield.four.lighting.DirectLighting.Mode.RTXDI)\n')+'org.lwjgl.glfw.GLFW.glfwSetWindowSize(window.windowHandle,1920,1080)\nprintln("PLUMBING ATTACHMENTS OK")')
 commands=[f'macro write pipe --file dev_docs/agents/transcripts/{page}.yaml','macro run pipe','sleep 2',
   'new-box --name Inspect',f'connect {page} @last',f'run @last --file {check}',
   f'native-screenshot {assets}/{short}.png --uid agent_graphics_context --wait-frames 48 --timeout 60',
   f'page-eval --file {toggle}','sleep 1',f'native-screenshot {work}/{short}-bypass.png --uid agent_graphics_context --wait-frames 8',
   f'page-eval --file {toggle}',f'page-eval --file {cut}','sleep 1',
   'page-eval --file tools/lighting/capture_background.js',f'screenshot-box {page} {assets}/{short}-controls.png --padding 16',
   f'run {page}','sleep 2',f'native-screenshot {work}/{short}-rerun.png --uid agent_graphics_context --wait-frames 12',
   f'stop {page}','stop agent_graphics_context']
 doc='plumbing-check-'+short+'-'+str(time.time_ns())
 cmd=['uv','run','--project','.','--group','backdoor','python','tools/field_backdoor.py','--json','--attach-existing','--port',str(a.port),'--document',doc,'--with-playwright']
 for c in commands:cmd+=['--command',c]
 r=subprocess.run(cmd,cwd=ROOT,capture_output=True,text=True,timeout=300)
 (work/f'{short}.json').write_text(r.stdout);(work/f'{short}.stderr').write_text(r.stderr)
 if r.returncode:
  subprocess.run(['uv','run','--project','.','--group','backdoor','python','tools/field_backdoor.py','--json','--attach-existing','--port',str(a.port),'--document',doc,'--command','dispose-graphics-context'],cwd=ROOT,capture_output=True,timeout=60)
 assert r.returncode==0,f'{page}: inspect {work}/{short}.json'
 records=[];rest=r.stdout;decoder=json.JSONDecoder()
 while rest.strip():
  d,n=decoder.raw_decode(rest.lstrip());rest=rest.lstrip()[n:];assert d['ok'] and not d.get('timedOut');records.append(d)
 toggles=[x['data']['result'] for x in records if str(toggle) in x['command']];assert toggles==[True,False],toggles
 assert 'PLUMBING ATTACHMENTS OK' in r.stdout
 dimensions=[]
 for image in [assets/f'{short}.png',work/f'{short}-bypass.png',work/f'{short}-rerun.png']:
  data=np.asarray(Image.open(image).convert('RGB'));assert data.shape[1]>=1920 and data.std()>15
  dimensions.append([data.shape[1],data.shape[0]])
 report[page]={'commandsPassed':len(records),'bypassToggles':toggles,'cut':True,'rerun':True,'normalStop':True,'captureDimensions':dimensions}
 print('PASS '+page,flush=True)
log=a.server_log.read_bytes()[start:].decode(errors='replace')
assert 'VUID-' not in log and '[error,execution]' not in log,'Inspect GPU/server log'
report['vulkanValidationErrors']=0
out=ROOT/'dev_docs/lighting/results/neural-tools/plumbing.json';out.write_text(json.dumps(report,indent=2)+'\n')
print('RECONSTRUCTION PLUMBING CHECK DONE')
