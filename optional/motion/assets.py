#!/usr/bin/env python3
"""Download pinned ARDY assets or the CLoSD runtime subset without training/optimizer data.

Run in the selected backend's separate environment after reviewing available disk capacity.
No SDK/license acquisition, environment mutation or inference happens here.
"""
import argparse
import json
from pathlib import Path
from huggingface_hub import snapshot_download

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--backend', choices=['ardy', 'closd'], default='closd')
parser.add_argument('--encoder-cache', type=Path, help='ARDY encoder cache; about 16.4 GB, excluding duplicate original weights')
args = parser.parse_args()
pins = json.loads((Path(__file__).resolve().parent / 'upstreams.json').read_text())[args.backend]
if args.backend == 'ardy':
    path = snapshot_download(pins['checkpoint'], revision=pins['checkpointRevision'])
    if args.encoder_cache:
        args.encoder_cache.mkdir(parents=True, exist_ok=True)
        selected = dict(pins['textEncoderAdapters'])
        selected[pins['gatedModel']] = pins['textEncoderBaseRevision']
        for repo, revision in selected.items():
            result = Path(snapshot_download(repo, revision=revision, cache_dir=args.encoder_cache,
                allow_patterns=['*.json', '*.safetensors', '*.model', '*.txt']))
            refs = result.parent.parent / 'refs'; refs.mkdir(exist_ok=True); (refs/'main').write_text(revision)
    print(json.dumps(dict(backend='ardy', checkpointSnapshot=path, inferenceValidated=False), indent=2))
    raise SystemExit(0)
path = Path(snapshot_download(pins['checkpoint'], revision=pins['checkpointRevision'],
    ignore_patterns=['data/amass/*', 'data/humanml3d/t2m_train.npy', 'evaluation/closd/CloSD.pkl', 'checkpoints/dip/*/opt*.pt'], max_workers=2))
# Stock CLoSD asks for main. Offline launches resolve that name to this exact selected revision.
refs = path.parent.parent / 'refs'
refs.mkdir(exist_ok=True)
(refs / 'main').write_text(pins['checkpointRevision'])
snapshot_download('distilbert/distilbert-base-uncased', revision=pins['distilbertRevision'],
    allow_patterns=['config.json', 'model.safetensors', 'tokenizer.json', 'tokenizer_config.json', 'vocab.txt'])
print(json.dumps(dict(backend='closd', runtimeSnapshot=str(path), revision=pins['checkpointRevision'],
    offlineLaunchRequired=True, inferenceValidated=False), indent=2))
