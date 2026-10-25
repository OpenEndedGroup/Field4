"""Compare both actual grid helpers at their published bounds, and the ribbon's first frame."""
import asyncio,importlib.util,json
from pathlib import Path
HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('r',HERE/'rehearse.py');r=importlib.util.module_from_spec(spec);spec.loader.exec_module(r);m=r.m
async def main():
 before=m.source('public/docs/spatial_geometry_examples.md','original');after=m.source('public/docs/spatial_geometry_examples.md','revised')
 chunks=[]
 for label,text in [('old',before),('new',after)]:
  offset=0
  for i in range(2):
   s=text.index('fun gridLines(',offset);f=r.function(text[s:],'gridLines');offset=s+len(f)
   chunks.append(f.replace('fun gridLines(',f'fun {label}Grid{i}('))
 probe='''
var worst = 0.0
for ((a,b) in listOf(oldGrid0(-1.05,1.03,-1.02,1.07,8,8) to newGrid0(-1.05,1.03,-1.02,1.07,8,8), oldGrid1(-2.0,2.0,-2.0,2.0,16) to newGrid1(-2.0,2.0,-2.0,2.0,16))) {
 check(a.nodes.size==b.nodes.size)
 for ((x,y) in a.nodes.zip(b.nodes)) {
  check(x.javaClass==y.javaClass)
  worst=maxOf(worst,x.toOrNull!!.distance(y.toOrNull!!).toDouble())
 }
 check(a.color==b.color && a.thickness==b.thickness)
}
check(worst<0.000001) { "grid endpoint error $worst" }
println("grid endpoint max error $worst")
proof(worst)
'''
 await m.program('grid_comparison','\n'.join(chunks),probe)
 values={}
 for label in ['original','revised']:
  code=m.source('templates/first-steps/first_steps_drawing_code_.kt',label)
  head,body=code.split('loop {',1);body=body[:body.index('    lines(ribbon,')]
  values[label]=await m.program(label+'_ribbon',head+body,'check(ribbon.size==24); check(ribbon.all { it.nodes.size==181 }); proof(geometry(ribbon))')
 assert values['original']==values['revised'],values
 await m.program('refresh_template_metadata', 'afield.document.persistence.SavingContext().refreshInlineFilesFromDisk("templates/first-steps")')
 (m.OUT/'ribbon-equivalence.json').write_text(json.dumps(values,indent=2))
if __name__=='__main__':asyncio.run(main())
