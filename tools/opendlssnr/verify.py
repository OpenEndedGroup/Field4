#!/usr/bin/env python3
"""Managed Field GPU parity checks against the pinned standalone port, with retained artifacts.

uv run --project . --group docs python tools/opendlssnr/verify.py --port 9487
Requires buildOpenDlssNr and a working Field graphics display. No original NVIDIA parity claim.
"""
import argparse
from array import array
import json
from pathlib import Path
import shlex
import subprocess
import sys
from PIL import Image, ImageChops, ImageStat
ROOT=Path(__file__).resolve().parents[2]
NATIVE=ROOT/'build/native/field-opendlssnr'
WORK=ROOT/'build/opendlssnr-verification'

def managed(port,entry,args,log):
    command=shlex.join(['run-jvm-main',entry,*sum((['--arg='+str(a)] for a in args),[]),'--timeout','240'])
    result=subprocess.run([sys.executable,str(ROOT/'tools/field_backdoor.py'),'--json','--port',str(port),'--command',command],cwd=ROOT,capture_output=True,text=True,check=True)
    log.write_text(result.stdout)
    payload=json.loads(result.stdout)['data']
    text=payload.get('stdout','')+payload.get('stderr','')
    if payload.get('exitCode')!=0 or 'VUID-' in text:
        raise RuntimeError('Field GPU verification failed; see '+str(log)+'\n'+text[-3000:])
    print('\n'.join(line for line in text.splitlines() if line.startswith('PASS') or line.startswith('NR DEVICE')),flush=True)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port',type=int,default=9487)
    parser.add_argument('--temporal',action='store_true')
    parser.add_argument('--benchmarks',action='store_true',help='Also measure 768x432 and 1920x1080')
    args=parser.parse_args();WORK.mkdir(parents=True,exist_ok=True)
    cases=[('ink',512,512,1,30,1),('charcoal',512,512,-10,30,2)]
    if args.benchmarks:cases += [('ink-768',768,432,1,30,1),('ink-1080',1920,1080,1,30,1)]
    metrics=[]
    for name,w,h,tone,structure,style in cases:
        output=WORK/(name+'-verified');output.mkdir(parents=True,exist_ok=True)
        source=Image.open(ROOT/'public/docs/assets/opendlssnr'/('charcoal-input.png' if name=='charcoal' else 'ink-input.png')).convert('RGB')
        if source.size!=(w,h):source=source.resize((w,h),Image.Resampling.LANCZOS)
        source.save(output/'input.png')
        managed(args.port,'afield.graphics.render.neural.OpenDlssNrGpuSmoke',[output/'input.png',output,tone,structure,style],output/'field-log.json')
        proxy=array('f')
        for r,g,b in source.get_flattened_data():proxy.extend((r/255,g/255,b/255,1))
        if sys.byteorder!='little':proxy.byteswap()
        (output/'proxy.f32').write_bytes(proxy.tobytes())
        subprocess.run([str(NATIVE/'field_opendlssnr_reference'),'render','--model',str(NATIVE/'model'),'--proxy',str(output/'proxy.f32'),
            '--width',str(w),'--height',str(h),'--output',str(output/'reference'),'--tone',str(tone),'--structure',str(structure),'--style',str(style),'--skin','-1'],cwd=ROOT,check=True)
        field=(output/'field.head.f32').read_bytes();ref=(output/'reference.head.f32').read_bytes()[:len(field)]
        if field!=ref:raise RuntimeError('Field/standalone head mismatch: '+name)
        a=Image.open(output/'field.png').convert('RGB');b=Image.open(output/'reference.ppm').convert('RGB')
        difference=ImageChops.difference(a,b);maximum=max(v[1] for v in difference.getextrema())
        if maximum>1:raise RuntimeError('Composed output differs by more than one code: '+name)
        sheet=Image.new('RGB',(w*3,h));sheet.paste(b);sheet.paste(a,(w,0));sheet.paste(difference,(w*2,0));sheet.save(output/'comparison.png')
        metrics.append(dict(case=name,width=w,height=h,tone=tone,structure=structure,style=style,headExact=True,headComparedBytes=len(field),
            maximumOutputCodeDifference=maximum,meanOutputCodeDifference=ImageStat.Stat(difference).mean,measurements=(output/'field.txt').read_text()))
        print('PASS parity:',name,'raw head exact; output max difference',maximum,flush=True)
    managed(args.port,'afield.graphics.render.neural.OpenDlssNrSmokeTest',['--disabled-renderer'],WORK/'disabled-log.json')
    if args.temporal:
        temporal=WORK/'temporal-verified';temporal.mkdir(exist_ok=True)
        managed(args.port,'afield.graphics.render.neural.OpenDlssNrGpuSmoke',['--temporal',temporal],temporal/'field-log.json')
        subprocess.run(['ffmpeg','-y','-hide_banner','-loglevel','error','-framerate','24','-i',str(temporal/'frame-%03d.png'),'-c:v','libx264','-pix_fmt','yuv420p','-crf','18','-movflags','+faststart',str(temporal/'temporal.mp4')],check=True)
    (WORK/'measurements.json').write_text(json.dumps(metrics,indent=2)+'\n')
    print('NR VERIFICATION DONE:',WORK)

if __name__=='__main__':main()
