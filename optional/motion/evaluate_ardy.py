#!/usr/bin/env python3
"""Retain the five-seed ARDY vocabulary, including unsuccessful motion. Requires ARDY's environment.

One model is reused; RNG/session state is restarted for each independent trial. Numerical metrics
are not action recognizability scores. The score sheet deliberately leaves artistic ratings blank.
"""
import argparse
import csv
import json
from pathlib import Path
import subprocess
import sys
import time
sys.path.insert(0, str(Path(__file__).resolve().parent / 'python'))
from field_motion.recording import RecordingWriter

PROMPTS = dict(walk='A person walks forward.', jog='A person jogs forward.', run='A person runs forward.',
    jump_in_place='A person jumps in place.', jump_forward='A person jumps forward.',
    stealth='A person walks stealthily.', crouch_creep='A person crouches and creeps forward.',
    sideways_roll='A person rolls sideways on the ground.', somersault='A person performs a forward somersault.',
    recoil='A person recoils.', backwards='A person steps backwards.', hands_up='A person raises both hands.',
    freeze='A person freezes and stands still.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--home', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--seeds', type=int, default=5)
    parser.add_argument('--duration', type=float, default=6.)
    parser.add_argument('--steps', type=int, default=4)
    args = parser.parse_args()
    if args.output.exists(): raise FileExistsError(args.output)
    args.output.mkdir(parents=True)
    source = args.home.resolve() / 'upstream/ardy'
    expected = 'e706e27acc88070918308a10922b8f7174d0825e'
    if subprocess.check_output(['git','rev-parse','HEAD'],cwd=source,text=True).strip() != expected: raise ValueError('Source pin mismatch')
    sys.path.insert(0, str(source))
    import numpy as np
    import torch
    from ardy.tools import seed_everything
    from field_motion.backends.ardy import ArdyBridge
    load_start = time.perf_counter()
    bridge = ArdyBridge(args.home / 'embeddings', steps=args.steps)
    cold = time.perf_counter() - load_start
    torch.cuda.reset_peak_memory_stats()
    summary = []; pins = json.loads((Path(__file__).resolve().parent/'upstreams.json').read_text())
    try:
        for label, prompt in PROMPTS.items():
            for seed in range(args.seeds):
                seed_everything(seed)
                bridge.history = bridge.future = bridge.future_output = None
                bridge.index = bridge.episode = bridge.revision = 0
                bridge.route = []; bridge.targets = []; bridge.events = []; bridge.scheduled = []
                bridge.text = prompt
                frames = []; events = []; wall = time.perf_counter()
                path = args.output / f'{label}-seed{seed}.f4motion'
                try:
                    with RecordingWriter(path, bridge.skeleton, dict(backend='ardy', prompt=prompt, seed=seed,
                        postprocess=False, capabilities=bridge.capabilities, pins=pins, evaluation='vocabulary')) as writer:
                        while len(frames) < round(args.duration*bridge.fps):
                            update=bridge.advance(bridge.patch/bridge.fps)
                            for f in update['frames']: writer.append(f)
                            for e in update['events']: writer.event(e)
                            frames.extend(update['frames']); events.extend(update['events'])
                    positions=np.array([f['executed']['worldPositions'] for f in frames]).reshape(len(frames),-1,3)
                    root=np.array([f['executed']['root'] for f in frames])
                    parents=bridge.skeleton['parents']; rest=np.array(bridge.skeleton['restTranslations']).reshape(-1,3)
                    errors=[np.max(np.abs(np.linalg.norm(positions[:,i]-positions[:,p],axis=-1)-np.linalg.norm(rest[i]))) for i,p in enumerate(parents) if p>=0]
                    feet=[bridge.skeleton['names'].index(n) for n in bridge.capabilities['contactNames']]
                    contacts=np.array([f['contacts'] for f in frames])[:-1]>.5
                    speed=np.linalg.norm(np.diff(positions[:,feet],axis=0),axis=-1)*bridge.fps
                    entry=dict(label=label,prompt=prompt,seed=seed,path=path.name,frames=len(frames),
                        wallSeconds=time.perf_counter()-wall,rootDisplacementM=(root[-1]-root[0]).tolist(),
                        minimumJointHeightM=float(positions[:,:,1].min()),heightRangeM=[float(root[:,1].min()),float(root[:,1].max())],
                        maximumBoneLengthErrorM=float(max(errors)),predictedContactFootSpeedMeanMps=float(speed[contacts].mean()) if contacts.any() else None,
                        generationMs=[e['generationMs'] for e in events if e['type']=='generation'],
                        encodingMs=[e['encodingMs'] for e in events if e['type']=='generation'], completed=True,
                        recognizable=None,variation=None,continuity=None,artifacts=None)
                except Exception as error:
                    entry=dict(label=label,prompt=prompt,seed=seed,path=path.name,completed=False,error=repr(error))
                summary.append(entry)
                (args.output/'summary.json').write_text(json.dumps(dict(coldModelSeconds=cold,
                    torchPeakAllocatedBytes=torch.cuda.max_memory_allocated(),results=summary),indent=2)+'\n')
                print(label,seed,'completed' if entry['completed'] else entry['error'],flush=True)
    finally: bridge.close()
    with (args.output/'artistic-scores.csv').open('w',newline='') as f:
        out=csv.writer(f);out.writerow(['clip','recognizable','variation','continuity','artifacts','notes'])
        for item in summary: out.writerow([item['path'],'','','','',''])


if __name__=='__main__': main()
