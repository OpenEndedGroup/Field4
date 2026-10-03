#!/usr/bin/env python3
"""Export the existing Underfit proof and a matched baseline as real docs audio.

Requires ffmpeg and the Field Audio API for genuine waveform envelopes. Generation
is separate: use music/scripts/run-underfit-proof.py with strength 0 and otherwise
identical controls to result-cfg15-30s. This exporter never runs a model.
"""
import argparse
import hashlib
import json
import subprocess
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--proof-root', type=Path, default=Path('/home/marc/temp/music/audio-project/underfit-proof'))
    parser.add_argument('--api', default='http://127.0.0.1:8765')
    args = parser.parse_args()
    out = ROOT / 'public/docs/assets/audio_ai_underfit_mancini'
    out.mkdir(parents=True, exist_ok=True)
    candidate = json.loads((args.proof_root / 'result-cfg15-30s/proof.json').read_text())
    baseline = json.loads((args.proof_root / 'docs-baseline-cfg15-30s/proof.json').read_text())
    controls = []
    for proof in (candidate, baseline):
        operation = json.loads(json.dumps(proof['operation']))
        operation['parameters']['lora_checkpoints'][0].pop('strength')
        for key in ('force_regenerate', 'requested_by'):
            operation.pop(key, None)
        controls.append(operation)
    assert controls[0] == controls[1], 'baseline controls/source/seed differ'
    assert candidate['operation']['parameters']['lora_checkpoints'][0]['strength'] == 0.85
    assert baseline['operation']['parameters']['lora_checkpoints'][0]['strength'] == 0.0
    manifest = {'source': 'Modern Jazz Quartet, Skating; first 12 seconds of the retained proof excerpt',
                'exports': {}, 'inputs': {}, 'encoder': 'ffmpeg libopus 160k, without gain normalization'}
    paths = [('source', args.proof_root / 'source-mjq-skating.wav', None),
             ('candidate', args.proof_root / 'result-cfg15-30s/proxy-48k.wav', candidate),
             ('baseline', args.proof_root / 'docs-baseline-cfg15-30s/proxy-48k.wav', baseline)]
    for label, path, proof in paths:
        data = path.read_bytes()
        digest = hashlib.sha256(data).hexdigest()
        if proof:
            c = proof['candidate_set']['candidates'][0]
            assert 'sha256:' + digest == c['proxy_representation']['asset_id']
            native = path.with_name('native.wav').read_bytes()
            assert 'sha256:' + hashlib.sha256(native).hexdigest() == c['asset']['id']
            (out / (label + '-proof.json')).write_text(json.dumps(proof, indent=2) + '\n')
        manifest['inputs'][label] = {'filename': path.name, 'sha256': digest}
        subprocess.run(['ffmpeg', '-nostdin', '-y', '-v', 'error', '-i', str(path), '-t', '12' if label == 'source' else '30',
                        '-c:a', 'libopus', '-b:a', '160k', str(out / (label + '.opus'))], check=True)
        query = urllib.parse.urlencode({'filename': 'underfit-docs-' + label + '.wav', 'media_type': 'audio/wav'})
        imported = json.loads(urllib.request.urlopen(urllib.request.Request(
            args.api + '/v1/assets/import?' + query, data=data,
            headers={'Content-Type': 'application/octet-stream'}), timeout=120).read())
        rep = next(r for r in imported['representations'] if r['media_type'] in ('audio/wav', 'audio/x-wav'))
        envelope = urllib.request.urlopen(args.api + '/v1/representations/' + rep['id'] + '/waveform?points=1200', timeout=120).read()
        (out / (label + '-waveform.json')).write_bytes(envelope)
    for path in sorted(out.iterdir()):
        if path.name == 'manifest.json' or path.suffix == '.png':
            continue
        manifest['exports'][path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
    (out / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print('Exported source, original candidate, matched baseline and genuine waveform envelopes')


if __name__ == '__main__':
    main()
