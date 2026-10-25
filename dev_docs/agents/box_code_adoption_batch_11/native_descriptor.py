"""Exercise the actual descriptor-SSBO example at aligned and partial workgroups."""
from pathlib import Path
import asyncio,importlib.util,json,sys
HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('r',HERE/'rehearse.py');r=importlib.util.module_from_spec(spec);spec.loader.exec_module(r);m=r.m;ROOT=r.ROOT
spec=importlib.util.spec_from_file_location('all_states',HERE.parent/'box_code_adoption_batch_7/format_docs.py');fmt=importlib.util.module_from_spec(spec);spec.loader.exec_module(fmt);sections=fmt.sections
async def main():
 text=(ROOT/'public/docs/afield_four_graphics.md').read_text()
 code=next(text[a:b] for a,b in sections(text) if 'var numVec4 = 16' in text[a:b])
 shader=next(text[a:b] for a,b in sections(text,'glsl') if 'writeonly buffer OutputBuffer' in text[a:b])
 renderer=(ROOT/'public/docs/assets/canonical_graphics/611072da-1af6-4d2d-85bf-07a9ba191aca_code_.kt').read_text().replace('Options.enableHDR = true','Options.enableHDR = false').replace('Window("Field")','Window("Private descriptor bounds verification")')
 await m.macro('descriptor_renderer',[{'create':{'box':'renderer','name':'Private descriptor renderer','frame':{'x':0.,'y':0.,'w':350.,'h':100.},'properties':{'code':renderer}}},{'launch':{'box':'renderer'}},{'await':{}}],True)
 rows=[]
 for n in [1,16,17,65]:
  fixture=code.replace('var numVec4 = 16',f'var numVec4 = {n}').replace('renderer += "runCompute" to module','')
  fixture=fixture[:fixture.index('module.InputBuffer.mapAndWrite')]+'''
// Exercise the published Structure mapping API, then initialize every physical image.
module.InputBuffer.mapAndWrite { put(0,1234f) }
module.InputBuffer.mapAndRead { check(get(0)==1234f) }
// Readback is independent of which swapchain image is current.
module.InputBuffer.proxying!!.buffers.forEach { buffer -> buffer.mapAndWrite {
 clear()
 repeat(numVec4*4) { i -> put((i/4*10+i%4).toFloat()) }
 clear()
} }
module.OutputBuffer.proxying!!.buffers.forEach { buffer -> buffer.mapAndWrite {
 clear();repeat(numVec4*4) { put(-777f) };clear()
} }
renderer += "runCompute" to module
'''
  await m.macro(f'descriptor_{n}_setup',[{'create':{'box':'ssbo','name':f'SSBO count {n}','frame':{'x':400.,'y':0.,'w':350.,'h':100.},'properties':{'code':fixture,'compute':shader}}},{'connect':{'parent':'renderer','child':'ssbo'}},{'launch':{'box':'ssbo'}},{'await':{}}])
  await m.batch(f'descriptor_{n}_frames',['wait-native-renderer ssbo --frames 24 --timeout 60'])
  await r.fragment(f'descriptor_{n}_values','ssbo','''
module.OutputBuffer.mapAndRead {
 for(i in 0 until numVec4) {
  val expected=floatArrayOf(11f*i+3,1f-11*i,13f*i+2,14f*i+3)
  for(j in 0..3)check(get(i*4+j)==expected[j]) { "SSBO record $i channel $j: ${get(i*4+j)} != ${expected[j]}" }
 }
}
println("descriptor GPU proof: all "+numVec4+" records match, including the final partial group")
''')
  rows.append({'count':n,'localSize':16,'workgroups':(n+15)//16,'allOutputScalarsChecked':n*4});(m.OUT/'descriptor-boundaries.json').write_text(json.dumps(rows,indent=2)+'\n')
  await r.fragment(f'descriptor_{n}_cleanup','ssbo','renderer.computeList.modules.remove("runCompute",module)\nmodule.current.cleanup()\nmodule.InputBuffer.proxying!!.buffers.forEach { it.cleanup() }\nmodule.OutputBuffer.proxying!!.buffers.forEach { it.cleanup() }')
  await m.batch(f'descriptor_{n}_stop',['stop ssbo','delete-box ssbo'])
 await m.batch('descriptor_renderer_stop',['stop renderer','lifecycle-census renderer --expect-stopped'])
 print('Descriptor SSBO partial-group GPU checks passed',flush=True)
if __name__=='__main__':asyncio.run(main())
