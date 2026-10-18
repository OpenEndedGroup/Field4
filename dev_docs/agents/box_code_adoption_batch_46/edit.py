"""Source-grounded teaching comments and handoff corrections; no algorithm changes."""
from pathlib import Path
import json
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
rows=[]
def edit(path,fn):
 p=R/path;before=p.read_text();after=fn(before);assert before!=after,path;p.write_text(after);rows.append({'path':path,'before':before,'after':after})
def replace(s,a,b,n=1):
 assert s.count(a)==n,(a,s.count(a));return s.replace(a,b)
edit('public/docs/klecks.md',lambda s:replace(s,'Those values update after Submit.','Each call returns the last submitted image. The local `png` and `layeredPsd` values are snapshots;\nrerun their retrieval lines after another Submit to read the new image.'))
edit('public/docs/csound.md',lambda s:replace(s,'It prints a gain of 0.2420. A second-order Butterworth lowpass passes twice its cutoff at\n1/√(1 + 2⁴) = 0.2425.','This excerpt returns the dry and lowpass sample arrays. The full `csound_demo` starting point\nmeasures their RMS ratio and prints a gain of 0.2420. A second-order Butterworth lowpass\npasses a signal at twice its cutoff with gain 1/√(1 + 2⁴) = 0.2425.'))
edit('public/docs/interactive_guides.md',lambda s:replace(s,'A template value can come from','Run the state declarations (`clickCount`, `userChoice` and `version`) before building the show,\nso its template callbacks can read those values.\n\nA template value can come from'))
edit('public/docs/afield_four_graphics.md',lambda s:replace(s,"// then there's an execution break here",'// Publish the template before moduleHere reflects the shader; rerun the module fragment to recompile.',1) if s.count("// then there's an execution break here")==1 else s.replace("// then there's an execution break here",'// Run the declarations above first; rerun the module fragment to compile its generated shader interface.'))
edit('public/docs/alife_examples.md',lambda s:replace(s,'val picks = evolved.improvements.indices.toList().let { idx ->','// Keep the full measured history; display at most five roughly evenly spaced record holders.\nval picks = evolved.improvements.indices.toList().let { idx ->',2))
def fluid(s):
 s=replace(s,'`println(chain.plan.report())`','`println(fluid.plan.report())`')
 s=replace(s,'A mask pass draws a disc and a bar, then thirteen passes\n  flood it into a signed distance field,','A mask pass draws a disc and a bar, then the jump-flood passes\n  turn it into a signed distance field,')
 s=replace(s,'`maskCentre` and `maskRadius`.','`sdf["centre"]` and `sdf["radius"]`.')
 s=replace(s,'`spacing` on the bands pass,','`sdf["spacing"]` on the bands pass,')
 s=replace(s,'`resolution = 512`. Fifteen passes instead of thirteen — the stride sequence is the halvings of\n      the resolution, so the pass count is `1 + log2(N)` and the planner derives it for you.','`resolution = 512` in `jumpFloodDistance(...)`. Doubling resolution adds one halving-stride\n      flood round. Seed, JFA+1 and distance passes are additional; inspect `sdf.plan.report()`\n      for the complete chain, including the mask, bands and inset passes.')
 return s
edit('dev_docs/agents/transcripts/four_simulation_chain.yaml',fluid)
edit('public/docs/assets/four_in_output_areas/rtt-view_vertex_.glsl',lambda s:replace(s,'    gl_Position =','    // finalColor.xy supplies the animated object-space offset; it is not a colour here.\n    gl_Position ='))
edit('templates/advanced_remote/e3fe297a-36b5-450a-8343-116e105de4ee_vertex_.glsl',lambda s:replace(replace(s,'uniform vec3 foo;','// Bound by the remote JavaScript: componentwise normal displacement in object space.\nuniform vec3 foo;'),'    gl_PointSize = size * 300.0 / abs(gl_Position.z);','    // 300.0 sets the point-size scale; view-space depth makes distant points smaller.\n    gl_PointSize = size * 300.0 / abs(gl_Position.z);'))
(H/'changes.json').write_text(json.dumps(rows,indent=2)+'\n')
print(len(rows),'sources')
