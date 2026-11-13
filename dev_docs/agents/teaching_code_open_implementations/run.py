"""Format, exercise and capture the exact teaching examples named by a batch.

Run from the repository root: uv run --group docs python <this file> ACTION BATCH.
All scratch files and logs go under build/. Captures use the normal docs runner.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
OUT = ROOT / 'build/teaching-code-open-implementations'
FENCE = re.compile(r'^(`{3,})(diffstatic_runner|diff_runner|diffstatic)(?![\w-])([^\n]*)\n(.*?)^\1[ \t]*$', re.M | re.S)


def selected(batch):
    for target in batch['targets']:
        path = ROOT / target['path']
        text = path.read_text()
        found = []
        for index, match in enumerate(FENCE.finditer(text), 1):
            name = re.search(r'\bname="([^"]+)"', match[3])
            if (name and name[1] == target.get('name')) or (target.get('contains') and target['contains'] in match[4]):
                found.append((target, path, text, match, index))
        assert len(found) == 1, (target, len(found))
        yield found[0]


def save_receipt(kind, batch, value):
    encoded = json.dumps(value, indent=2) + '\n'
    (OUT / f'{kind}-receipt.json').write_text(encoded)
    (OUT / f"{kind}-{batch.get('_receiptName', 'unnamed')}-receipt.json").write_text(encoded)


def format_batch(batch):
    plans = []
    for n, (target, path, text, match, _) in enumerate(selected(batch)):
        folds = []
        def carry(m):
            folds.append(m[0])
            return f'/* TEACHING_FOLD_{len(folds)-1} */'
        before = re.sub(r'^[ \t]*:::(?:static|fold[^\n]*|endfold|)[ \t]*$', carry, match[4], flags=re.M)
        scratch = OUT / f'format-{n}_code_.kt'
        scratch.write_text(before)
        plans.append((path, text, match.start(4), match.end(4), before, scratch, folds))
    args = shlex.join(['--imports', *[str(p[5].relative_to(ROOT)) for p in plans]])
    result = subprocess.run(['./gradlew', 'formatBoxCode', '--args='+args], cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    print(result.stdout, end='')
    result.check_returncode()
    assert not re.search(r'^skipped ', result.stdout, re.M), 'formatter skipped a selected body'
    edits = {}
    for path, text, start, end, before, scratch, folds in plans:
        after = scratch.read_text()
        old_strings = list(re.finditer(r'"""[\s\S]*?"""', before))
        new_strings = list(re.finditer(r'"""[\s\S]*?"""', after))
        assert len(old_strings) == len(new_strings)
        for old, new in reversed(list(zip(old_strings, new_strings))):
            after = after[:new.start()] + old[0] + after[new.end():]
        for i, fold in enumerate(folds):
            after, count = re.subn(
                rf'^([^\n]*?)/\* TEACHING_FOLD_{i} \*/[ \t]*(?:\n|$)',
                lambda m: (m[1].rstrip()+'\n' if m[1].strip() else '')+fold+'\n',
                after, flags=re.M,
            )
            assert count == 1, (path, fold, count)
        edits.setdefault(path, (text, []))[1].append((start, end, after.rstrip()+'\n'))
    for path, (text, replacements) in edits.items():
        assert path.read_text() == text, f'changed while formatting: {path}'
        for start, end, after in sorted(replacements, reverse=True):
            text = text[:start] + after + text[end:]
        path.write_text(text)


def probe_batch(batch):
    commands = []
    cases = []
    sources = []
    for target, _, _, match, _ in selected(batch):
        sources.append(dict(path=target['path'], name=target.get('name'), fenceSha256=hashlib.sha256(match[0].encode()).hexdigest()))
        commands.append('new-box --name '+shlex.quote(target.get('name', 'mapping')))
        for n, case in enumerate(target.get('probes', [])):
            code = match[4]
            if not target.get('probeFullCode'):
                code = re.sub(r'^:::fold imports[^\n]*\n(.*?)^:::endfold[ \t]*$', r'\1', code, flags=re.M | re.S)
                code = code.split(':::fold', 1)[0]
            code = re.sub(r'^[ \t]*:::(?:static|fold[^\n]*|endfold)[ \t]*$', '', code, flags=re.M).strip()
            until = case.get('probeUntil', target.get('probeUntil'))
            if until:
                assert code.count(until) == 1, target
                code = code.split(until, 1)[0]
            for old, new in case.get('replace', {}).items():
                assert old in code, (target, old)
                code = code.replace(old, new)
            token = f'TEACHING_PROBE_PASSED_{len(cases)}'
            if case.get('before'):
                anchor = case['before']
                assert code.count(anchor) == 1, (target, anchor)
                code = code.replace(anchor, case['assertions']+'\nprintln(\"'+token+'\")\n'+anchor)
            else:
                code += '\n' + case['assertions'] + '\n'
            path = OUT / f'probe-{len(cases)}.kt'
            path.write_text(code)
            commands.append('run @last --file '+shlex.quote(str(path))+' --timeout 180')
            if case.get('before'):
                commands.append('wait-output @last --kind all --contains '+token+' --timeout 180')
            cases.append(case['label'])
    args = [sys.executable, 'tools/field_backdoor.py', '--json']
    for command in commands:
        args += ['--command', command]
    with (OUT / 'probe-output.json').open('w') as output:
        subprocess.run(args, cwd=ROOT, stdout=output, check=True)
    save_receipt('probe', batch, {'passed': cases, 'sources': sources})
    print(f'{len(cases)} actual-code probes passed, with reruns in the same boxes')


def capture_batch(batch):
    sys.path.insert(0, str(ROOT / 'public'))
    sys.path.insert(0, str(ROOT / 'tools'))
    import docs_hooks as H
    from verification_runtime import verification_lock
    class Config(dict):
        config_file_path = str(ROOT / 'public/zensical.yml')
    os.environ['FIELD_DOCS_RUNNER_MODE'] = 'always'
    records = []
    with verification_lock(ROOT, label='Teaching-code captures'):
        state = H.RunnerState(Config(docs_dir=str(ROOT/'public/docs'), site_dir=str(OUT/'site'), use_directory_urls=True))
        try:
            for target, path, text, match, index in selected(batch):
                if not target.get('capture'):
                    continue
                assert match[2] in {'diffstatic_runner', 'diff_runner'}
                line = text[:match.start()].count('\n')+1
                options = H.parse_runner_options(runner_name=match[2], open_line=match[1]+match[2]+match[3], page_path=path, line_number=line, block_index=index)
                code = H.extract_runnable_code(lines=match[4].splitlines(keepends=True), fence_name=match[2], page_path=path, line_number=line, options=options)
                options = H.maybe_enable_output_wait(options=options, code=code)
                code = H.code_with_macro_digest(code=code, options=options, repo_root=ROOT, page_path=path)
                print('CAPTURE', target['path'], target['name'], flush=True)
                state.capture_with_environment(state.ensure_screenshot, code=code, options=options, page_path=path, block_index=index)
                records.append(dict(path=target['path'], name=target['name'], asset=options.screenshot, fenceSha256=hashlib.sha256(match[0].encode()).hexdigest()))
        finally:
            state.close()
    save_receipt('capture', batch, records)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['format', 'probe', 'capture'])
    parser.add_argument('batch')
    parser.add_argument('--from-target', help='Resume at this named target after a focused repair')
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    batch = json.loads((HERE/'batches.json').read_text())[args.batch]
    batch['_receiptName'] = args.batch + ('-from-' + args.from_target if args.from_target else '')
    if args.from_target:
        start = next(i for i, target in enumerate(batch['targets']) if target.get('name') == args.from_target)
        batch['targets'] = batch['targets'][start:]
    {'format': format_batch, 'probe': probe_batch, 'capture': capture_batch}[args.action](batch)
