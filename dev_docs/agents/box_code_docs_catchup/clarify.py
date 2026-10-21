"""Name intermediate geometry and controls at the places a reader is likely to edit."""
from pathlib import Path
import json
ROOT=Path(__file__).resolve().parents[3]
OUT=ROOT/'build/box-code-revision/docs-catchup'
records=[]

def edit(name,pairs):
    path=ROOT/name;before=path.read_text();after=before
    for old,new in pairs:
        if old not in after:
            assert new in after,(name,old[:100])
            continue
        after=after.replace(old,new)
    if before!=after:
        path.write_text(after);records.append(dict(path=name,before=before,after=after))

for name in ['public/docs/contour_trees.md','dev_docs/agents/transcripts/contour_trees_explorer.yaml']:
    p=ROOT/name;s=p.read_text();line=next((line for line in s.splitlines() if 'val dots = FLine().also' in line),None)
    if line is None:continue
    indent=line[:len(line)-len(line.lstrip())]
    edit(name,[(line, '\n'.join(indent+l for l in ['val dots = fline {','    tree.nodes.forEach { moveTo(it.x, it.y) }','    pointed = true','    pointSize = 5.0','}']))])

edit('public/docs/geometry_chains.md',[
('''val pts = ArrayList<Double>(); val weights = ArrayList<Double>()
ridges.forEach { r ->
    for (i in 0 until r.vertexCount) { val v = r.vertex(i); pts.add(v.x.toDouble()); pts.add(v.y.toDouble()); weights.add(1.0) }
}''','''val ridgePoints = ridges.flatMap { ridge ->
    (0 until ridge.vertexCount).flatMap { index ->
        val point = ridge.vertex(index)
        [point.x.toDouble(), point.y.toDouble()]
    }
}.toDoubleArray()'''),
('''val tree = PrincipalCurves.fit(pts.toDoubleArray(), weights = weights.toDoubleArray(), curves = 4, nodes = 90, allowBranching = true)''','''val tree = PrincipalCurves.fit(ridgePoints, curves = 4, nodes = 90, allowBranching = true)
val junctions = tree.network.nodesFLine().apply {
    pointed = true
    pointSize = 6.0
    color = Vec4(1f, 1f, 1f, 1f)
}'''),
('tree.network.nodesFLine().also { it.pointed = true; it.pointSize = 6.0; it.color = Vec4(1f, 1f, 1f, 1f) }','junctions'),
('// 2. Points along every surviving ridge, one per vertex. Persistence already chose the ridges, so\n//    every point counts the same.','// 2. One point per surviving ridge vertex. Persistence already chose the ridges; fit them with\n//    the default equal weights.')])

edit('public/docs/topology_tracking.md',[
('val dark = Img(print.width, print.height, 3, FloatArray(print.width * print.height * 3))','''val tracks = lasting.map { track ->
    track.toFLine().apply {
        color = FeatureTracker.colourOf(track.id)
        thickness = 1.5
    }
}
val dark = Img(print.width, print.height, 3, FloatArray(print.width * print.height * 3))'''),
('circles + lasting.map { t -> t.toFLine().also { it.color = FeatureTracker.colourOf(t.id); it.thickness = 1.5 } }','circles + tracks')])

edit('public/docs/optimal_transport.md',[
('println("mean squared travel, exact: " + meanTravel(exact).f0)','println("mean squared travel, exact: ${meanTravel(exact).f0}")'),
('println("mean squared travel, BSP-OT: " + meanTravel(approx).f0)','println("mean squared travel, BSP-OT: ${meanTravel(approx).f0}")'),
('''    "shift " + t + ": divergence " + sinkhornDivergence(cloud, shifted, epsilon = 25.0).f2 + ", t² = " + t * t''','''    val divergence = sinkhornDivergence(cloud, shifted, epsilon = 25.0)
    "shift $t: divergence ${divergence.f2}, t² = ${t * t}"''')])

p=ROOT/'dev_docs/agents/box_code_docs_catchup/revise.py';s=p.read_text();s=s.replace("start=after.index('import afield.simulation.creatures.*',after.index('name=')) if False else after.rfind",'start=after.rfind');p.write_text(s)
base=json.loads((OUT/'source-changes.json').read_text())
records=[dict(path=r['path'],before=r['after'],after=(ROOT/r['path']).read_text()) for r in base if (ROOT/r['path']).read_text()!=r['after']]
(OUT/'clarity-changes.json').write_text(json.dumps(records,indent=2)+'\n')
print(len(records),'paths with explicit clarity edits')
