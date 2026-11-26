"""Apply reviewed readability edits only within the frozen authored source spans."""
import collections
import json
import re

from run import S


def literals(code):
    # Skip comments and all Kotlin strings; balance calls so nested lists work too.
    token = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/|\blistOf\s*\(|[()]')
    stack = []
    edits = []
    for m in token.finditer(code):
        text = m[0]
        if text.startswith('listOf'):
            stack.append(m)
        elif text == '(':
            stack.append(None)
        elif text == ')' and stack:
            opening = stack.pop()
            if opening and code[opening.end():m.start()].strip():
                edits += [(opening.start(), opening.end(), '['), (m.start(), m.end(), ']')]
    for a, b, replacement in sorted(edits, reverse=True):
        code = code[:a] + replacement + code[b:]
    return code, len(edits) // 2


def revise(code, path):
    code, count = literals(code)
    reasons = ['collection literals'] if count else []
    if path.endswith('addressable_paint_strokes.yaml'):
        before = code
        code = code.replace('val sheet = PaintStrokes(700, aspect = 1.4, seed = 3)',
                            'prop<PaintStrokes>(paintSheet) = PaintStrokes(700, aspect = 1.4, seed = 3)\nval sheet = paintSheet')
        code = code.replace('fromContext<PaintStrokes>()', 'paintSheet')
        if code != before:
            reasons.append('named inherited stroke sheet')
    if path.endswith('pattern_rewrite_worked_example.yaml'):
        start = code.index('display(')
        code = code[:start] + 'markdown("main", "`rev(hello)` = **$answer**, a string built at compile time from the identifier\'s text.")\n'
        reasons.append('Markdown result')
    if path.endswith('interaction_nets_examples.md'):
        old = '''display(
        "stats",
        "<div style='font-family:monospace;font-size:14px'>$term · step $steps · $lastRule · " +
            "${net.nodes.size} agents$answer</div>",
    )'''
        if old in code:
            code = code.replace(old, 'markdown("stats", "`$term` · step $steps · $lastRule · ${net.nodes.size} agents$answer")')
            reasons.append('Markdown reaction readout')
        old = """display(
        \"stats\",
        \"<div style='font-family:monospace;font-size:14px'>round $round · $lastFired firing · \" +
            \"$interactions interactions · ${net.nodes.size} agents · widest $widest$answer</div>\",
    )"""
        if old in code:
            code = code.replace(old, 'markdown("stats", "round $round · $lastFired firing · $interactions interactions · ${net.nodes.size} agents · widest $widest$answer")')
            reasons.append('Markdown parallel reduction readout')
    if path.endswith('pattern_language.md'):
        old = 'display("<pre>" + lines + "</pre>")'
        if old in code:
            code = code.replace(old, 'markdown("```text\\n$lines\\n```")')
            reasons.append('Markdown event readout')
    if path.endswith('nile_gezira_svg.yaml'):
        old = 'display("readout", "<pre style=\'margin:0; white-space:pre-wrap\'>$readout</pre>")'
        if old in code:
            code = code.replace(old, 'markdown("readout", "```text\\n$readout\\n```")')
            reasons.append('Markdown comparison readout')
    return code, reasons, count


def main():
    receipt = S.HERE / 'revision-verification.json'
    if receipt.exists():
        raise SystemExit('Reviewed edits already applied; inspect the receipt before changing source again.')
    frozen = json.loads((S.OUT / 'bodies.json').read_text())
    changes = []
    for path in sorted({r['path'] for r in frozen}):
        # Study snapshots are recorded evidence, including named speculative variants.
        if '/studies/' in path:
            continue
        source = S.ROOT / path
        before = source.read_text()
        original = __import__('subprocess').check_output(['git', 'show', f"{json.loads((S.HERE/'scope.json').read_text())['head']}:{path}"], cwd=S.ROOT, text=True)
        hashes = {r['sha256'] for r in frozen if r['path'] == path}
        selected = {i for i, r in enumerate(S.spans(path, original)) if r['sha256'] in hashes}
        edits = []
        for i, r in enumerate(S.spans(path, before)):
            if i not in selected or r['language'] != 'kotlin' or '_preamble_.' in path:
                continue
            code, reasons, count = revise(r['code'], path)
            if code == r['code']:
                continue
            if r['kind'] == 'yaml':
                replacement = ('|\n' if code.endswith('\n') else '|-\n') + ''.join(' ' * r['indent'] + line + '\n' if line else '\n' for line in code.rstrip('\n').splitlines())
            else:
                replacement = code.rstrip() + '\n'
            edits.append((r['start'], r['end'], replacement))
            changes.append(dict(path=path, index=i, reasons=reasons, collectionLiterals=count))
        after = before
        for a, b, code in sorted(edits, reverse=True):
            after = after[:a] + code + after[b:]
        if after != before:
            source.write_text(after)
    receipt.write_text(json.dumps(dict(changes=changes), indent=2) + '\n')
    print(len(changes), 'authored bodies revised;', sum(r['collectionLiterals'] for r in changes), 'collection literals')


if __name__ == '__main__':
    main()
