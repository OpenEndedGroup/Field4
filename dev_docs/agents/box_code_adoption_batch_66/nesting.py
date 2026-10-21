"""Update just the diagnostic code through Field's existing document codec."""
from pathlib import Path
import ast,asyncio,importlib.util,json,re,shutil
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;O=R/'build/box-code-revision/adoption-66';O.mkdir(parents=True,exist_ok=True)
spec=importlib.util.spec_from_file_location('rig66',H.parent/'box_code_adoption_batch_26/rehearse.py');rig=importlib.util.module_from_spec(spec);spec.loader.exec_module(rig);m=rig.m;m.PORT=9721;m.OUT=O/'nesting';m.OUT.mkdir(parents=True,exist_ok=True)
p=R/'dev_docs/nesting_edge_demo/metadata.edn';recorded=json.loads((H/'changes.json').read_text());prior=next((r for r in recorded if r['path']==str(p.relative_to(R))),None);before=prior['before'] if prior else p.read_text();old=next(json.loads(x[1]) for x in re.finditer(r'"code"\s+("(?:\\.|[^"\\])*")',before) if 'setPropertyRaw' in json.loads(x[1]))
a=old.index('// `Box.disconnect`');b=old.index('\nprintln("--- what the edge buys',a)
new=old[:a]+'''// Compare lookup with just this incoming edge removed; retain the stored graph on failure.
val saved = child.parents
val withoutEdge = try {
    child.parents = saved.toMutableSet().apply { remove(container) }
    gatherPreambleUpwards(child)
} finally {
    child.parents = saved
}
'''+old[b:]
request=[{'directory':str(p.parent),'sidecars':[],'updates':[{'box':'edge-probe','property':'code','code':new}]}];q=O/'nesting-request.json';q.write_text(json.dumps(request,indent=2)+'\n')
module=ast.parse((H.parent/'box_code_second_pass/refresh_formatted_metadata.py').read_text());codec=next(ast.literal_eval(n.value) for n in module.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='CODE' for t in n.targets)).replace('REQUEST',json.dumps(str(q)))
async def main():
 assertions='''val c=inside.boxes.getValue("nest-container")
val child=inside.boxes.getValue("nested-child")
check(c in child.parents)
println("real loaded document has its restored nesting edge")'''
 if prior is None:
  await m.macro('nesting-codec66',[{'create':{'box':'g1-codec66','properties':{'code':codec}}},{'execute':{'box':'g1-codec66'}}])
  after=p.read_text();assert after!=before
  rows=json.loads((H/'changes.json').read_text());rows.append({'path':str(p.relative_to(R)),'before':before,'after':after});(H/'changes.json').write_text(json.dumps(rows,indent=2)+'\n')
  # Open a private copy through the actual document loader so nesting edges are re-derived.
  folder=R/'build/field-automation-workspace/box-code-nesting-66';folder.mkdir(exist_ok=True);shutil.copy2(p,folder/'metadata.edn')
  await m.batch('nesting-loaded',['open box-code-nesting-66','run edge-probe'])
  await m.macro('nesting-success-restores',[{'execute':{'box':'edge-probe','type':'fragment','code':'run {\n'+assertions+'\n}'}}])
 # Execute the actual diagnostic body, injecting a failure at its fallible gather operation.
 diagnostic=new.replace('    gatherPreambleUpwards(child)\n} finally','    failingGather(child)\n} finally')
 assert diagnostic!=new
 imports='\n'.join(x for x in diagnostic.splitlines() if x.startswith('import '));body='\n'.join(x for x in diagnostic.splitlines() if not x.startswith('import '))
 tested=imports+'\nfun failingGather(box:Box):String = throw IllegalStateException("controlled gather failure")\nval failure=runCatching {\n'+body+'\n}.exceptionOrNull()\ncheck(failure is IllegalStateException && failure.message=="controlled gather failure")\n'+assertions
 await m.macro('nesting-failure-restores-typed',[{'create':{'box':'edge-failure66-typed','properties':{'code':tested}}},{'execute':{'box':'edge-failure66-typed'}}])
 (H/'nesting-behavior.json').write_text(json.dumps({'realCodecPreservesOtherProperties':True,'actualDocumentLoader':True,'successRestoresIncomingEdge':True,'injectedGatherFailureRestoresIncomingEdge':True,'noRawPropertyBackdoor':True},indent=2)+'\n')
asyncio.run(main())
