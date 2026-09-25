#!/usr/bin/env python3
"""Publish real Field captures only after checking successful GPU verdicts and zero VUIDs."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess

ROOT=Path(__file__).resolve().parents[2]
WORK=ROOT/'build/neural-tools'
EVIDENCE=ROOT/'dev_docs/lighting/results/neural-tools'
EVIDENCE.mkdir(parents=True,exist_ok=True)
parser=argparse.ArgumentParser()
parser.add_argument('--stage',choices=['rr','flow','learned'],action='append')
args=parser.parse_args()
for stage,page,marker in [('rr','ray_reconstruction','RAY RECONSTRUCTION CHECK DONE'),('flow','optical_flow','OPTICAL FLOW CHECK DONE'),('learned','learned_shader','LEARNED SHADER CHECK DONE')]:
    if args.stage and stage not in args.stage:continue
    result=json.loads((WORK/f'{stage}-final.json').read_text());data=result['data'];text=data.get('stdout','')+data.get('stderr','')
    assert result['ok'] and data['exitCode']==0 and marker in text and 'VUID-' not in text,stage
    dest=ROOT/'public/docs/assets'/page;dest.mkdir(parents=True,exist_ok=True)
    for source in (WORK/stage).glob('*.png'):shutil.copyfile(source,dest/source.name)
    shutil.copyfile(WORK/stage/'measurements.json',dest/'measurements.json')
    shutil.copyfile(dest/'measurements.json',EVIDENCE/f'{stage}-measurements.json')
    (EVIDENCE/f'{stage}-verification.json').write_text(json.dumps({'entrypoint':data.get('entrypoint'),
        'exitCode':0,'completionMarker':marker,'vulkanValidationErrors':0,'durationMs':data['durationMs'],'framesPerClip':120},indent=2)+'\n')
    command=['ffmpeg','-y','-loglevel','error']
    if stage=='rr':
        paths=['raw','spatial','spatial_dlaa','ray_reconstruction'];labels=['Raw RTXDI','Spatial filter','Spatial + DLAA','Ray Reconstruction']
        for mode in paths:
            folder=WORK/stage/'clips'/mode;assert len(list(folder.glob('*.png')))==120
            command+=['-framerate','30','-i',str(folder/'%04d.png')]
        filters=[f"[{i}:v]drawtext=text='{label}':font=monospace:fontsize=32:fontcolor=white:box=1:boxcolor=black@0.8:boxborderw=10:x=24:y=24[v{i}]" for i,label in enumerate(labels)]
        filters+=['[v0][v1][v2][v3]xstack=inputs=4:layout=0_0|w0_0|0_h0|w0_h0[out]']
        command+=['-filter_complex',';'.join(filters),'-map','[out]'];movie='comparison.mp4'
    else:
        folder=WORK/stage/'clip';assert len(list(folder.glob('*.png')))==120
        command+=['-framerate','30','-i',str(folder/'%04d.png')]
        label='First / second image | Motion and cost / advected feedback' if stage=='flow' else 'Original / learned / absolute error x8'
        command+=['-vf',f"drawtext=text='{label}':font=monospace:fontsize=24:fontcolor=white:box=1:boxcolor=black@0.8:boxborderw=8:x=20:y=20"]
        movie='advection.mp4' if stage=='flow' else 'animation.mp4'
    command+=['-c:v','libx264','-preset','fast','-crf','18','-pix_fmt','yuv420p','-movflags','+faststart',str(dest/movie)]
    subprocess.run(command,check=True)
shutil.copyfile(WORK/'wayland/verification.json',EVIDENCE/'wayland.json')
shutil.copyfile(WORK/'physical-capabilities.json',EVIDENCE/'physical-capabilities.json')
shutil.copyfile(ROOT/'build/lighting/learned-shader/learned-build.json',EVIDENCE/'learned-build.json')
shutil.copyfile(WORK/'rtxns-upstream-run.log',EVIDENCE/'rtxns-upstream.txt')
print('Published validated Field captures and measurement records')
