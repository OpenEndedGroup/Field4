#!/usr/bin/env python3
"""Export real completed Nancy analysis previews for the offline Starting Point.

Run the audio_ai_analysis docs capture first, with the Field Audio API and analysis
worker available. This copies service results; it never creates analysis values.
Commercial source audio is not copied into the repository.
"""
import argparse
import hashlib
import json
import urllib.request
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='http://127.0.0.1:8765')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    folder = root / 'public/docs/assets/audio_ai_analysis'
    folder.mkdir(parents=True, exist_ok=True)
    base = args.url.rstrip('/')

    def get(path):
        with urllib.request.urlopen(base + path, timeout=60) as response:
            return json.load(response)

    source = get('/v1/assets/lookup?filename=*Nancy*')
    expected = dict(asset_id=source['id'], start_seconds=18.0, duration_seconds=24.0)
    remaining = {'analysis.beats', 'analysis.pitch_mono', 'analysis.pitch_poly'}
    manifest = {'source': expected, 'results': {}, 'files': {}}

    def write(name, value):
        data = (json.dumps(value, indent=2, allow_nan=False) + '\n').encode()
        (folder / name).write_bytes(data)
        manifest['files'][name] = hashlib.sha256(data).hexdigest()

    for summary in get('/v1/jobs?limit=500'):
        operation = summary['operation']
        if operation not in remaining or summary['state'] != 'complete':
            continue
        job = get('/v1/jobs/' + summary['id'])
        result = get('/v1/analysis-results/' + job['analysis_result_id'])
        if result['source'] != expected:
            continue
        preview = get('/v1/analysis-results/' + result['id'] + '/preview?points=1600')
        assert preview['operation'] == operation and preview['duration_seconds'] == 24.0
        assert preview.get('series') or preview.get('notes'), operation
        # Verify the immutable full-resolution result too, without publishing its binary.
        representation = result['data']
        with urllib.request.urlopen(base + representation['binary_url'], timeout=60) as response:
            binary = response.read()
        assert 'sha256:' + hashlib.sha256(binary).hexdigest() == representation['asset_id']
        label = operation.split('.')[-1]
        write(label + '-preview.json', preview)
        write(label + '-result.json', result)
        write(label + '-job.json', job)
        manifest['results'][operation] = {
            'id': result['id'], 'backend': job['resolved_backend'],
            'dataAssetId': representation['asset_id'],
            'series': len(preview.get('series', [])), 'notes': len(preview.get('notes', [])),
        }
        remaining.remove(operation)
    if remaining:
        raise SystemExit('Run the real Nancy capture first; missing completed jobs: ' + str(sorted(remaining)))
    (folder / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(json.dumps(manifest['results'], indent=2))


if __name__ == '__main__':
    main()
