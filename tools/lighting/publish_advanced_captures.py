#!/usr/bin/env python3
"""Publish only explicitly verified NVIDIA captures. Never called by the docs build."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[2]
WORK=ROOT/'build/neural-tools'


def main():
    p=argparse.ArgumentParser(description=__doc__);p.parse_args()
    if sys.platform!='linux':raise SystemExit('Retain committed NVIDIA media on this host; publishing requires its Linux verification runs.')
    suites={
        'computed_ray_geometry':('clusters','clusters-final.json','COMPUTED CLUSTERS CHECK DONE',
                                 {'computed.png':'computed.png','clusters.png':'clusters.png'}),
        'gpu_shader_learning':('gpu-learning','gpu-learning-final.json','GPU SHADER TRAINING CHECK DONE',
                               {'comparison.png':'comparison.png','learned.png':'learned.png'}),
        'path_traced_reconstruction':('path-rr','path-rr-final.json','PATH RR CHECK DONE',
                                     {'1920x1080-rr.png':'reconstructed.png','1920x1080-raw.png':'raw.png'}),
    }
    reports={}
    for page,(folder,log,marker,files) in suites.items():
        result=json.loads((WORK/log).read_text());data=result['data'];stdout=data.get('stdout','')
        assert result['ok'] and data.get('exitCode')==0 and marker in stdout,log
        assert 'VUID-' not in stdout+data.get('stderr',''),f'Validation errors: {log}'
        source=WORK/folder;destination=ROOT/'public/docs/assets'/page;destination.mkdir(parents=True,exist_ok=True)
        for original,target in files.items():shutil.copyfile(source/original,destination/target)
        measurement=json.loads((source/'measurements.json').read_text())
        (destination/'measurements.json').write_text(json.dumps(measurement,indent=2)+'\n')
        frames=sorted((source/'clip').glob('*.png'));assert len(frames)>=90
        subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y','-framerate','30','-i',str(source/'clip/%04d.png'),
            '-frames:v',str(len(frames)),'-c:v','libx264','-crf','18','-pix_fmt','yuv420p','-movflags','+faststart',str(destination/'animation.mp4')],check=True)
        controls=WORK/'advanced-starting-points'/page/'controls.png'
        if controls.exists():shutil.copyfile(controls,destination/'controls.png')
        reports[page]={'marker':marker,'exitCode':0,'vulkanValidationErrors':0,'frames':len(frames),'measurements':measurement}
    for name in ('advanced-capabilities.json',):
        if (WORK/name).exists():reports[name]=json.loads((WORK/name).read_text())
    editor=WORK/'advanced-starting-points/results.json'
    if editor.exists():reports['startingPoints']=json.loads(editor.read_text())
    baseline=WORK/'advanced-base-audit.json'
    if baseline.exists():
        r=json.loads(baseline.read_text());d=r['data'];log=d.get('stdout','')+d.get('stderr','')
        assert r['ok'] and d.get('exitCode')==0 and 'RAY TRACING AUDIT DONE' in log and 'VUID-' not in log
        audit=json.loads((WORK/'base-path/report.json').read_text())
        reports['ordinaryPathTracer']={'complete':audit['complete'],'stages':audit['stages'],'vulkanValidationErrors':0}
    build=ROOT/'build/lighting/learned-shader/learned-build.json'
    if build.exists():reports['shaderBuild']=json.loads(build.read_text())
    reports['sourceHashes']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest()
        for directory in ('src/jvmMain/resources/afield/four/geometry','src/jvmMain/resources/afield/four/neural')
        for p in sorted((ROOT/directory).iterdir()) if p.is_file()}
    report=ROOT/'dev_docs/lighting/results/advanced-vulkan.json';report.parent.mkdir(parents=True,exist_ok=True)
    report.write_text(json.dumps(reports,indent=2)+'\n')
    print('ADVANCED NVIDIA CAPTURES PUBLISHED')


if __name__=='__main__':main()
