#!/usr/bin/env python3
"""Reproducible CPU training for Field's tiny frozen shader function. No live-loop training.

uv run --project . --group docs python tools/lighting/train_shader.py
Uses only NumPy; deterministic seed, Adam, FP16 row-major weights with 64-byte layer alignment.
"""
import argparse
import time
import sys
import os
os.environ.setdefault("OPENBLAS_NUM_THREADS", "1")
import json
import hashlib
from pathlib import Path
import numpy as np

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/"public/docs/assets/learned_shader"

def features(p):
    x,y,t=p.T
    return np.stack([x,y,t,np.ones_like(x),np.sin(x*np.pi),np.cos(x*np.pi),np.sin(y*np.pi),np.cos(y*np.pi),
        np.sin(t*np.pi),np.cos(t*np.pi),np.sin((x+y)*np.pi),np.cos((x-y)*np.pi),np.sin(2*x*np.pi),np.sin(2*y*np.pi),np.cos(2*x*np.pi),np.cos(2*y*np.pi)],axis=1).astype(np.float32)

def target(p):
    x,y,t=p.T
    # Many weak waves make a deliberately expensive, smooth pigment-like function.
    result=np.zeros((len(p),3),np.float32)
    for k in range(1,49):
        a=k*.61803399
        for c in range(3):
            result[:,c]+=np.sin((x*np.cos(a)+y*np.sin(a))*3 + t*(.3+.1*c)+c*2+a)*(.35/k)
    return .5+.35*np.tanh(result*3)

def load_dataset(folder):
    manifest=json.loads((folder/'dataset.json').read_text())
    if manifest['schema']!=1 or manifest['rowFormat']!='little-endian float32: x,y,t,r,g,b':
        raise ValueError('Unsupported Field capture layout')
    arrays={}
    for name in ['train','validation']:
        record=manifest['splits'][name];raw=(folder/record['file']).read_bytes()
        if hashlib.sha256(raw).hexdigest()!=record['sha256'] or len(raw)!=record['rows']*24:
            raise ValueError(f'{name} capture checksum/stride mismatch')
        rows=np.frombuffer(raw,dtype='<f4').reshape(-1,6)
        if not np.isfinite(rows).all() or np.any(np.abs(rows[:,:3])>1.00001):
            raise ValueError('Invalid captured coordinates/color')
        arrays[name]=rows
    if set(manifest['splits']['train']['parameters']) & set(manifest['splits']['validation']['parameters']):
        raise ValueError('Validation must use held-out parameter slices')
    return manifest,arrays

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dataset',type=Path,help='Directory exported by ShaderTraining.capture')
    parser.add_argument('--out',type=Path)
    parser.add_argument('--steps',type=int,default=6000)
    args=parser.parse_args()
    if not 1<=args.steps<=200000:parser.error('--steps must be 1..200000')
    if args.dataset and not args.out:parser.error('--dataset requires --out; bundled weights are never overwritten implicitly')
    out=args.out or OUT
    captured,arrays=load_dataset(args.dataset) if args.dataset else (None,None)
    started=time.monotonic()
    rng=np.random.default_rng(4090);shape=[16,32,32,3]
    params=[]
    for a,b in zip(shape,shape[1:]):params.extend([(rng.normal(size=(b,a))*np.sqrt(2/a)).astype(np.float32),np.zeros(b,np.float32)])
    m=[np.zeros_like(p) for p in params];v=[np.zeros_like(p) for p in params]
    for step in range(1,args.steps+1):
        if arrays is None:
            points=rng.uniform(-1,1,(512,3)).astype(np.float32);truth=target(points)
        else:
            rows=arrays['train'][rng.integers(0,len(arrays['train']),512)];points=rows[:,:3];truth=rows[:,3:]
        a=features(points)
        h1=np.maximum(a@params[0].T+params[1],0);h2=np.maximum(h1@params[2].T+params[3],0);pred=h2@params[4].T+params[5]
        d=2*(pred-truth)/pred.size;g4=d.T@h2;g5=d.sum(0);d=(d@params[4])*(h2>0);g2=d.T@h1;g3=d.sum(0);d=(d@params[2])*(h1>0)
        grads=[d.T@a,d.sum(0),g2,g3,g4,g5];lr=.003*(.2+.8*(1-step/(args.steps+1)))
        for i,g in enumerate(grads):
            m[i]=.9*m[i]+.1*g;v[i]=.999*v[i]+.001*g*g
            params[i]-=lr*(m[i]/(1-.9**step))/(np.sqrt(v[i]/(1-.999**step))+1e-8)
        if step%1000==0:print(step,float(np.mean((pred-truth)**2)),flush=True)
    test=rng.uniform(-1,1,(8192,3)).astype(np.float32) if arrays is None else arrays['validation'][:,:3]
    truth=target(test) if arrays is None else arrays['validation'][:,3:]
    q=[p.astype(np.float16).astype(np.float32) for p in params]
    pred=np.maximum(features(test)@q[0].T+q[1],0);pred=np.maximum(pred@q[2].T+q[3],0);pred=pred@q[4].T+q[5]
    mse=float(np.mean((pred-truth)**2))
    if not np.isfinite(pred).all() or not np.isfinite(mse):raise ValueError('Nonfinite quantized model')
    if arrays is None:assert mse<.004,mse
    out.mkdir(parents=True,exist_ok=True);data=bytearray();offsets=[]
    for p in params:
        data.extend(bytes((-len(data))%64));offsets.append(len(data));data.extend(p.astype('<f2').tobytes())
    data.extend(bytes((-len(data))%64));(out/'weights.f16').write_bytes(data)
    manifest={'schema':1,'shape':shape,'offsets':offsets,'matrixLayout':'row-major','dtype':'float16 little-endian','alignment':64,
        'seed':4090,'steps':args.steps,'batch':512,'validationSamples':len(test),'validationMse':mse,'sha256':hashlib.sha256(data).hexdigest()}
    manifest['trainingSeconds']=time.monotonic()-started
    manifest['numpyVersion']=np.__version__;manifest['pythonVersion']=sys.version.split()[0]
    manifest['trainerSha256']=hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    manifest['sourceKind']='builtin' if captured is None else 'captured-module'
    if captured is not None:
        manifest.update({key:captured[key] for key in ['sourceShaderDigest','parameterRange','fixedState','resolution']})
        manifest['datasetSha256']=hashlib.sha256((args.dataset/'dataset.json').read_bytes()).hexdigest()
        manifest['validation']='held-out parameter slices; measured after FP16 weight conversion'
        manifest['validationP95AbsoluteError']=float(np.quantile(np.abs(pred-truth),.95))
        manifest['validationMaxAbsoluteError']=float(np.max(np.abs(pred-truth)))
        manifest['validationTargetRange']=[float(truth.min()),float(truth.max())]
    (out/'weights.json').write_text(json.dumps(manifest,indent=2)+'\n')
    # Small NumPy input/output fixture for offline inspection; the JVM smoke independently recomputes it.
    np.savez(out/'reference.npz',points=test[:64],outputs=pred[:64])
    print(json.dumps(manifest,indent=2))

if __name__=='__main__':main()
