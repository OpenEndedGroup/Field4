#!/usr/bin/env python3
"""Publish captured-module media only after the actual Starting Point workflow passes."""
import json,shutil,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];work=ROOT/'build/neural-tools';src=work/'from-module';dst=ROOT/'public/docs/assets/learned_shader'
proof=json.loads((work/'module-verification.json').read_text());metrics=json.loads((src/'measurements.json').read_text())
assert proof['completionMarker']=='MODULE SHADER LEARNING CHECK DONE' and proof['vulkanValidationErrors']==0
assert metrics['sourcePassSkippedInReplacement'] and metrics['incompatibleModelPreservedWorkingView']
assert len(list((src/'clip').glob('*.png')))==120
shutil.copyfile(src/'comparison.png',dst/'from-module.png')
shutil.copyfile(src/'measurements.json',dst/'from-module-measurements.json')
manifest=Path(proof['modelDirectory'])/'weights.json';shutil.copyfile(manifest,dst/'from-module-training.json')
evidence=ROOT/'dev_docs/lighting/results/neural-tools'
shutil.copyfile(work/'module-verification.json',evidence/'module-verification.json')
shutil.copyfile(src/'measurements.json',evidence/'module-measurements.json')
subprocess.run(['ffmpeg','-y','-loglevel','error','-framerate','30','-i',str(src/'clip/%04d.png'),'-vf',
 "drawtext=text='Actual moduleHere shader / learned replacement / absolute error x8':font=monospace:fontsize=23:fontcolor=white:box=1:boxcolor=black@0.8:boxborderw=8:x=20:y=20",
 '-c:v','libx264','-preset','fast','-crf','18','-pix_fmt','yuv420p','-movflags','+faststart',str(dst/'from-module.mp4')],check=True)
print('Published verified moduleHere training media')
