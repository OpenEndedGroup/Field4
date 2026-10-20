#!/usr/bin/env python3
"""Reproduce a short CLoSD trial through the same loopback worker used by Kotlin.

Seeds/config are matched between modes; GPU physics determinism is not promised.
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
from field_motion.wire import receive, send
from field_motion.recording import RecordingWriter


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--home', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--mode', choices=['baseline', 'impulse', 'weak', 'trap', 'break', 'informed', 'hand', 'moving', 'strong'], default='baseline')
    parser.add_argument('--duration', type=float, default=6.)
    parser.add_argument('--seed', type=int, default=0)
    parser.add_argument('--override', action='append', default=[], help='Explicit upstream Hydra override')
    parser.add_argument('--stiffness', type=float)
    parser.add_argument('--damping', type=float)
    parser.add_argument('--maximum-force', type=float)
    args = parser.parse_args()
    home = args.home.resolve()
    python = home / 'environments/closd/bin/python'
    source = home / 'upstream/CLoSD'
    if args.output.exists():
        raise ValueError('Trial output already exists')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    log_path = args.output.with_suffix('.worker.log')
    log = log_path.open('w')
    env = dict(os.environ, FIELD_MOTION_TOKEN=uuid.uuid4().hex, HF_HUB_OFFLINE='1', TRANSFORMERS_OFFLINE='1', PYTHONUNBUFFERED='1')
    library = str(python.resolve().parent.parent / 'lib')
    env['LD_LIBRARY_PATH'] = library + (':' + env['LD_LIBRARY_PATH'] if env.get('LD_LIBRARY_PATH') else '')
    process = None
    start = time.perf_counter()
    try:
        with socket.socket() as server:
            server.bind(('127.0.0.1', 0)); server.listen(1); server.settimeout(180)
            process = subprocess.Popen([str(python), str(Path(__file__).resolve().parent / 'worker.py'), '--backend', 'closd',
                '--source', str(source), '--connect', str(server.getsockname()[1]), '--override=seed=' + str(args.seed)] + ['--override=' + value for value in args.override], cwd=source, env=env, stdout=log, stderr=log)
            stream, _ = server.accept()
        with stream:
            stream.settimeout(180)
            sequence = 0
            def call(op, **body):
                nonlocal sequence
                sequence += 1
                send(stream, dict(body, op=op, id=sequence, token=env['FIELD_MOTION_TOKEN']))
                response = receive(stream)
                if response.get('id') != sequence or not response.get('ok'):
                    raise RuntimeError(str(response))
                return response['result']
            hello = call('hello')
            print('Ready', hello['skeleton']['names'], 'cold seconds', time.perf_counter() - start, flush=True)
            all_events = []
            all_frames = []
            durations = []
            pins = json.loads((Path(__file__).resolve().parent / 'upstreams.json').read_text())
            config = dict(backend='closd', mode=args.mode, seed=args.seed, pins=pins,
                timestamp=datetime.now(timezone.utc).isoformat(), capabilities=hello['capabilities'],
                assetRevision='de7106b947b6f70700b5320d1cd61fef4a9ebc9b', upstreamOverrides=args.override,
                tetherOverrides=dict(stiffness=args.stiffness, damping=args.damping, maximumForce=args.maximum_force))
            with RecordingWriter(args.output, hello['skeleton'], config) as recorder:
                revision = 0
                def command(operation, requested, payload):
                    nonlocal revision
                    revision += 1
                    cmd = dict(operation=operation, revision=revision, requestedTime=requested, payload=payload)
                    recorder.event(dict(cmd, type='requested'))
                    return call('command', command=cmd)
                prompt = 'A person walks forward.' if args.mode != 'informed' else 'A person tries to walk but their left foot is stuck and they pull away.'
                command('PROMPT', 0., dict(text=prompt))
                attached = released = False
                while not all_frames or all_frames[-1]['time'] < args.duration - 1e-6:
                    clock = all_frames[-1]['time'] if all_frames else 0.
                    if clock >= 2. - 1e-6 and not attached:
                        attached = True
                        if args.mode == 'impulse':
                            command('IMPULSE', clock, dict(body='Torso', localPoint=[0., 0., 0.], impulse=[10., 0., 0.]))
                        elif args.mode != 'baseline':
                            name = 'L_Wrist' if args.mode == 'hand' else 'L_Ankle'
                            index = hello['skeleton']['names'].index(name)
                            anchor = all_frames[-1]['executed']['worldPositions'][index * 3:index * 3 + 3]
                            strong = args.mode in ('trap', 'break', 'informed')
                            payload = dict(id='tether', body=name, localPoint=[0., 0., 0.], anchor=anchor,
                                stiffness=600. if args.mode == 'strong' else 150. if strong else 30., damping=30. if args.mode == 'strong' else 15. if strong else 5.,
                                maximumForce=400. if args.mode == 'strong' else 150. if strong else 40., engagementSeconds=.2,
                                breakForce=30. if args.mode == 'break' else None, breakError=None, breakDuration=.1,
                                mode='breakable' if args.mode == 'break' else 'saturating', anchorVelocity=[.1, 0., 0.] if args.mode == 'moving' else [0., 0., 0.])
                            for option, key in [(args.stiffness, 'stiffness'), (args.damping, 'damping'), (args.maximum_force, 'maximumForce')]:
                                if option is not None: payload[key] = option
                            command('ATTACH', clock, payload)
                    if clock >= 4. - 1e-6 and attached and not released:
                        released = True
                        active = not any(e['type'] in ('tetherBreak', 'reset') and e.get('time', 0) >= 2 for e in all_events)
                        if args.mode not in ('baseline', 'impulse') and active:
                            command('DETACH', clock, dict(id='tether'))
                    tick = time.perf_counter()
                    result = call('advance', seconds=.1)
                    durations.append(time.perf_counter() - tick)
                    for event in result['events']:
                        recorder.event(event); all_events.append(event)
                    for frame in result['frames']:
                        recorder.append(frame); all_frames.append(frame)
                call('close')
            ordered = sorted(durations)
            summary = dict(mode=args.mode, frames=len(all_frames), duration=all_frames[-1]['time'],
                coldSeconds=time.perf_counter() - start - sum(durations),
                advanceP50Ms=ordered[len(ordered)//2]*1000, advanceP95Ms=ordered[int((len(ordered)-1)*.95)]*1000,
                events={kind: sum(e['type'] == kind for e in all_events) for kind in sorted({e['type'] for e in all_events})},
                maxPrefixResidualM=max(f['diagnostics']['prefixResidualM'] for f in all_frames),
                meanTrackingErrorM=sum(f['diagnostics']['trackingErrorM'] for f in all_frames)/len(all_frames),
                maxTetherForceN=max([e['forceN'] for e in all_events if e['type']=='tetherPhysics'] or [0]),
                totalTetherWorkJ=sum(e['workJ'] for e in all_events if e['type']=='tetherPhysics'),
                commandToVisibleMeasured=False, visualInspection=False)
            args.output.with_suffix('.summary.json').write_text(json.dumps(summary, indent=2) + '\n')
            print(json.dumps(summary, indent=2), flush=True)
    finally:
        if process is not None:
            try: process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.terminate()
                try: process.wait(timeout=2)
                except subprocess.TimeoutExpired: process.kill(); process.wait()
        log.close()


if __name__ == '__main__':
    main()
