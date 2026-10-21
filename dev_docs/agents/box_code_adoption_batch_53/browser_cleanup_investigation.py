"""Exercise actual native Csound and actual Chromium registrations across rerun/Stop."""
from pathlib import Path
import importlib.util,asyncio,json,re,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig53',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9718;m.OUT=R/'build/box-code-revision/adoption-53/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
from format_docs import sections
async def execute(name,box,code):return await m.macro(name,[{'execute':{'box':box,'type':'fragment','code':code}}])
async def edit(name,box,old,new):return await m.macro(name,[{'edit':{'box':box,'property':'code','from':old,'to':new}},{'launch':{'box':box}}])
async def main():
    # Run against the existing warm scratch session only: this is a diagnostic, not a pass.
    await execute('cleanup-scope-inventory', 'release-scope',
                  'println(thisBox.runScopes.snapshot().map {it.toString()+" live="+it.liveCount})')
asyncio.run(main())
