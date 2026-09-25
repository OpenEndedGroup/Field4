#!/usr/bin/env python3
"""Real moduleHere -> GPU capture -> CPU training -> cooperative inference -> replacement proof."""
import argparse
import time
import json
from pathlib import Path
import shlex
import subprocess

ROOT=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser();parser.add_argument('--port',type=int,required=True);parser.add_argument('--server-log',type=Path,required=True)
a=parser.parse_args();work=ROOT/'build/neural-tools';work.mkdir(parents=True,exist_ok=True)
marker=ROOT/'build/shader-learning/latest-model.txt';marker.unlink(missing_ok=True)
script=work/'check-module.kt';script.write_text('afield.four.neural.ShaderLearningChecks.run(lab)')
start=a.server_log.stat().st_size
click={'action':'clickSelector','selector':'.field-button:has-text("Capture shader and train") button'}
commands=['macro write learn --file dev_docs/agents/transcripts/learned_from_module.yaml','macro run learn','sleep 2',
          'raw-send agent-playwright-input '+shlex.quote(json.dumps(click))+' --reply agent-playwright-input --timeout 30',
          f'wait-file {marker} --timeout 180 --fail-on-error-uid learned_from_module',
          'new-box --name LearningChecks','connect learned_from_module @last',f'run @last --file {script} --timeout 180',
          'page-eval --file tools/lighting/capture_background.js',
          f'screenshot-box learned_from_module {ROOT}/public/docs/assets/learned_shader/module-controls.png --padding 16',
          'run learned_from_module','sleep 2',
          'stop learned_from_module','sleep 1','stop agent_graphics_context','sleep 1']
document='shader-learning-check-'+str(time.time_ns())
cmd=['uv','run','--project','.','--group','backdoor','python','tools/field_backdoor.py','--json','--attach-existing','--port',str(a.port),'--document',document,'--with-playwright']
for c in commands:cmd+=['--command',c]
r=subprocess.run(cmd,cwd=ROOT,capture_output=True,text=True,timeout=420)
(work/'module-workflow.json').write_text(r.stdout);(work/'module-workflow.stderr').write_text(r.stderr)
if r.returncode:
    subprocess.run(['uv','run','--project','.','--group','backdoor','python','tools/field_backdoor.py','--json','--attach-existing','--port',str(a.port),'--document',document,'--command','dispose-graphics-context'],cwd=ROOT,capture_output=True,timeout=60)
assert r.returncode==0,'Inspect build/neural-tools/module-workflow.json and .stderr'
rest=r.stdout;records=[];decoder=json.JSONDecoder()
while rest.strip():
 record,end=decoder.raw_decode(rest.lstrip());rest=rest.lstrip()[end:];assert record['ok'] and not record.get('timedOut'),record
 records.append(record)
log=a.server_log.read_bytes()[start:].decode(errors='replace')
assert 'VUID-' not in log and '[error,execution]' not in log,'Runtime/validation errors in server log'
assert 'MODULE SHADER LEARNING CHECK DONE' in log
model=Path(marker.read_text().strip());manifest=json.loads((model/'weights.json').read_text())
assert manifest['sourceKind']=='captured-module' and manifest['validationMse']<.003
report={'commandsPassed':len(records),'vulkanValidationErrors':0,'completionMarker':'MODULE SHADER LEARNING CHECK DONE',
        'modelDirectory':str(model),'training':manifest}
(work/'module-verification.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
