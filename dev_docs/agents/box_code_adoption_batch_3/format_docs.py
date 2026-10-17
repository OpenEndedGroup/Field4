"""Format this batch's Markdown code states through Field's actual box formatter.

BoxCodeFormat accepts YAML and sidecars. Extract each Markdown before/after/static state
as a sidecar, carrying fold markers through as comments, then put it back verbatim.
"""
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / 'build/box-code-revision/adoption-3/format-docs'
PAGES = ['interpolants_examples.md', 'interaction_nets_examples.md']

def sections(text):
    fences = re.finditer(r'^```(?:diffstatic_runner|diff_runner)\b[^\n]*\n(.*?)^```', text, re.M | re.S)
    for fence in fences:
        body = fence[1]
        # A diff's states are independent Kotlin programs, each with its own imports.
        markers = list(re.finditer(r'^:::(?:static|before|after)\s*\n', body, re.M))
        for i, marker in enumerate(markers):
            start = fence.start(1) + marker.end()
            end = fence.start(1) + (markers[i + 1].start() if i + 1 < len(markers) else len(body))
            yield start, end

def main():
    OUT.mkdir(parents=True, exist_ok=True)
    extracted = []
    for page in PAGES:
        path = ROOT / 'public/docs' / page
        text = path.read_text()
        for i, (start, end) in enumerate(sections(text)):
            folds = []
            def carry(m):
                folds.append(m[0])
                return f'/* BOX_CODE_FOLD_{len(folds) - 1} */'
            body = re.sub(r'^:::(?:fold[^\n]*|endfold|)[ \t]*$', carry, text[start:end], flags=re.M)
            target = OUT / f'{path.stem}_{i}_code_.kt'
            target.write_text(body)
            extracted.append((path, start, end, target, folds))
    args = '--imports ' + ' '.join(str(x[3]) for x in extracted)
    subprocess.run([str(ROOT / 'gradlew'), 'formatBoxCode', '--args=' + args], cwd=ROOT, check=True)
    changed = []
    for page in PAGES:
        path = ROOT / 'public/docs' / page
        text = path.read_text()
        for _, start, end, target, folds in reversed([x for x in extracted if x[0] == path]):
            formatted = target.read_text()
            for i, marker in enumerate(folds):
                pattern = rf'^([^\n]*?)/\* BOX_CODE_FOLD_{i} \*/[ \t]*(?:\n|$)'
                formatted, count = re.subn(
                    pattern, lambda m: (m[1].rstrip() + '\n' if m[1].strip() else '') + marker + '\n',
                    formatted, flags=re.M,
                )
                assert count == 1, (target, marker)
            text = text[:start] + formatted.rstrip() + '\n' + text[end:]
        if text != path.read_text():
            changed.append(page)
            if '--check' not in sys.argv:
                path.write_text(text)
    print('Markdown formatter: ' + (', '.join(changed) if changed else 'clean'))
    if '--check' in sys.argv and changed:
        raise SystemExit(1)

if __name__ == '__main__':
    main()
