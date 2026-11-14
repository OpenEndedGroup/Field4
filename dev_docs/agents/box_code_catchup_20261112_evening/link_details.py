"""Direct reference links for the substantial helpers used by this delta."""
import json
import re
import run as C

R = C.load('reference_details', C.PRIOR / 'reference_links.py')
index = R.index()
changes = {}

def target(symbol):
    assert len(index[symbol]) == 1, (symbol, sorted(index[symbol]))
    return next(iter(index[symbol]))

def link(page, label, symbol, start=0):
    path = C.ROOT / page
    text = path.read_text()
    url = target(symbol)
    if url in text[start:]:
        return
    parts = re.split(r'(^`{3,}[^\n]*\n.*?^`{3,}[ \t]*$)', text[start:], flags=re.M | re.S)
    for i in range(0, len(parts), 2):
        pattern = r'(?<![\[`])`('+re.escape(label)+r'(?:\([^`]*?\))?)`(?!\])'
        parts[i], n = re.subn(pattern, lambda m: f'[`{m[1]}`]({url})', parts[i], count=1)
        if n:
            path.write_text(text[:start]+''.join(parts))
            changes.setdefault(page, []).append(dict(symbol=symbol, target=url, placement='existing prose'))
            return
    raise AssertionError((page, label))

def insert(page, needle, replacement, symbols):
    path = C.ROOT / page
    text = path.read_text()
    assert text.count(needle) == 1, (page, needle)
    for symbol in symbols:
        replacement = replacement.replace('{'+symbol+'}', target(symbol))
        changes.setdefault(page, []).append(dict(symbol=symbol, target=target(symbol), placement='nearby explanation'))
    path.write_text(text.replace(needle, replacement))

p='public/docs/paint_canvas.md'
for method in ['fold','press','rub']:
    link(p,'canvas.'+method,'PaintCanvas.'+method)
insert(p,'The box below lays three thick strokes on the left half, folds the canvas down the middle and opens',
       'The [`PaintCanvas`]({PaintCanvas}) example below lays three thick strokes on the left half, folds the canvas down the middle and opens', ['PaintCanvas'])
p='public/docs/path_tracing.md'
link(p,'afield.graphics.volume.CloudScene','CloudScene')
link(p,'NeuralCloud.ambient','NeuralCloud.ambient')
link(p,'CloudScene.render','CloudScene.render')
insert(p,'replace that term with a small network: 10 inputs, two hidden layers of 16, and 3 outputs for the',
       'replace that term with a small [`NeuralCloud`]({NeuralCloud}) network: 10 inputs, two hidden layers of 16, and 3 outputs for the', ['NeuralCloud'])
insert(p,'network:\n\n```diffstatic_runner {data-lang=kotlin screenshot="assets/path_tracing/neural_clouds_train.png"',
       'network with [`NeuralCloud.samples`]({NeuralCloud.samples}) and [`NeuralCloud.train`]({NeuralCloud.train}):\n\n```diffstatic_runner {data-lang=kotlin screenshot="assets/path_tracing/neural_clouds_train.png"', ['NeuralCloud.samples','NeuralCloud.train'])
insert(p,"and the row below shows each one's error against the reference, multiplied by four:",
       "and the row below shows each one's error against the reference, multiplied by four.\n[`writeCloudComparison`]({writeCloudComparison}) arranges those panels;\n[`cloudError`]({cloudError}) measures the differences:", ['writeCloudComparison','cloudError'])
link(p,'cloudHere','cloudHere')
receipt=dict(changes=[dict(path=p,links=links) for p,links in changes.items()], policy='Direct class and method links in nearby prose; actual generated anchors, including companion factories.')
(C.HERE/'reference-verification.json').write_text(json.dumps(receipt,indent=2)+'\n')
R.verify()
