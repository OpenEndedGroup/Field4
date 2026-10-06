#!/usr/bin/env python3
"""Content-addressed, locked, atomic RAFT/C++ launcher. stdout is one JSON result."""
import argparse
import contextlib
import fcntl
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import signal
import subprocess
import sys
import tempfile
import time
import urllib.parse
import uuid

import pvt

ROOT = Path(__file__).resolve().parent
DEFAULTS = dict(width=0, fps=None, frames=0, particles=3000, min_track=10,
                grace=5, spacing=0.0, confidence=0.25, seed_confidence=0.45,
                color_limit=0.18, still_speed=0.0, merge_distance=0.0,
                merge_age=30, merge_velocity=1.5, seed_budget=16, seed=1,
                iterations=20, weights='C_T_SKHT_V2', device='cuda', threads=4)


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        for block in iter(lambda: f.read(1024*1024), b''):
            h.update(block)
    return h.hexdigest()


def key_for(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()).hexdigest()


def options_for(raw):
    unknown = set(raw)-set(DEFAULTS)
    if unknown:
        raise ValueError(f'Unknown tracker options: {sorted(unknown)}')
    o = dict(DEFAULTS, **raw)
    limits = dict(width=(0,16384), frames=(0,1000000), particles=(1,1000000),
                  min_track=(1,10000), grace=(1,10000), merge_age=(1,1000000),
                  seed_budget=(1,10000), seed=(0,4294967295), iterations=(1,100), threads=(1,256))
    for k, (lo,hi) in limits.items():
        if type(o[k]) is not int or not lo <= o[k] <= hi:
            raise ValueError(f'Invalid {k}: {o[k]}')
    if o['width'] and o['width'] < 32:
        raise ValueError('Width must be 0 (native) or at least 32')
    for k in ('spacing','confidence','seed_confidence','color_limit','still_speed','merge_distance','merge_velocity'):
        if not isinstance(o[k],(int,float)) or not math.isfinite(o[k]) or o[k] < 0:
            raise ValueError(f'Invalid {k}')
    for k in ('confidence','seed_confidence','color_limit'):
        if o[k] > 1:
            raise ValueError(f'{k} must be in 0..1')
    if o['fps'] is not None and (not isinstance(o['fps'],(int,float)) or not math.isfinite(o['fps']) or not 0 < o['fps'] <= 1000):
        raise ValueError('Invalid FPS')
    if o['device'] not in ('cuda','cpu','mps') and not (isinstance(o['device'],str) and o['device'].startswith('cuda:') and o['device'][5:].isdigit()):
        raise ValueError('Device must be cuda[:index], cpu, or mps')
    # Kotlin/JSON can serialize 0.0 as 0. Canonicalize equivalent numeric spellings.
    for k in ('spacing','confidence','seed_confidence','color_limit','still_speed','merge_distance','merge_velocity'):
        o[k] = float(o[k])
    if o['fps'] is not None:
        o['fps'] = float(o['fps'])
    return o


@contextlib.contextmanager
def locked(path):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('a+b') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        try:
            yield
        finally:
            fcntl.flock(lock, fcntl.LOCK_UN)


def source_identity():
    paths = [ROOT/'cached_track.py',ROOT/'neural_track.py',ROOT/'pvt.py']
    paths += sorted((ROOT/'engine').glob('*.hpp')) + [ROOT/'engine/main.cpp']
    return {str(p.relative_to(ROOT)):digest(p) for p in paths}


def environment_identity(o, offline=False):
    import torch
    import torchvision
    from torchvision.models.optical_flow import Raft_Large_Weights
    weights = Raft_Large_Weights[o['weights']]
    checkpoint = Path(torch.hub.get_dir())/'checkpoints'/Path(urllib.parse.urlparse(weights.url).path).name
    if not checkpoint.is_file():
        if offline:
            raise FileNotFoundError(f'Offline checkpoint is missing: {checkpoint}')
        weights.get_state_dict(progress=True, check_hash=True)
    device = torch.device(o['device'])
    if device.type == 'cuda':
        if not torch.cuda.is_available():
            raise RuntimeError('CUDA unavailable; prepare matching Torch or explicitly request cpu')
        info = torch.cuda.get_device_properties(device)
        hardware = dict(name=info.name, capability=[info.major, info.minor])
    elif device.type == 'mps':
        if not torch.backends.mps.is_available():
            raise RuntimeError('MPS unavailable; explicitly request cpu')
        hardware = dict(name='mps')
    else:
        hardware = dict(name=platform.machine())
    return dict(python=platform.python_version(), torch=torch.__version__, torchvision=torchvision.__version__,
                cuda=torch.version.cuda, hardware=hardware, checkpoint_sha256=digest(checkpoint),
                checkpoint_name=checkpoint.name,
                ffmpeg=subprocess.check_output(['ffmpeg','-version'],text=True).splitlines()[0])


