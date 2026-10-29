#!/usr/bin/env python3
"""Short reproducible ARDY trials through the same worker/protocol as Field.

Foot trials explicitly also condition a root path, required by this pinned representation.
No postprocessing or joint overwrite is used. Outputs retain failed runs as incomplete recordings.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
import uuid
sys.path.insert(0, str(Path(__file__).resolve().parent / 'python'))
from field_motion.wire import send, receive
from field_motion.recording import RecordingWriter


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--home', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--mode', choices=['baseline', 'path', 'foot', 'foot-orientation', 'transitions'], default='baseline')
    parser.add_argument('--seed', type=int, default=0)
    parser.add_argument('--steps', type=int, default=4)
    parser.add_argument('--duration', type=float, default=6.)
    parser.add_argument('--prompt', default='A person walks forward.')
    args = parser.parse_args()
    home = args.home.resolve()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    if args.output.exists(): raise FileExistsError(args.output)
    python = home / 'environments/ardy/bin/python'
    source = home / 'upstream/ardy'
    env = dict(os.environ, FIELD_MOTION_TOKEN=uuid.uuid4().hex, PYTHONUNBUFFERED='1', LOCAL_CACHE='true')
    process = None; start = time.perf_counter()
    try:
        with args.output.with_suffix('.worker.log').open('w') as log, socket.socket() as server:
            server.bind(('127.0.0.1', 0)); server.listen(1); server.settimeout(180)
            process = subprocess.Popen([str(python), str(Path(__file__).resolve().parent / 'worker.py'),
                '--backend', 'ardy', '--source', str(source), '--connect', str(server.getsockname()[1]),
                '--embedding-cache', str(home / 'embeddings'), '--seed', str(args.seed), '--steps', str(args.steps)],
                cwd=source, env=env, stdout=log, stderr=log)
            stream, _ = server.accept()
            stream.settimeout(180)
            with stream:
                sequence = 0
                def call(op, **values):
                    nonlocal sequence
                    sequence += 1
                    send(stream, dict(values, op=op, id=sequence, token=env['FIELD_MOTION_TOKEN']))
                    response = receive(stream)
                    if response['id'] != sequence or not response['ok']: raise RuntimeError(str(response))
                    return response['result']
                hello = call('hello'); cold = time.perf_counter() - start
                caps = hello['capabilities']; step = caps['minimumAdvanceSeconds']; fps = caps['fps']
                print('ARDY ready', caps, 'cold seconds', cold, flush=True)
                all_frames = []; all_events = []; timings = []; anchor = None
                pins = json.loads((Path(__file__).resolve().parent / 'upstreams.json').read_text())
                with RecordingWriter(args.output, hello['skeleton'], dict(backend='ardy', mode=args.mode,
                    seed=args.seed, prompt=args.prompt, steps=args.steps, pins=pins,
                    timestamp=datetime.now(timezone.utc).isoformat(), capabilities=caps,
                    postprocess=False, footRequiresRootPlan=True)) as writer:
                    revision = 0
                    def command(op, at, payload):
                        nonlocal revision
                        revision += 1
                        c = dict(operation=op, requestedTime=at, revision=revision, payload=payload)
                        writer.event(dict(c, type='requested'))
                        call('command', command=c)
                    command('PROMPT', 0., dict(text=args.prompt))
                    if args.mode in ('path', 'foot', 'foot-orientation'):
                        command('ROOT_PLAN', 0., dict(points=[dict(time=i / fps, x=0., z=.6 * i / fps, heading=0.) for i in range(round(args.duration * fps) + 1)]))
                    foot_set = released = running_prompt = stopped_prompt = False
                    while len(all_frames) < round(args.duration * fps):
                        current = len(all_frames) / fps
                        if args.mode.startswith('foot') and current >= 2. - 1e-6 and not foot_set:
                            foot_set = True
                            joint = hello['skeleton']['names'].index('LeftFoot')
                            pose = all_frames[-1]['executed']
                            anchor = pose['worldPositions'][joint * 3:joint * 3 + 3]
                            orientation = None
                            if args.mode == 'foot-orientation':
                                from scipy.spatial.transform import Rotation
                                local = Rotation.from_quat([pose['localRotations'][i:i + 4] for i in range(0, len(pose['localRotations']), 4)])
                                world = []
                                for i, parent in enumerate(hello['skeleton']['parents']): world.append(local[i] if parent < 0 else world[parent] * local[i])
                                orientation = world[joint].as_quat().tolist()
                            command('JOINT_TARGETS', current, dict(targets=[dict(joint='LeftFoot', time=i / fps, position=anchor,
                                orientation=orientation) for i in range(round(current * fps), round(4. * fps))]))
                        if foot_set and current >= 4. - 1e-6 and not released:
                            released = True; command('JOINT_TARGETS', current, dict(targets=[]))
                        if args.mode == 'transitions':
                            if current >= 2. and not running_prompt:
                                running_prompt = True; command('PROMPT', current, dict(text='A person runs forward.'))
                            if current >= 4. and not stopped_prompt:
                                stopped_prompt = True; command('PROMPT', current, dict(text='A person stops and stands still.'))
                        clock = time.perf_counter(); update = call('advance', seconds=step)
                        timings.append((time.perf_counter() - clock) * 1000)
                        for frame in update['frames']: writer.append(frame)
                        for event in update['events']: writer.event(event)
                        all_frames.extend(update['frames']); all_events.extend(update['events'])
                call('close')
                a = all_frames[0]['executed']['root']; b = all_frames[-1]['executed']['root']
                residuals = []
                if anchor is not None:
                    joint = hello['skeleton']['names'].index('LeftFoot')
                    for f in all_frames:
                        if 2. <= f['time'] < 4.:
                            p = f['executed']['worldPositions'][joint * 3:joint * 3 + 3]
                            residuals.append(sum((x - y) ** 2 for x, y in zip(p, anchor)) ** .5)
                summary = dict(mode=args.mode, frames=len(all_frames), fps=fps, coldSeconds=cold,
                    rootDisplacementM=[y-x for x,y in zip(a,b)], advanceP50Ms=sorted(timings)[len(timings)//2],
                    advanceP95Ms=sorted(timings)[int((len(timings)-1)*.95)], generationEvents=[e for e in all_events if e['type']=='generation'],
                    targetResidualMeanM=sum(residuals)/len(residuals) if residuals else None,
                    targetResidualMaxM=max(residuals) if residuals else None,
                    commandToVisibleMeasured=False, visualInspection=False)
                args.output.with_suffix('.summary.json').write_text(json.dumps(summary, indent=2)+'\n')
                print(json.dumps(summary, indent=2), flush=True)
    finally:
        if process is not None:
            try: process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.terminate()
                try: process.wait(timeout=3)
                except subprocess.TimeoutExpired: process.kill(); process.wait()


if __name__ == '__main__': main()
