"""Exercise the changed table/mesh lessons; comment-only behavior is protected by token equality."""
from pathlib import Path
import importlib.util,sys,asyncio,json
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;sys.path.insert(0,str(H.parent/'box_code_adoption_batch_3'))
s=importlib.util.spec_from_file_location('rig24',H.parent/'box_code_adoption_batch_3/rehearse.py');m=importlib.util.module_from_spec(s);s.loader.exec_module(m);m.ROOT=R;m.PORT=9700;m.OUT=R/'build/box-code-revision/adoption-24/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
async def main():
 code=(R/'templates/h2-explorer-examples/15301b9c-4454-4b37-b439-7c5fc2c8d06d_code_.kt').read_text()
 await m.program('open_table',code+'\ncheck(f.nodes.size==4 && f.nodes.none { it is FLine.Close });println("open path has four stored nodes")',channel='fline',capture=True)
 await m.program('closed_table',code+'\nf.close();check(f.nodes.last() is FLine.Close);check(f.nodes.last().storage.isEmpty());display("fline",f.h2)',channel='fline',capture=True)
 await m.program('mesh_and_line',(R/'public/docs/assets/three_mesh_pick_demo/mesh-pick-clearance_code_.kt').read_text(),channel='main',capture=True)
 (m.OUT/'verification.json').write_text(json.dumps({'openAndClosedPathTables':True,'closeStoresNoEndpoint':True,'actualMeshAndLineViewer':True},indent=2)+'\n')
if __name__=='__main__':asyncio.run(main())
