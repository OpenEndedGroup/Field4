"""Add selected class/member reference pairs using actual generated anchors."""
import json
import run as R

L = R.load('links', R.PRIOR/'reference_links.py')
symbols = L.index()
groups = {
 'fm1.md': [
  ('Simulator and offline rendering', ['FM1Sim','FM1Sim.create','FM1Sim.render','FM1Sim.preset']),
  ('Pattern and control inputs', ['FM1Sim.play','FM1Sim.modulate','FM1Sim.sample','FM1Sim.voice']),
  ('Firmware editing', ['FM1Firmware','FM1Firmware.checkout','FM1Firmware.patch','FM1Firmware.build']),
 ],
 'pattern_language.md': [('Weighted hands and editable loops', ['PatMuse','PatMuse.pattern','PatMuse.lock','PatMuseChannel','PatMuseChannel.lock','PatLoop','PatLoop.set','PatLoop.pattern'])],
 'spectral_examples.md': [('Filter implementations', ['Korg35','TptOnePole','TiltEQ']), ('Prediction and resynthesis', ['Lpc','Lpc.analyze','LpcVocoder'])],
 'alife_examples.md': [('Growing and advancing bonded bodies', ['BondedWorld','BondedWorld.grow','BondedWorld.run','BondedWorld.centroid'])],
 'topology_tracking.md': [('Tracking critical points over time', ['CerfDiagram','CerfDiagram.add','CerfDiagram.toFLines','CerfDiagram.fieldMarkers'])],
 'path_tracing.md': [('Installing and requesting compute denoising', ['OidnComputeDenoiser','OidnComputeDenoiser.install','OidnComputeDenoiser.request'])],
}
records = []
for name, entries in groups.items():
 p = R.S.ROOT/'public/docs'/name
 text = p.read_text()
 paragraphs = []
 for label, names in entries:
  links = []
  for symbol in names:
   choices = symbols[symbol]
   assert len(choices) == 1, (symbol, choices)
   target = next(iter(choices))
   if target in text: continue
   links.append(f'[`{symbol}`]({target})')
   records.append(dict(path=str(p.relative_to(R.S.ROOT)),symbol=symbol,target=target))
  if links: paragraphs.append('- '+label+': '+', '.join(links)+'.')
 if paragraphs:
  heading = '\n\n## API reference\n\n' if '\n## API reference\n' not in text else '\n\n'
  p.write_text(text.rstrip()+heading+'\n'.join(paragraphs)+'\n')
(R.S.HERE/'reference-verification.json').write_text(json.dumps(dict(links=records,policy='Explicit curated class/member pairs for retained algorithms, simulators and resource APIs; every anchor resolved from generated reference.'),indent=2)+'\n')
print(len(records),'reference links added')
