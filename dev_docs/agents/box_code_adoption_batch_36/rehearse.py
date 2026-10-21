"""Execute real disk loaders and repair booleans; inspect native cleanup without reading freed memory."""
import asyncio,importlib.util,json,re,yaml
from pathlib import Path
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig36',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9703;m.OUT=R/'build/box-code-revision/adoption-36/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
ROWS={r['path']:r for r in json.loads((H/'changes.json').read_text())}
def bodies(path,label='after'):
 text=ROWS[path]['before'] if label=='before' else (R/path).read_text()
 return {o['create']['box']:o['create']['properties']['code'] for o in yaml.safe_load(text)['operations'] if 'create' in o}
def imports_body(code):return '\n'.join(x for x in code.splitlines() if x.startswith('import ')), '\n'.join(x for x in code.splitlines() if not x.startswith('import '))
def instrument_disk(code):
 imports,body=imports_body(code)
 prefix=imports+'\nval allocated=mutableListOf<java.nio.Buffer>()\nval freed=mutableListOf<java.nio.Buffer>()\nval directories=mutableListOf<java.io.File>()\n'
 body=body.replace('.toFile()', '.toFile().also { directories += it }')
 body=body.replace('PlySupport.loadPly(file.path)','PlySupport.loadPly(file.path).also { allocated += it.buffers.vertexData; it.buffers.indexData?.let { allocated += it } }')
 body=body.replace('memFree(model.buffers.vertexData)','memFree(model.buffers.vertexData); freed += model.buffers.vertexData').replace('memFree(it)','memFree(it); freed += it')
 return prefix,body
async def disk():
 path='dev_docs/agents/transcripts/geometry_from_disk.yaml';original=bodies(path,'before')['disk_ply'];after=bodies(path)
 geometry='check(mesh.vertexCount==4 && mesh.triangleCount==2 && mesh.hasColors && !mesh.hasNormals);proof(mesh.positions.toList().toString()+mesh.indices.toList()+mesh.colors?.toList())'
 answers={}
 for label,code in [('before',original),('after',after['disk_ply'])]:
  prefix,body=instrument_disk(code)
  body=body.replace('val mesh = model.toTriMesh()','val mesh = model.toTriMesh()\n'+geometry)
  if label=='before':
   # The original leaks; the test owns the returned buffers to avoid repeating that leak.
   body='try {\n'+body+'\n} finally { allocated.forEach { org.lwjgl.system.MemoryUtil.memFree(it); freed += it }; directories.forEach { it.deleteRecursively() } }'
  result=await m.macro('ply-'+label,[{'create':{'box':'subject','name':'ply-'+label,'frame':{'x':0.0,'y':0.0,'w':1200.0,'h':100.0},'properties':{'code':prefix+m.PROOF+body+'\ncheck(allocated.size==2 && allocated.zip(freed).all { (a,b)->a===b } && freed.size==2);check(directories.all { !it.exists() })'}}},{'execute':{'box':'subject','property':'code'}}],True)
  answers[label]=m.proof_of(result)
  if label=='after':await m.batch('ply-after-capture',['wait-output subject --channel main --timeout 60','screenshot-box subject '+str(m.OUT/'ply-after.png')])
 assert answers['before']==answers['after'];(m.OUT/'ply-comparison.json').write_text(json.dumps(answers,indent=2))
 # All three real loaders run twice; the second PLY pass also checks explicit frees.
 for repetition in range(2):
  for name,code in after.items():
   prefix,body=instrument_disk(code)
   await m.program(name+'-'+str(repetition),prefix+body,'check(directories.all { !it.exists() });check(allocated.size==freed.size && allocated.zip(freed).all { (a,b)->a===b });proof("actual loader and cleanup complete")',capture=repetition==0 and name!='disk_ply')
 prefix,body=instrument_disk(after['disk_ply'])
 for name,failed in [('after-allocation',body.replace('val b = model.buffers','throw IllegalStateException("controlled diagnostic failure")\nval b = model.buffers')),('malformed',body.replace('format ascii 1.0','format unsupported 1.0'))]:
  await m.program('ply-'+name,prefix+'val failure=runCatching {\n'+failed+'\n}.exceptionOrNull()', 'check(failure != null);check(directories.size==1 && directories.none { it.exists() });check(allocated.size==freed.size && allocated.zip(freed).all { (a,b)->a===b });check(allocated.size=='+('2' if name=='after-allocation' else '0')+');proof("real failure leaves no owned buffer or temp directory")')
async def repair():
 src=bodies('dev_docs/agents/transcripts/mesh_repair_demo.yaml')
 await m.program('diagnose',src['mesh-repair-diagnose'],capture=True)
 imports,body=imports_body(src['mesh-repair-fix'])
 body=body.replace('Solid.cube(4.0, 4.0, 4.0, center = true).translated','Solid.cube(4.0, 4.0, 4.0, center = true).also { owned += it }.translated')
 body=body.replace('val lower = solid - knife','val lower = solid - knife\nowned += solid; owned += knife; owned += lower')
 prefix=imports+'\nval owned=mutableListOf<Solid>()\n'
 await m.program('repair-cut',prefix+body,'check(owned.size==4 && owned.all { it.isClosed });proof("all four native handles, including pre-transform cube, closed")',capture=True)
 failed=body.replace('owned += lower','owned += lower\nthrow IllegalStateException("controlled viewer failure")')
 await m.program('repair-cut-failure',prefix+'val failure=runCatching {\n'+failed+'\n}.exceptionOrNull()', 'check(failure?.message=="controlled viewer failure");check(owned.size==4 && owned.all { it.isClosed });proof("all native handles close on failed viewer path")')
async def main():await disk();await repair()
asyncio.run(main())
