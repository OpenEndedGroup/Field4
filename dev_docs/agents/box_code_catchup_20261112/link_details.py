"""Link the substantial operations behind the newly added examples, in nearby prose."""
import json
import re
import run as C

R = C.load('reference_details', C.PRIOR / 'reference_links.py')
index = R.index()
plan = {
    'public/docs/satellites.md': {
        'Tle': 'Tle', 'propagate': 'Sgp4.propagate',
        'SatelliteCatalogue': 'SatelliteCatalogue', 'Observer': 'Observer',
        'passes': 'Observer.passes', 'visiblePasses': 'Observer.visiblePasses', 'SkyClock': 'SkyClock',
    },
    'public/docs/alife_examples.md': {'flowLeniaWorld': 'flowLeniaWorld'},
}
receipt_path = C.HERE / 'reference-verification.json'
receipt = json.loads(receipt_path.read_text())
by_page = {r['path']: r for r in receipt['changes']}

def record(page, symbol, target):
    by_page.setdefault(page, dict(path=page, links=[]))['links'].append(dict(symbol=symbol, target=target, placement='nearby prose'))

for page, labels in plan.items():
    path = C.ROOT / page
    parts = re.split(r'(^`{3,}[^\n]*\n.*?^`{3,}[ \t]*$)', path.read_text(), flags=re.M | re.S)
    for label, symbol in labels.items():
        assert len(index[symbol]) == 1, symbol
        target = next(iter(index[symbol]))
        if target in ''.join(parts[::2]):
            continue
        for i in range(0, len(parts), 2):
            parts[i], n = re.subn(r'(?<![\[`])`'+re.escape(label)+r'`(?!\])', f'[`{label}`]({target})', parts[i], count=1)
            if n:
                record(page, symbol, target)
                break
    path.write_text(''.join(parts))

paragraphs = {
    'public/docs/alife_examples.md': (
        'own rule with it, so different species can meet and mix in one world.',
        [('FlowLeniaWorld', 'FlowLeniaWorld'), ('step()', 'FlowLeniaWorld.step'), ('animateEvolution', 'animateEvolution')],
        '\n\nEach [`{0}`]({1}) advances with [`{2}`]({3}); [`{4}`]({5}) runs the steps and records the panels shown below.'),
    'public/docs/fline_examples.md': (
        'compare it with another one.',
        [('sweptCircleStroke', 'sweptCircleStroke'), ('FLine.bySweptCircleEnveloping', 'bySweptCircleEnveloping'), ('thinPenSamples', 'thinPenSamples'), ('penCorners', 'penCorners')],
        '\n\nSee [`{0}`]({1}) and [`{2}`]({3}) for the outline controls,\nand [`{4}`]({5}) and [`{6}`]({7}) for preparing tablet samples.'),
    'public/docs/keyframer_examples.md': (
        'For a travelling transition, call this from a control loop with elapsed seconds:',
        [('OrderedKeyframeGroup.transition', 'OrderedKeyframeGroup.transition')],
        'For a travelling transition, call [`{0}`]({1}) from a control loop with elapsed seconds:'),
}
for page, (needle, pairs, template) in paragraphs.items():
    path = C.ROOT / page
    text = path.read_text()
    assert text.count(needle) == 1, page
    args = []
    for label, symbol in pairs:
        assert len(index[symbol]) == 1, symbol
        target = next(iter(index[symbol]))
        args += [label, target]
        record(page, symbol, target)
    replacement = template.format(*args)
    text = text.replace(needle, replacement if page.endswith('keyframer_examples.md') else needle + replacement)
    path.write_text(text)
receipt['changes'] = list(by_page.values())
receipt_path.write_text(json.dumps(receipt, indent=2)+'\n')
R.verify()
