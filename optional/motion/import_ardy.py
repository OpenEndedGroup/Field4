#!/usr/bin/env python3
"""Convert verified Core27 upstream NPZ output to portable Field motion. Run in ARDY's environment."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import numpy as np
sys.path.insert(0, str(Path(__file__).resolve().parent / 'python'))
from field_motion.recording import RecordingWriter
from field_motion.backends.ardy import skeleton_metadata, motion_frame

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('input', type=Path)
parser.add_argument('output', type=Path)
parser.add_argument('--source', type=Path, required=True)
parser.add_argument('--seed', type=int)
parser.add_argument('--postprocess', choices=['unknown', 'none', 'upstream'], default='unknown')
args = parser.parse_args()
pins = json.loads((Path(__file__).resolve().parent / 'upstreams.json').read_text())['ardy']
actual = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=args.source, text=True).strip()
if actual != pins['commit']: raise ValueError('ARDY source pin mismatch')
sys.path.insert(0, str(args.source.resolve()))
from ardy.skeleton import CoreSkeleton27
with np.load(args.input, allow_pickle=False) as source:
    output = {k: source[k] for k in source.files}
if output['posed_joints'].shape[1] != 27: raise ValueError('This importer supports Core27 only; do not infer SOMA expansion mappings')
selected_skeleton = CoreSkeleton27()
skeleton = skeleton_metadata(selected_skeleton)
fps = float(output['fps'])
configuration = dict(backend='ardy', pins=pins, seed=args.seed, postprocess=args.postprocess,
    inputName=args.input.name, inputSha256=hashlib.sha256(args.input.read_bytes()).hexdigest(),
    prompt=str(output.get('text', '')), fps=fps, contactNames=list(selected_skeleton.foot_joint_names))
with RecordingWriter(args.output, skeleton, configuration) as writer:
    for i in range(len(output['posed_joints'])): writer.append(motion_frame(output, i, i/fps, i, 0, 0))
print('Imported', len(output['posed_joints']), 'Core27 frames at', fps, 'Hz')
