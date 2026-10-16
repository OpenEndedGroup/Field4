"""Persist compact verdicts, genuine captures and exact emitted WAV bytes without duplicating HTML."""
import base64
import hashlib
import io
import json
import re
import subprocess
import tarfile
import xml.etree.ElementTree as E
from pathlib import Path

ROOT = Path.cwd()
OUT = ROOT / 'build/box-code-revision/adoption-2'
DOC = ROOT / 'dev_docs/agents/box_code_adoption_batch_2'
BOXES = ['loris_edit_partials', 'loris_two_resolutions', 'loris_spectral_whirlpool', 'loris_label_align_morph']
records = []
for label in ['original', 'revised']:
    records += [label + '_create']
    for box in BOXES: records += [label + '_' + box + suffix for suffix in ['', '_output', '_capture']]
for name in ['audio_ai_spectral_embeddings', 'audio_ai_corpus_microscope']:
    records += [name + '_create', name + '_real_transport_error']
records += ['spectral_gallery_create', 'spectral_gallery_real_artifacts', 'cancel_synthesis_create',
            'cancel_synthesis_launch', 'cancel_synthesis_stop', 'cancel_synthesis_output']
results = {n: json.loads((OUT / (n + '.json')).read_text()) for n in records}
assert all(r['ok'] for r in results.values())
xmls = list((ROOT / 'build/test-results/jvmTest').glob('TEST-*.xml'))
suites = [E.parse(p).getroot() for p in xmls]
counts = {k: sum(int(s.get(k, 0)) for s in suites) for k in ['tests', 'failures', 'errors', 'skipped']}
assert counts == dict(tests=15, failures=0, errors=0, skipped=0), counts
comparison = json.loads((OUT / 'wav-equivalence.json').read_text())
assert comparison['identical'] and comparison['original'] == comparison['revised']
cancellation = json.loads((OUT / 'synthesis-cancellation.json').read_text())
assert cancellation['exited'] and cancellation['requestRemoved'] and cancellation['staleResultCount'] == 0
source = ['dev_docs/agents/transcripts/' + n + '.yaml' for n in [
    'loris_partial_explorer', 'audio_ai_spectral_embeddings', 'audio_ai_corpus_microscope']]
source += ['src/jvmTest/kotlin/afield/audio/ai/TrackedAnalysisTest.kt',
           'src/jvmMain/kotlin/afield/runtime/lifecycle/AsyncOperation.kt',
           'src/jvmMain/kotlin/afield/audio/ai/TrackedAnalysis.kt',
           'src/jvmMain/kotlin/afield/audio/analysis/loris/Loris.kt']
record = {'baseline': 'e97a8c4a9', 'sourceSha256': {p: hashlib.sha256((ROOT / p).read_bytes()).hexdigest() for p in source},
          'tests': counts, 'preservation': json.loads((OUT / 'preservation.json').read_text()),
          'wavEquivalence': comparison, 'nativeSynthesisCancellation': cancellation,
          'runtimeBatches': len(results), 'runtimeCommands': sum(len(r['results']) for r in results.values()),
          'allRecordedRuntimeCommandsPassed': True,
          'limits': ['No fresh Field Audio model job: configured port serves another application.',
                     'HTTP/WebSocket ownership tests use the repository protocol service, not model inference.',
                     'An already-running t-SNE fit has no interrupt hook; later fits/publication are suppressed.',
                     'No audition claim.'],
          'evidenceEncoding': 'Large inline text omitted from compact verdicts with length/hash; exact real WAV bytes deduplicated by hash; screenshots unmodified.'}
(DOC / 'verification.json').write_text(json.dumps(record, indent=2) + '\n')

def compact(value):
    if isinstance(value, str) and len(value) > 5000:
        encoded = value.encode()
        return {'omittedLargeText': {'utf8Bytes': len(encoded), 'sha256': hashlib.sha256(encoded).hexdigest()}}
    if isinstance(value, list): return [compact(v) for v in value]
    if isinstance(value, dict): return {k: compact(v) for k, v in value.items()}
    return value

with tarfile.open(DOC / 'evidence.tar.gz', 'w:gz') as t:
    def add_bytes(name, body):
        info = tarfile.TarInfo(name); info.size = len(body); t.addfile(info, io.BytesIO(body))
    wavs = set()
    for name, result in results.items():
        add_bytes('verdicts/' + name + '.json', json.dumps(compact(result), indent=2).encode())
        if name.endswith('_output') and name.startswith(('original_loris_', 'revised_loris_')):
            for encoded in re.findall(r'data:audio/wav;base64,([A-Za-z0-9+/=]+)', json.dumps(result)):
                wav = base64.b64decode(encoded); digest = hashlib.sha256(wav).hexdigest()
                if digest not in wavs:
                    add_bytes('actual-audio/' + digest + '.wav', wav); wavs.add(digest)
    for path in OUT.glob('*.png'):
        if not path.name.startswith('original_'): t.add(path, arcname='captures/' + path.name)
    for path in OUT.glob('*.yaml'): t.add(path, arcname='fixtures/' + path.name)
    for path in xmls: t.add(path, arcname='tests/' + path.name)
    for name in ['format.log', 'format-check.log', 'idioms.log', 'compile.log', 'async-tests.log',
                 'compile-spectral-final.log', 'runtime.log', 'runtime-final-loris.log', 'cancellation.log', 'cancellation-short-worker.log']:
        t.add(OUT / name, arcname='logs/' + name)
    for path in source[:3]:
        add_bytes('originals/' + Path(path).name, subprocess.check_output(['git', 'show', 'e97a8c4a9:' + path]))
print(len(results), 'batches;', record['runtimeCommands'], 'commands;', counts, ';', len(wavs), 'exact WAVs')
