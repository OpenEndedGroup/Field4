#!/usr/bin/env python3
"""Explicit NVIDIA check: real editable boxes, reload, resize, relaunch and Stop. No SDK preparation."""
import argparse
import json
from pathlib import Path
import subprocess
import time

ROOT=Path(__file__).resolve().parents[2]
PAGES=('computed_ray_geometry','gpu_shader_learning','path_traced_reconstruction')


def records(text):
    decoder=json.JSONDecoder()
    while text.strip():
        record,end=decoder.raw_decode(text.lstrip());yield record;text=text.lstrip()[end:]


def main():
    p=argparse.ArgumentParser();p.add_argument('--port',type=int,required=True)
    p.add_argument('--server-log',type=Path,required=True);p.add_argument('--only',choices=PAGES,action='append')
    a=p.parse_args();work=ROOT/'build/neural-tools/advanced-starting-points';work.mkdir(parents=True,exist_ok=True)
    result={};log_start=a.server_log.stat().st_size
    for page in a.only or PAGES:
        directory=work/page;directory.mkdir(exist_ok=True)
        if page=='computed_ray_geometry':
            before='prop<afield.four.modules.ComputeModule>(oldProducer)=lab.producer.current\ncheck(lab.geometry.deviceAddress()>0)\n'
            after='check(lab.producer.current!==oldProducer && lab.producer.current.valid)\n'
            shader=ROOT/'src/jvmMain/resources/afield/four/geometry/terrain.comp'
            (directory/'edited.comp').write_text(shader.read_text().replace('.55*sin','.75*sin'))
            edit=[f'set {page} compute --file {directory}/edited.comp',f'action {page} computeModuleHere_reload --property compute']
        elif page=='gpu_shader_learning':
            before='check(lab.model.steps>0)\nprop<afield.four.neural.GpuShaderTraining>(oldModel)=lab.model\nprop<String>(oldSourceHash)=afield.four.neural.ShaderTraining.sourceDigest(lab.source)\n'
            after='check(lab.model===oldModel && lab.model.steps>0)\ncheck(afield.four.neural.ShaderTraining.sourceDigest(lab.source)!=oldSourceHash)\n'
            shader=ROOT/'src/jvmMain/resources/afield/four/neural/teacher.frag'
            (directory/'edited.frag').write_text(shader.read_text().replace('color=','color = ').replace('.22*cos','.20*cos'))
            # A source change goes through moduleHere and the Starting Point's digest/reset path.
            edit=[f'set {page} fragment --file {directory}/edited.frag',f'action {page} moduleHere_reload --property fragment']
        else:
            before='lab.reset("test cut");lab.view=afield.four.reconstruction.PathTracedReconstruction.View.RAW\n'
            after='lab.view=afield.four.reconstruction.PathTracedReconstruction.View.RECONSTRUCTED\n'
            edit=[]
        (directory/'before.kt').write_text(before+'org.lwjgl.glfw.GLFW.glfwSetWindowSize(window.windowHandle,1280,720)\n')
        (directory/'after.kt').write_text(after+'org.lwjgl.glfw.GLFW.glfwSetWindowSize(window.windowHandle,1920,1080)\nprintln("ADVANCED EDIT CHECK DONE")\n')
        commands=[f'macro write advanced --file dev_docs/agents/transcripts/{page}.yaml','macro run advanced',
            'wait-native-renderer agent_graphics_context --frames 12','sleep 2',
            'new-box --name Checks',f'connect {page} @last',f'run @last --file {directory}/before.kt',
            *([f'run @last --file tools/lighting/capture_advanced_capabilities.kt'] if page=='computed_ray_geometry' else []),
            *edit,'sleep 2',f'run @last --file {directory}/after.kt',
            f'native-screenshot {directory}/live.png --uid agent_graphics_context --wait-frames 12',
            'page-eval --file tools/lighting/capture_background.js',
            f'screenshot-box {page} {directory}/controls.png --padding 16',
            f'stop {page}','sleep 1',f'launch {page}','sleep 2',f'output {page}',f'stop {page}','stop agent_graphics_context']
        doc=f'advanced-{page}-{time.time_ns()}'
        base=['uv','run','--project','.','--group','backdoor','python','tools/field_backdoor.py',
              '--json','--attach-existing','--port',str(a.port),'--document',doc,'--with-playwright']
        cmd=base.copy()
        for c in commands:cmd+=['--command',c]
        run=subprocess.run(cmd,cwd=ROOT,capture_output=True,text=True,timeout=300)
        (directory/'run.json').write_text(run.stdout);(directory/'stderr.txt').write_text(run.stderr)
        if run.returncode:
            subprocess.run(base+['--no-fail-on-error','--command',f'stop {page}','--command','stop agent_graphics_context'],
                           cwd=ROOT,capture_output=True,timeout=60)
        assert run.returncode==0,f'{page}: inspect {directory}/run.json and stderr.txt'
        rows=list(records(run.stdout));assert all(r['ok'] and not r.get('timedOut') for r in rows)
        result[page]={'commandsPassed':len(rows),'reloadOrCut':True,'resize':True,'relaunch':True,'stop':True}
        print(f'PASS {page}',flush=True)
    log=a.server_log.read_bytes()[log_start:].decode(errors='replace')
    assert 'VUID-' not in log and '[error,execution]' not in log,'Inspect server validation/execution errors'
    result['vulkanValidationErrors']=0
    (work/'results.json').write_text(json.dumps(result,indent=2)+'\n')
    print('ADVANCED STARTING POINTS CHECK DONE')


if __name__=='__main__':main()
