"""Execute the actual revised wrap helper on the GPU and verify fractional channel values."""
from pathlib import Path
import importlib.util,asyncio,json,re
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('native25',H.parent/'box_code_adoption_batch_19/native_checks.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.OUT=R/'build/box-code-revision/adoption-25/native';m.OUT.mkdir(parents=True,exist_ok=True)
async def fragment(label,uid,code):return await m.macro(label,[{'execute':{'box':uid,'property':'code','type':'fragment','code':code}}])
async def main():
 renderer=(R/'public/docs/assets/canonical_graphics/611072da-1af6-4d2d-85bf-07a9ba191aca_code_.kt').read_text().replace('Options.enableHDR = true','Options.enableHDR = false').replace('Window("Field")','Window("Private wrap verification")')
 await m.macro('renderer',[{'create':{'box':'renderer','properties':{'code':renderer}}},{'launch':{'box':'renderer'}},{'await':{}}],True)
 source=(R/'public/docs/assets/canonical_graphics/65bd0587-dbfa-451a-8f6a-e003ff3d1abf_vertex_.glsl').read_text();wrap=re.search(r'float wrap\([^}]+}',source)[0]
 shader='''#version 460
#extension GL_EXT_buffer_reference : require
layout(local_size_x=64) in;
layout(std430,buffer_reference) buffer Output { vec4 a[]; };
layout(push_constant) uniform Params { Output rampData; } pc;
'''+wrap+'''
void main() {
 uint i=gl_GlobalInvocationID.x;
 if(i>=33u)return;
 pc.rampData.a[i]=vec4(wrap(int(i),3),wrap(int(i),5),wrap(int(i),11),1.0);
}
'''
 code='''val rampData=defaultBuffer { size=33.vec4s }.buffer
val module=computeModuleHere {}
val mesh=ComputeMesh()
module+="ramp" to mesh
mesh.x=1;mesh.y=1;mesh.z=1
mesh.rampData={rampData}
renderer+="wrap-test" to module
'''
 try:
  await m.macro('ramp',[{'create':{'box':'ramp','properties':{'code':code,'compute':shader}}},{'connect':{'parent':'renderer','child':'ramp'}},{'launch':{'box':'ramp'}},{'await':{}}])
  await m.batch('ramp_frames',['wait-native-renderer ramp --frames 30 --timeout 60'])
  await fragment('ramp_values','ramp','''rampData.read {
 for(i in 0 until 33) {
  for((j,b) in intArrayOf(3,5,11).withIndex()) {
   val expected=(i%b).toFloat()/(b-1)
   check(kotlin.math.abs(get(i*4+j)-expected)<0.000001f) { "index $i channel $j: ${get(i*4+j)}, expected $expected" }
  }
  check(get(i*4+3)==1f)
 }
 check(get(1*4+1)==0.25f && get(2*4+1)==0.5f && get(3*4+1)==0.75f)
}
println("all 99 GPU ramp channels, including quarter steps, verified")''')
  await fragment('ramp_cleanup','ramp','renderer.computeList.modules.remove("wrap-test",module);module.current.cleanup();rampData.buffers.forEach { it.cleanup() }')
  await m.batch('ramp_stop',['stop ramp','lifecycle-census ramp --expect-stopped'])
 finally:await m.batch('renderer_stop',['stop renderer','lifecycle-census renderer --expect-stopped'])
 (m.OUT/'verification.json').write_text(json.dumps({'actualShaderHelperExecuted':True,'vertices':33,'colorChannels':99,'bases':[3,5,11],'quarterStepsVerified':True,'tolerance':1e-6},indent=2)+'\n')
if __name__=='__main__':asyncio.run(main())
