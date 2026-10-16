#!/usr/bin/env python3
"""Collect editor-formatter candidates in private copies; never rewrite teaching sources.

Run from the repository root after preparing first-pass bodies.jsonl. Gradle invocations
are sequential. All output is under build/box-code-revision/first-pass/mechanical/.
"""
from pathlib import Path
import difflib
import json
import re
import shlex
import subprocess
import yaml

ROOT = Path.cwd()
RUN = ROOT / 'build/box-code-revision/first-pass'
OUT = RUN / 'mechanical'
LANGUAGE_EXCEPTIONS = {
    'templates/advanced_remote/c04566d0-6d97-442e-ba39-c02de8105b05_code_.kt':
        'metadata mainLanguage is remotejs; inventory code_language fallback says Kotlin',
    'templates/advanced_remote/94f15e77-0d57-498c-a573-b7af7ac70a07_code_.kt':
        'JavaScript source with absent code_language/mainLanguage; needs language review',
}


def tokens(code, language):
    """Conservative code-token check, preserving quoted/raw string contents exactly."""
    result, i, depth = [], 0, 0
    while i < len(code):
        if code[i].isspace():
            i += 1
        elif code.startswith('//', i):
            end = code.find('\n', i)
            i = len(code) if end < 0 else end
        elif code.startswith('/*', i):
            depth, i = 1, i + 2
            while i < len(code) and depth:
                if code.startswith('/*', i): depth, i = depth + 1, i + 2
                elif code.startswith('*/', i): depth, i = depth - 1, i + 2
                else: i += 1
            if depth: raise ValueError('unclosed block comment')
        elif code.startswith('"""', i):
            end = code.find('"""', i + 3)
            if end < 0: raise ValueError('unclosed raw string')
            result.append(code[i:end + 3]); i = end + 3
        elif code[i] in ('"', "'"):
            start, quote = i, code[i]; i += 1
            while i < len(code):
                if code[i] == '\\': i += 2
                elif code[i] == quote: i += 1; break
                else: i += 1
            else: raise ValueError('unclosed quoted literal')
            result.append(code[start:i])
        else:
            match = re.match(r'[\w$]+', code[i:])
            if match:
                value = match.group()
                prefix = code[code.rfind('\n', 0, i) + 1:i]
                if language == 'kotlin' and value == 'import' and not prefix.strip():
                    end = code.find('\n', i); i = len(code) if end < 0 else end
                else:
                    result.append(value); i += len(value)
            else:
                result.append(code[i]); i += 1
    return result


def markers(code):
    return [re.sub(r'\s+', ' ', line.strip()).replace('// %-', '//%-')
            for line in code.splitlines()
            if re.match(r'^\s*(?://\s*%-|/\*\s*%-\s*\*/)', line)]


def imports(code):
    return re.findall(r'(?m)^[ \t]*import[ \t]+([^\n]+)', code)


def main():
    inputs = OUT / 'inputs'; diffs = OUT / 'diffs'
    inputs.mkdir(parents=True, exist_ok=True); diffs.mkdir(exist_ok=True)
    bodies = [json.loads(line) for line in (RUN / 'bodies.jsonl').read_text().splitlines()]
    todo, records = [], []
    for body in bodies:
        key = body['language'] + '-' + body['sha256']
        record = {k: body[k] for k in ('language', 'sha256', 'occurrences')}
        record['key'] = key
        exceptions = [LANGUAGE_EXCEPTIONS[o['path']] for o in body['occurrences'] if o['path'] in LANGUAGE_EXCEPTIONS]
        if exceptions or body['language'] == 'kotlinjs':
            record.update(status='language-review', detail=exceptions or ['KotlinJS head requires its language-specific formatter'])
            records.append(record); continue
        path = inputs / (key + ('_code_.kt' if body['language'] == 'kotlin' else '.yaml'))
        if body['language'] == 'kotlin': path.write_text(body['code'])
        else: path.write_text('fragment: |\n' + ''.join('    ' + line + '\n' for line in body['code'].splitlines()))
        todo.append((body, record, path))
    for offset in range(0, len(todo), 180):
        batch = todo[offset:offset + 180]
        args = ['--imports', *[str(path.relative_to(ROOT)) for _, _, path in batch]]
        log = OUT / f'batch-{offset // 180 + 1:02}.log'
        with log.open('w') as stream:
            result = subprocess.run(['./gradlew', 'formatBoxCode', '--args=' + shlex.join(args)],
                                    cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT)
        if result.returncode:
            raise SystemExit(f'Formatter invocation failed: {log}; inspect before rerunning')
        skipped = {line.split(' (', 1)[0].removeprefix('skipped '): line
                   for line in log.read_text(errors='replace').splitlines() if line.startswith('skipped ')}
        for body, record, path in batch:
            skip = next((v for k, v in skipped.items() if str(path.relative_to(ROOT)) in k), None)
            if skip:
                record.update(status='parse-review', detail=skip)
            else:
                after = path.read_text() if body['language'] == 'kotlin' else yaml.safe_load(path.read_text())['fragment']
                before = body['code']
                record['fragment_markers_preserved'] = markers(before) == markers(after)
                try:
                    record['code_tokens_preserved'] = tokens(before, body['language']) == tokens(after, body['language'])
                except ValueError as error:
                    record['token_check_detail'] = str(error); record['code_tokens_preserved'] = None
                record['imports_before'] = imports(before) if body['language'] == 'kotlin' else []
                record['imports_after'] = imports(after) if body['language'] == 'kotlin' else []
                changed = before.rstrip('\n') != after.rstrip('\n')
                record['status'] = 'candidate' if changed else 'already-formatted'
                if not record['fragment_markers_preserved'] or record['code_tokens_preserved'] is False:
                    record['status'] = 'preservation-review'
                if changed:
                    diff = diffs / (record['key'] + '.diff')
                    diff.write_text(''.join(difflib.unified_diff(before.splitlines(True), after.splitlines(True),
                        fromfile='before/' + record['key'], tofile='after/' + record['key'])))
                    record['diff'] = str(diff.relative_to(ROOT))
            records.append(record)
        (OUT / 'results.jsonl').write_text(''.join(json.dumps(r) + '\n' for r in records))
        print(f'Mechanical formatter: {min(offset + 180, len(todo))}/{len(todo)} bodies processed', flush=True)
    (OUT / 'summary.json').write_text(json.dumps({
        'bodies':len(bodies), 'statuses':{s:sum(r['status']==s for r in records) for s in sorted({r['status'] for r in records})},
        'teaching_sources_modified':False, 'formatter':'Field editor: ktfmt, deterministic box imports, GLSL formatter',
        'verification':'format candidates and preservation checks; compilation/runtime remains per-edit work',
    }, indent=2) + '\n')


if __name__ == '__main__':
    main()
