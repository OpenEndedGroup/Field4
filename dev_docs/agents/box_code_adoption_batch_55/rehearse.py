"""Exercise authored native geometry through CPU scene import and failure cleanup."""
from pathlib import Path
import asyncio,importlib.util,json,re,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig55',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9718;m.OUT=R/'build/box-code-revision/adoption-55/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
rows=json.loads((H/'changes.json').read_text())
def body(text):
 return next(o['create']['properties']['code'] for o in yaml.safe_load(text)['operations'] if 'Solid.' in o.get('create',{}).get('properties',{}).get('code',''))
async def main():
 evidence=[]
 for row in rows:
  name=Path(row['path']).stem;proofs={}
  for label in ['before','after','failure']:
   code=body(row['before'] if label=='before' else (R/row['path']).read_text());code=code[:code.index('val environment = PathTraceEnvironment')]
   code=re.sub(r'(?m)^\s*prop<PathTracingRenderer>\(tracer\)\s*\n','\n',code)
   # Only the independent native stage and copied scene are exercised, without a Renderer.
   code=code.replace('.checked("cloth net")','.checked("cloth net").also { owned += it }').replace('.checked("cut shell")','.checked("cut shell").also { owned += it }').replace('whole.decompose()','whole.decompose().also { owned += whole; owned.addAll(it) }')
   if label=='failure':
    if 'life3d' in name:code=code.replace('val volumes = parts.map','throw IllegalStateException("controlled census failure")\nval volumes = parts.map')
    elif 'physics' in name:code=code.replace('welded.toTriMesh(', 'welded.let { throw IllegalStateException("controlled conversion failure");it }.toTriMesh(',1)
    else:code=code.replace('cut.toTriMesh(', 'cut.let { throw IllegalStateException("controlled conversion failure");it }.toTriMesh(',1)
   imports='\n'.join(x for x in code.splitlines() if x.startswith('import '));code='\n'.join(x for x in code.splitlines() if not x.startswith('import '))
   prefix=imports+'\nval owned=mutableListOf<Solid>()\n'
   if label=='failure':
    await m.program(name+'-'+label,prefix+'val failure=runCatching {\n'+code+'\n}.exceptionOrNull()', 'check(failure is IllegalStateException && failure.message!!.startsWith("controlled"));check(owned.isNotEmpty() && owned.all {it.isClosed});proof("native solids closed on injected failure")')
   else:
    # Compare every copied triangle/material, using reflection only in this diagnostic fixture.
    proof='''val trianglesField=scene.javaClass.getDeclaredField("triangles").also {it.isAccessible=true}
val materialField=scene.javaClass.getDeclaredField("materials").also {it.isAccessible=true}
val triangles=trianglesField.get(scene) as List<*>
check(triangles.isNotEmpty())
val digest=java.security.MessageDigest.getInstance("SHA-256")
triangles.forEach { digest.update(it.toString().toByteArray()) }
digest.update(materialField.get(scene).toString().toByteArray())
'''
    if label=='after':proof+='check(owned.isNotEmpty() && owned.all {it.isClosed}) {"native solid escaped copied scene"}\n'
    proof+='owned.forEach { it.close() }\nproof(digest.digest().joinToString("") {"%02x".format(it.toInt() and 255)})'
    proofs[label]=await m.program(name+'-'+label,prefix+code,proof)
  assert proofs['before']==proofs['after'],(name,proofs)
  evidence.append({'path':row['path'],'actualNativeSceneGeometryAndMaterialsIdentical':True,'allObservedRootAndPartsClosedBeforeRendering':True,'injectedFailureClosesObservedSolids':True,'proof':proofs['after']})
 (H/'behavior.json').write_text(json.dumps({'sources':evidence,'limits':'Real full authored CPU/native geometry and scene import, plus controlled failure; stops before environment loading, camera and Vulkan renderer creation. No GPU rendering or new public capture.'},indent=2)+'\n')
asyncio.run(main())
