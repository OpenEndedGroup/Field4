"""Add selected class/member reference pairs using actual generated anchors."""
import json
import re
import run as R

L = R.load('links', R.PRIOR/'reference_links.py')
symbols = L.index()
# Nested TimelineGraph methods currently have no separate generated member row.
source_path = R.S.ROOT/'src/jvmMain/kotlin/afield/math/functions/Functions.kt'
source_text = source_path.read_text()
class_start = source_text.index('class TimelineGraph(')
method_start = source_text.index('fun asFn(', class_start)
method_line = source_text[:method_start].count('\n') + 1
symbols['TimelineGraph.asFn'] = {f'reference/source/jvmMain/afield/math/functions/Functions/#L{method_line}'}
# Top-level extension functions have their own anchors, outside the class table.
symbols['PathTraceScene.rhino'] = {'reference/graphics.md#rhinopathtrace-kt--rhino'}
symbols['Rhino3dm.save'] = {'reference/graphics.md#rhinowriter-kt--save'}
groups = {
 'afield_four_graphics.md': [('Rasterization implementations', ['TriangleSplat', 'TriangleSplat.render', 'Gezira', 'Gezira.rasterize', 'Gezira.beziers', 'GeziraStroke', 'GeziraStroke.strokeBezierPath', 'GeziraComposite', 'GeziraComposite.texture', 'GeziraFLine', 'GeziraFLine.render', 'GeziraSvg', 'GeziraSvg.parse', 'GeziraSvg.render', 'GeziraGpu', 'GeziraGpu.dispatch', 'GeziraGpu.stage', 'GpuProbe', 'GeziraGpuScene', 'GeziraGpuScene.of', 'GeziraGpuScene.paths'])],
 'chain_polytope_render.md': [('Triangulated selection', ['PickParts', 'PickParts.fromTriangulated', 'ElementId'])],
 'audio_curves.md': [('Analysis and mapped files', ['audioCurves','AudioCurveSettings','AudioCurveFile','AudioCurveFile.close'])],
 'drawing_on.md': [('Reading and querying the recording', ['DrawingOnCapture','readDrawingOn','DrawingOnCapture.drawingSince','DrawingOnCapture.networkAt'])],
 'colors_examples.md': [('Contrast searches and returned swatches', ['ContrastScale','contrastScale','ContrastSwatch','contrastPalette','colorHelix'])],
 'functions_examples.md': [('Building and sampling timeline graphs', ['TimelineGraph','timelineGraph','TimelineGraph.asFn'])],
 'braitenberg_vehicles.md': [('Learning and drawing the weight cube', ['WeightCubeLearner', 'WeightCubeLearner.train', 'WeightCubeLearner.measure', 'CubeView', 'CubeView.path'])],
 'chain_rhino_render.md': [('Loading and rendering the model', ['Rhino3dm', 'Rhino3dm.load', 'Rhino3dm.writeNurbsSample', 'PathTraceScene', 'PathTraceScene.rhino', 'PathTraceMaterial', 'PathTraceMaterial.thinGlass', 'PathTraceMaterial.plastic'])],
 'datalog.md': [('Facts and explanations', ['Datalog', 'Datalog.add', 'Datalog.replaceFacts', 'Datalog.why'])],
 'davinci_resolve.md': [('Following the playhead', ['Resolve', 'Resolve.on', 'ResolvePlayheadLink', 'ResolvePlayheadLink.offer', 'ResolvePlayheadLink.close'])],
 'direct_lighting.md': [('The lighting study', ['DirectLightingDemo', 'DirectLightingDemo.animate', 'DirectLightingDemo.close'])],
 'discrete_energy.md': [('Stating and solving the energy', ['DiscreteEnergy', 'DiscreteEnergy.allDifferent', 'DiscreteEnergy.pairCost', 'EnergyState', 'MeanFieldDescent', 'MeanFieldDescent.settle', 'GreedyMoves', 'GreedyMoves.settle', 'GibbsAnnealing', 'GibbsAnnealing.step', 'PseudolikelihoodLearner'])],
 'fabricate_renderer.md': [('Building a renderer', ['StandardRenderer', 'StandardRenderer.build'])],
 'geometry_from_disk.md': [('Binning and drawing splats', ['SplatTiles', 'SplatTiles.clusteredCloud', 'SplatTiles.bin', 'SplatTiles.render', 'SplatTiles.compare'])],
 'gpu_shader_learning.md': [('The live learning view', ['GpuShaderLearningView', 'RenderToTexture'])],
 'learned_pen.md': [('Loading and prompting the pen', ['HersheyFont', 'HersheyFont.load', 'PenDrawings', 'PenDrawings.michaux', 'PenGpt', 'PenGpt.named', 'PenGptPrompt'])],
 'manim.md': [('Receiving the sampled paths', ['NumpyShared', 'NumpyShared.pathFrames'])],
 'paint_canvas.md': [('Addressable stroke records', ['PaintStrokes', 'PaintStrokes.add', 'PaintStrokes.apply', 'PaintStrokes.render', 'PaintStrokes.pin', 'PaintCanvas', 'PaintCanvas.work'])],
 'ai4_painting.md': [('The editable stroke sheet', ['PaintStrokes', 'PaintStrokes.add'])],
 'chain_tiger_paint.md': [('Reading SVG and painting its paths', ['GeziraSvg', 'GeziraSvg.parse', 'GeziraSvg.render', 'PaintCanvas', 'PaintCanvas.work'])],
 'path_tracing.md': [('Denoising an existing image', ['OidnImageSource', 'OidnComputeDenoiser', 'OidnComputeDenoiser.denoiseImage', 'OidnComputeDenoiser.read'])],
 'presentation_studio.md': [('Restoring the live experiment', ['LessonCheckpoint', 'LessonCheckpoint.capture', 'LessonCheckpoint.restore'])],
 'rhino_files.md': [('Reading and writing models', ['Rhino3dm', 'Rhino3dm.load', 'Rhino3dm.save', 'Rhino3dm.writeSample', 'Rhino3dm.writeNurbsSample', 'RhinoBreps', 'RhinoBreps.box', 'RhinoBreps.subdBox'])],
 'sentence_diagrams.md': [('Preparing the reading diagram', ['ReadingDiagramPlayer', 'ReadingDiagramPlayer.prepare', 'DiagramKeyedSpan'])],
 'sound_paths.md': [('Oscillators and block processing', ['SineOsc', 'SynthContext'])],
 'spectral_examples.md': [('Speech, samples and resynthesis', ['Speech','speak','Speech.resampled','Speech.pattern','Speech.sungOn','DirtRender','DirtRender.requirePinnedLibrary']), ('Signal and filter implementations', ['SineOsc', 'Waveshaper', 'Waveshaper.tanh', 'AdaaShaper', 'ZdfLadder', 'ZdfSvf', 'SamplesSignal', 'Smoother', 'Lpc', 'Lpc.analyze'])],
 'spectral_geometry.md': [('Spectral geometry implementations', ['EpicycleGeometry', 'EpicycleGeometry.torus', 'EpicycleGeometry.fit', 'PhaseClock', 'PhaseClock.phase', 'CotangentLaplacian', 'CotangentLaplacian.basis', 'PlaneWaveField', 'PlaneWaveField.fitDirectional'])],
 'stacksl_examples.md': [('Specializing and running the shader', ['GlslPartialEvaluator', 'GlslPartialEvaluator.specialize', 'StackSLCpu', 'StackSLCpu.renderFragment'])],
 'spatial_rules.md': [('Matching and advancing spatial rules', ['SpatialRuleWorld', 'SpatialRuleWorld.source', 'SpatialRuleWorld.rules', 'SpatialRuleWorld.step','inspectOnClick'])],
 'treemaps.md': [('Color and layout policies', ['TreemapColors', 'TreemapColors.byBranch', 'TreemapColors.byCategory', 'TreemapColors.byValue', 'TreemapTiling', 'TreemapTiling.Resquarify'])],
 'interaction_nets_examples.md': [('Compiling and reducing nets', ['LambdaTerms', 'LambdaTerms.compile', 'LambdaTerms.readback', 'Reducer', 'Reducer.stepDetailed', 'NetPrograms', 'NetPrograms.compile', 'NetPrograms.run'])],
}

previous = json.loads((R.S.HERE/'reference-verification.json').read_text())['links']
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
  text = text.rstrip()+heading+'\n'.join(paragraphs)+'\n'
 # Repeated runs add missing anchors to the existing group rather than repeating its label.
 for label, _ in entries:
  matches = list(re.finditer(r'^- '+re.escape(label)+r': .*$', text, re.M))
  if len(matches) < 2: continue
  links = list(dict.fromkeys(link for m in matches for link in re.findall(r'\[`[^`]+`\]\([^)]+\)', m[0])))
  replacement = '- '+label+': '+', '.join(links)+'.'
  for m in reversed(matches[1:]): text = text[:m.start()]+text[m.end()+1:]
  first = matches[0]
  text = text[:first.start()]+replacement+text[first.end():]
 if text != p.read_text(): p.write_text(text.rstrip()+'\n')
records = list({(r['path'],r['symbol'],r['target']):r for r in previous+records}.values())
(R.S.HERE/'reference-verification.json').write_text(json.dumps(dict(links=records,policy='Explicit curated class/member pairs for retained algorithms, simulators and resource APIs; every anchor resolved from generated reference.'),indent=2)+'\n')
print(len(records),'reference links added')
