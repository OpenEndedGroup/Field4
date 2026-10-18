"""Round-trip the formatted diagnostic through the same selective Field codec."""
from pathlib import Path
import ast,asyncio,importlib.util,json,re,subprocess
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;O=R/'build/box-code-revision/adoption-66'
p=R/'dev_docs/nesting_edge_demo/metadata.edn';source=next(json.loads(x[1]) for x in re.finditer(r'"code"\s+("(?:\\.|[^"\\])*")',p.read_text()) if 'val withoutEdge' in json.loads(x[1]));temp=O/'nesting_code_.kt';temp.write_text(source)
subprocess.run([str(R/'gradlew'),'formatBoxCode','--args='+str(temp)],cwd=R,check=True)
request=O/'nesting-format-request.json';request.write_text(json.dumps([{'directory':str(p.parent),'sidecars':[],'updates':[{'box':'edge-probe','property':'code','code':temp.read_text()}]}],indent=2)+'\n')
module=ast.parse((H.parent/'box_code_second_pass/refresh_formatted_metadata.py').read_text());codec=next(ast.literal_eval(n.value) for n in module.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='CODE' for t in n.targets)).replace('REQUEST',json.dumps(str(request)))
s=importlib.util.spec_from_file_location('rig66format',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9721;m.OUT=O/'nesting';m.OUT.mkdir(parents=True,exist_ok=True)
async def main():
 await m.macro('nesting-format-codec66',[{'create':{'box':'g1-format-codec66','properties':{'code':codec}}},{'execute':{'box':'g1-format-codec66'}}])
 rows=json.loads((H/'changes.json').read_text())
 for row in rows:
  if row['path']==str(p.relative_to(R)):row['after']=p.read_text()
 (H/'changes.json').write_text(json.dumps(rows,indent=2)+'\n')
asyncio.run(main())
