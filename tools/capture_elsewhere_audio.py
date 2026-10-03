#!/usr/bin/env python3
"""Export real Field Audio results for the Elsewhere docs captures.

Run with the API and workers started from /home/marc/temp/music. No models or
preview values are synthesized. Existing completed speech/spectral/corpus jobs
are reused; codec and MuScriptor requests use the current service cache contract.
The Pat render requires the pinned Dirt-Samples subset (fetchDocsDirtSamples).
"""
import argparse
import hashlib
import json
import time
import subprocess
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='http://127.0.0.1:8765')
    args = parser.parse_args()
    base = args.url.rstrip('/')
    manifests = {}

    def get(path):
        return json.loads(urllib.request.urlopen(base + path, timeout=120).read())

    def write(folder, name, value):
        folder.mkdir(parents=True, exist_ok=True)
        data = (json.dumps(value, indent=2) + '\n').encode() if not isinstance(value, bytes) else value
        (folder / name).write_bytes(data)
        manifests[str((folder / name).relative_to(ROOT))] = hashlib.sha256(data).hexdigest()

    def download(folder, name, rep):
        data = urllib.request.urlopen(base + rep['binary_url'], timeout=120).read()
        assert 'sha256:' + hashlib.sha256(data).hexdigest() == rep['asset_id'], name
        write(folder, name, data)

    def submit(request):
        accepted = json.loads(urllib.request.urlopen(urllib.request.Request(
            base + '/v1/operations', data=json.dumps(request).encode(),
            headers={'Content-Type': 'application/json'}), timeout=120).read())
        deadline = time.monotonic() + 900
        while time.monotonic() < deadline:
            job = get('/v1/jobs/' + accepted['job_id'])
            if job['state'] == 'complete':
                key = 'codec_result_id' if request['operation'] == 'codec.reconstruct' else 'analysis_result_id'
                route = 'codec-results' if key == 'codec_result_id' else 'analysis-results'
                return job, get('/v1/' + route + '/' + job[key])
            if job['state'] in ('failed', 'cancelled'):
                raise RuntimeError(job)
            time.sleep(2)
        raise TimeoutError(accepted)

    jobs = get('/v1/jobs?limit=500')
    for operation, folder_name in [('analysis.embed_speech', 'audio_ai_speech_embeddings'),
                                   ('analysis.embed_spectral', 'audio_ai_spectral_embeddings'),
                                   ('analysis.find_neighbours', 'audio_ai_corpus_microscope')]:
        folder = ROOT / 'public/docs/assets' / folder_name
        seen = set()
        for summary in jobs:
            if summary['operation'] != operation or summary['state'] != 'complete':
                continue
            job = get('/v1/jobs/' + summary['id'])
            backend = job['resolved_backend']
            if backend in seen:
                continue
            seen.add(backend)
            result = get('/v1/analysis-results/' + job['analysis_result_id'])
            label = backend.split('.')[-1] if operation == 'analysis.embed_speech' else 'result'
            write(folder, label + '.json', result)
            write(folder, label + '-job.json', job)
            if operation == 'analysis.find_neighbours':
                write(folder, 'corpus.json', get('/v1/analysis-results/' + result['id'] + '/corpus'))
                asset = get('/v1/assets/' + result['source']['asset_id'])
                audio = next(r for r in asset['representations'] if r['role'] == 'canonical_project')
                write(folder, 'query-audio.json', audio)
            else:
                write(folder, label + '-preview.json', get('/v1/analysis-results/' + result['id'] + '/preview?points=1200'))
                if operation == 'analysis.embed_spectral':
                    rep = next(r for r in result['artifacts'] if r['role'] == 'spectral_embedding_messagepack')
                    download(folder, 'frames.msgpack', rep)
            print(operation, backend, result['id'], flush=True)

    source = ROOT / 'public/docs/assets/faust/poly-chord-eight-voices.wav'
    imported = json.loads(urllib.request.urlopen(urllib.request.Request(
        base + '/v1/assets/import?filename=field-docs-poly-chord.wav&media_type=audio/wav',
        data=source.read_bytes(), headers={'Content-Type': 'application/octet-stream'}), timeout=120).read())
    duration = min(12.0, next(r['duration_seconds'] for r in imported['representations'] if r['duration_seconds']))
    job, result = submit({'operation': 'codec.reconstruct',
        'inputs': [{'asset_id': imported['id'], 'start_seconds': 0, 'duration_seconds': duration}],
        'parameters': {}, 'backend_preference': 'audiocraft.encodec_musicgen_style_32khz',
        'requested_by': 'field:docs/elsewhere/codec'})
    folder = ROOT / 'public/docs/assets/audio_ai_codec'
    write(folder, 'result.json', result)
    write(folder, 'job.json', job)
    for name, key in [('source.wav', 'model_input'), ('reconstruction.wav', 'reconstruction_native'), ('tokens.npy', 'tokens')]:
        download(folder, name, result[key])
    print('codec', result['id'], flush=True)

    old_job = next(j for j in jobs if j['operation'] == 'analysis.transcribe_music' and j['state'] == 'complete')
    old = get('/v1/jobs/' + old_job['id'])
    result = get('/v1/analysis-results/' + old['analysis_result_id'])
    job, result = submit({'operation': 'analysis.transcribe_music', 'inputs': [result['source']],
        'parameters': {'detect_tempo': False, 'include_quantized_midi': False},
        'backend_preference': 'analysis.muscriptor.small', 'requested_by': 'field:docs/elsewhere/muscriptor'})
    folder = ROOT / 'public/docs/assets/audio_ai_muscriptor'
    write(folder, 'result.json', result)
    write(folder, 'job.json', job)
    preview = get('/v1/analysis-results/' + result['id'] + '/preview?points=1200')
    assert all(0 <= n['start_seconds'] <= n['end_seconds'] <= result['source']['duration_seconds'] for n in preview['notes'])
    write(folder, 'preview.json', preview)
    asset = get('/v1/assets/' + result['source']['asset_id'])
    download(folder, 'source.wav', next(r for r in asset['representations'] if r['media_type'] in ('audio/wav', 'audio/x-wav')))
    print('muscriptor', result['id'], len(preview['notes']), flush=True)
    folder = ROOT / 'public/docs/assets/audio_render_analysis'
    folder.mkdir(parents=True, exist_ok=True)
    subprocess.run(['uv', 'run', '--project', '.', '--group', 'backdoor', 'python',
        'tools/field_backdoor.py', '--json', '--port', '9477',
        '--command', 'new-box --name ElsewherePat',
        '--command', 'run @last --file tools/docs_capture_elsewhere/render_pat.kt --timeout 120'],
        cwd=ROOT, stdout=subprocess.DEVNULL, check=True)
    source = folder / 'pat-dirt-study.wav'
    imported = json.loads(urllib.request.urlopen(urllib.request.Request(
        base + '/v1/assets/import?filename=pat-dirt-study.wav&media_type=audio/wav',
        data=source.read_bytes(), headers={'Content-Type': 'application/octet-stream'}), timeout=120).read())
    write(folder, 'asset.json', imported)
    write(folder, 'pat-dirt-study.wav', source.read_bytes())
    write(folder, 'render-provenance.json', (folder / 'render-provenance.json').read_bytes())
    for operation in ['analysis.beats', 'analysis.pitch_mono', 'analysis.pitch_poly']:
        job, result = submit({'operation': operation,
            'inputs': [{'asset_id': imported['id'], 'start_seconds': 0, 'duration_seconds': 7.5}],
            'parameters': {}, 'requested_by': 'field:docs/elsewhere/pat'})
        label = operation.split('.')[-1]
        write(folder, label + '.json', result)
        write(folder, label + '-job.json', job)
        write(folder, label + '-preview.json', get('/v1/analysis-results/' + result['id'] + '/preview?points=1200'))
        print(operation, result['id'], flush=True)
    write(ROOT / 'public/docs/assets/elsewhere_audio', 'manifest.json', manifests)


if __name__ == '__main__':
    main()
