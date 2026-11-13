"""Reviewed collection and output edits, limited to frozen body indices."""
import json
import re
import subprocess
import run as C

S = C.S
R = C.load('previous_revision', C.PRIOR / 'revise.py')
scope = json.loads((C.HERE / 'scope.json').read_text())
frozen = json.loads((S.OUT / 'bodies.json').read_text())
records = []
for path in scope['paths']:
    file = C.ROOT / path
    text = file.read_text()
    original = subprocess.check_output(['git', 'show', scope['head'] + ':' + path], text=True)
    wanted = {r['sha256'] for r in frozen if r['path'] == path}
    selected = {i for i, r in enumerate(S.spans(path, original)) if r['sha256'] in wanted}
    edits = []
    for i, row in enumerate(S.spans(path, text)):
        if i not in selected or row['language'] != 'kotlin' or row.get('property') == 'preamble':
            continue
        before = row['code']
        code, count = R.literals(before)
        cards = [0]
        def card(m):
            cards[0] += 1
            return 'markdown("' + m[1] + '", "' + m[2].replace('<br>', '\\n\\n') + '")'
        code = re.sub(r'display\(\s*"([^"]+)"\s*,\s*"<div[^>]*>(.*?)</div>"\s*,?\s*\)', card, code, flags=re.S)
        # Name the palettes/text before applying receiver operations, so literals have an inferred list type.
        for pattern, replacement in [
            (r'for \(\(i, color\) in listOf\((rgb\([^\n]+)\)\.withIndex\(\)\)', r'val palette = [\1]\nfor ((i, color) in palette.withIndex())'),
            (r'for \(line in listOf\((thread, marginThread, silenceThread, elsewhereThread, tideThread)\)\)', r'val threads = [\1]\nfor (line in threads)'),
            (r'for \(\(i, word\) in listOf\(("a land", "of vague", "promise\.")\)\.withIndex\(\)\)', r'val phrases = [\1]\nfor ((i, word) in phrases.withIndex())'),
            (r'val frames\s*=\s*listOf\((door, eyes, hands, nonexistence, sipping, taut)\)\.associateWith', r'val readings = [\1]\nval frames = readings.associateWith'),
            (r'for \(\(name, vector\) in vectors\) for \(\(axis, suffix\) in listOf\(("x", "y", "z", "w")\)\.withIndex\(\)\)', r'val axes = [\1]\nfor ((name, vector) in vectors) for ((axis, suffix) in axes.withIndex())'),
        ]:
            code, n = re.subn(pattern, replacement, code)
            count += n
        if 'writeCloudComparison(file' in code and 'val rows =' in code:
            code = code[:code.index('val rows =')] + '''val comparisons = ["single scatter" to single, "fitted ambient" to ambient, "network" to neural]
val errors = comparisons.joinToString("\\n") { (name, image) ->
    val e = cloudError(image, reference, mask)
    "| $name | ${e.relativeMeanAbs.f3} | ${e.rmse.f3} |"
}
// Keep the computed image as an embed; the caption and measured table are Markdown.
display("compare", "<img style='width:${w * 4}px' src='data:image/png;base64,$png'>")
markdown("errors", """
Top: reference · single scatter · fitted ambient · network. Bottom: absolute error ×4.

| Model | Relative MAE | RMSE |
| --- | --- | --- |
$errors""".trimIndent())
'''
        if code != before:
            if row['kind'] == 'yaml':
                code = '|\n' + ''.join(' '*row['indent'] + l + '\n' if l else '\n' for l in code.rstrip('\n').splitlines())
            else:
                code = code.rstrip() + '\n'
            edits.append((row['start'], row['end'], code))
            records.append(dict(path=path, body=i, collectionFactoriesRemoved=count, markdownCards=cards[0], neuralMeasuredTable='writeCloudComparison(file' in before))
    for a, b, code in sorted(edits, reverse=True):
        text = text[:a] + code + text[b:]
    if edits:
        file.write_text(text)
(C.HERE / 'revision-verification.json').write_text(json.dumps(dict(changes=records), indent=2)+'\n')
print(len(records), 'bodies revised')
