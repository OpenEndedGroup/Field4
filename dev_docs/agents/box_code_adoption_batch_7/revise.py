"""Correct current numerical/geometric explanations in copyable documentation code."""
from pathlib import Path
import json
ROOT=Path(__file__).resolve().parents[3];changes=[]
def edit(page,old,new,finding):
 p=ROOT/'public/docs'/page;s=p.read_text();assert old in s,(page,old)
 p.write_text(s.replace(old,new));changes.append({'path':str(p.relative_to(ROOT)),'finding':finding,'old':old,'new':new})
edit('dataframe.md','//%-\nval p = people.cast<Peach>()','// Run the first fragment to publish Peach into the preamble before compiling the cast.\n//%-\nval p = people.cast<Peach>()','0138746883aa-01')
edit('dynamic_graph_systems_examples.md','// Six DynamicGraphSystems, one cloud, one clock, one reinforcement budget. The only thing that\n// differs between panels is what the system thinks an edge means.','// Six systems share a cloud, step count and edge-weight ceiling. Reinforcement is\n// system-specific: routing uses 1.0, the other update laws use 4.0, and alpha scales\n// reinforcement per unit area. These are not equal reinforcement budgets.','42e191350b0b-01')
edit('functions_examples.md','// Compare the raw transfer curves by sweeping a ramp through each shaper.','// Compare the shapes produced by applying each shaper to the same sine samples.','17291ebbeb49-03')
edit('gaussian_process_optimization.md','// A rosette with three dials. You do not say what you want; you only say which of two you','// A rosette with four dials: petals, depth, curl and hue. Say which of two you','bdd9a7752234-02')
edit('keyframer_examples.md','// Two endpoints. The camera swings 90 degrees around the fan and lifts, while the tint goes cool to','// Two endpoints. The camera swings about 60 degrees around the fan and lifts, while the tint goes cool to','2f08a46f3b63-02')
edit('kotlin_overview.md','val raw = """ // a multi-line string','// A multi-line string, with shared indentation removed by trimIndent().\nval raw = """','05cfe9d79f8c-01')
edit('manifold.md','// The same field marched again: identical inputs give a bit-identical mesh.','// March the same field again and compare triangle count and volume below.\n// These aggregate checks do not establish identical vertex/index arrays.','3321d8acc458-01')
edit('mesh_examples.md','// marchingCubes samples the field once per grid corner and shares one vertex per crossed grid\n// edge, so the surface it emits is closed rather than a triangle soup.','// marchingCubes samples each grid corner and shares one vertex per crossed grid edge.\n// The result is indexed; closure also requires the surface to stay inside the sampling bounds.','a10737de4739-01')
edit('mesh_examples.md','// negative. The marcher does not mind that the gyroid is not a distance function at all — it only\n// ever looks at signs.','// negative. A scalar field need not be a distance function: signs classify corners,\n// while the sampled values locate interpolated edge crossings.','a10737de4739-02')
edit('movement_examples.md','yTitle = "radians travelled from 3"','yTitle = "angle in radians (short path unwrapped)"','3ce0ef5aa3ce-01')
edit('paint_sweeps.md','// a canvas 50 cm wide and 1.25 m tall, and a brush 10 cm wide','// A canvas 50 cm wide and 1.25 m tall; the default brush is 10 cm wide.','170fca19e6cf-03')
edit('polytope_examples.md','// face straddling an original edge or corner has two or three, and gets a neutral grey.','// face straddling an original edge or corner has two or three, whose colors are blended.','12a95c86b5aa-01')
edit('pressure_membranes.md','            val ids = if ((points[b]-points[a]).cross(points[c]-points[a]).dot(points[a]) > 0)','            // Choose outward winding using the face normal and its position from the origin.\n            val ids = if ((points[b]-points[a]).cross(points[c]-points[a]).dot(points[a]) > 0)','22b6fafe7412-01')
edit('pressure_membranes.md','            PanelTriangle(ids[0], ids[1], ids[2], "skin",','            // Project the rest triangle into its local, unstretched 2D material frame.\n            PanelTriangle(ids[0], ids[1], ids[2], "skin",','22b6fafe7412-01')
edit('rectsl_cheatsheet.md','// antialiased at any zoom','// fixed 0.01 scene-unit feather','021f373e6bf8-01')
edit('spatial_geometry_examples.md',':::fold slice each 3D cell with the plane z = 5, and draw',':::fold slice each 3D cell at the selected z levels, and draw','91a9cbd293cd-02')
edit('statistics_examples.md','"sine, 1.5 cycles" to DoubleArray(n) { sin(3.0 * PI * x[it]) }','"sine, 3 cycles" to DoubleArray(n) { sin(3.0 * PI * x[it]) }','e70830985ea4-02')
(ROOT/'dev_docs/agents/box_code_adoption_batch_7/comment_changes.json').write_text(json.dumps(changes,indent=2)+'\n')
print(len(changes),'edits across',len({x['finding'] for x in changes}),'findings')