def build_engine(cache):
    compiler = os.environ.get('CXX','c++')
    flags = ['-O3','-std=c++17','-Wall','-Wextra','-Wpedantic']
    if sys.platform.startswith('linux'):
        flags += ['-fopenmp']
    identity = dict(compiler=subprocess.check_output([compiler,'--version'],text=True), flags=flags,
                    sources={p.name:digest(p) for p in (ROOT/'engine').iterdir() if p.suffix in ('.hpp','.cpp')})
    key = key_for(identity)
    folder = cache/'engines'/key
    folder.mkdir(parents=True, exist_ok=True)
    binary = folder/'particle-video'
    receipt = folder/'build.json'
    with locked(cache/'locks'/f'engine-{key}.lock'):
        if binary.is_file() and receipt.is_file() and json.loads(receipt.read_text()).get('sha256') == digest(binary):
            return binary, identity
        temporary = folder/('build-'+uuid.uuid4().hex)
        try:
            subprocess.run([compiler,*flags,str(ROOT/'engine/main.cpp'),'-o',str(temporary)],check=True,stdout=sys.stderr)
            os.replace(temporary,binary)
            receipt.write_text(json.dumps(dict(identity=identity,sha256=digest(binary)),indent=2)+'\n')
        finally:
            temporary.unlink(missing_ok=True)
    return binary, identity


def valid_entry(folder, identity):
    try:
        manifest=json.loads((folder/'manifest.json').read_text())
        return (manifest['identity']==identity and manifest['tracks_sha256']==digest(folder/'tracks.pvt')
                and manifest['metadata_sha256']==digest(folder/'tracks.pvt.run.json'))
    except (OSError,ValueError,KeyError):
        return False


def run_cached(source, cache, raw, offline=False, force=False):
    o=options_for(raw)
    source=Path(source).expanduser().resolve(strict=True)
    if not source.is_file():
        raise ValueError('Input must be a local video file')
    cache=Path(cache).expanduser().resolve()
    cache.mkdir(parents=True,exist_ok=True)
    binary, engine=build_engine(cache)
    env=environment_identity(o,offline)
    identity=dict(schema='field.particle-tracker.cache.v1',input_sha256=digest(source),options=o,
                  tool=source_identity(),environment=env,engine=engine)
    key=key_for(identity)
    folder=cache/'runs'/key
    folder.mkdir(parents=True,exist_ok=True)
    target=folder/'tracks.pvt'
    with locked(cache/'locks'/f'run-{key}.lock'):
        if not force and valid_entry(folder,identity):
            return dict(key=key,cache_hit=True,tracks=str(target),manifest=str(folder/'manifest.json'),log=str(folder/'run.log'))
        probe=json.loads(subprocess.check_output(['ffprobe','-v','error','-select_streams','v:0',
            '-show_entries','stream=width','-of','json',str(source)],text=True))
        width=o['width'] or probe['streams'][0]['width']
        with tempfile.TemporaryDirectory(prefix='.run-',dir=folder) as tmp:
            staging=Path(tmp)/'tracks.pvt'
            command=[sys.executable,str(ROOT/'neural_track.py'),str(source),str(staging),'--tracker',str(binary),
                     '--width',str(width),'--device',o['device'],'--iterations',str(o['iterations']),'--weights',o['weights']]
            if o['fps'] is not None:
                command+=['--fps',str(o['fps'])]
            if o['frames']:
                command+=['--frames',str(o['frames'])]
            command+=['--']
            for k in ('particles','min_track','grace','spacing','confidence','seed_confidence','color_limit',
                      'still_speed','merge_distance','merge_age','merge_velocity','seed_budget','seed','threads'):
                command+=['--'+k.replace('_','-'),str(o[k])]
            start=time.monotonic()
            process=None
            try:
                with (folder/'run.log').open('w') as log:
                    process=subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
                    while process.poll() is None:
                        time.sleep(.05)
                    if process.returncode:
                        raise RuntimeError(f'Tracker exited {process.returncode}; see {folder / "run.log"}')
                summary=pvt.inspect(staging)
                # A video edited during inference cannot poison the original content key.
                if digest(source)!=identity['input_sha256']:
                    raise RuntimeError('Input video changed during tracking; retry with a stable file')
                metadata=Path(str(staging)+'.run.json')
                provenance=dict(identity=identity,input_path=str(source),summary=summary,wall_seconds=time.monotonic()-start,
                                tracks_sha256=digest(staging),metadata_sha256=digest(metadata),command=command)
                receipt=Path(tmp)/'manifest.json'
                receipt.write_text(json.dumps(provenance,indent=2)+'\n')
                os.replace(staging,target)
                os.replace(metadata,folder/'tracks.pvt.run.json')
                os.replace(receipt,folder/'manifest.json')
            finally:
                if process is not None and process.poll() is None:
                    os.killpg(process.pid,signal.SIGTERM)
                    try:
                        process.wait(timeout=3)
                    except subprocess.TimeoutExpired:
                        os.killpg(process.pid,signal.SIGKILL)
                        process.wait()
    return dict(key=key,cache_hit=False,tracks=str(target),manifest=str(folder/'manifest.json'),log=str(folder/'run.log'))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input',required=True)
    parser.add_argument('--cache',default=str(Path.home()/'.afield/particle-tracker'))
    parser.add_argument('--options',default='{}',help='JSON object; all defaults and options participate in the content key')
    parser.add_argument('--offline',action='store_true')
    parser.add_argument('--force',action='store_true')
    args=parser.parse_args()
    # Give cancellation the same cleanup path as an interrupted local invocation.
    signal.signal(signal.SIGTERM,lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    try:
        print(json.dumps(run_cached(args.input,args.cache,json.loads(args.options),args.offline,args.force)))
    except KeyboardInterrupt:
        raise SystemExit(130)


if __name__=='__main__':
    main()
