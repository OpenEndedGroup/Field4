"""Execute the revised authored code in a fresh private Field session and capture its real output."""
import asyncio,json,re,shlex,sys
from pathlib import Path
import session as S
sys.path.insert(0,str(S.ROOT/'tools'))
from field_session import send_request,socket_path_for
OUT=S.OUT/'runtime';OUT.mkdir(exist_ok=True)
async def call(name,commands):
    result=await send_request(socket_path_for(9763),dict(commands=commands,resetDoc=True,scratch=True,timeout=240),timeout=300)
    (OUT/(name+'.json')).write_text(json.dumps(result,indent=2)+'\n')
    assert result.get('ok'),(name,str(result)[-4500:])
    return result
async def code(name,source,capture=True):
    fixture=OUT/(name+'.kt');prior=OUT/(name+'.json')
    if fixture.exists() and fixture.read_text()==source and prior.exists() and json.loads(prior.read_text()).get('ok') and (not capture or (OUT/(name+'.png')).exists()):return json.loads(prior.read_text())
    fixture.write_text(source)
    commands=['new-box --name '+name,'run @last --value '+shlex.quote(source)]
    if capture:commands+=['screenshot-box @last '+shlex.quote(str(OUT/(name+'.png')))+' --padding 16 --open-outputs']
    return await call(name,commands)
def body(path,contains):return next(r['code'] for r in S.spans(path,(S.ROOT/path).read_text()) if contains in r['code'])
async def main():
    checks=[]
    tests=[('presets','dev_docs/agents/transcripts/controls_presets_morph_midi.yaml','var size by keys.slider'),('surface','dev_docs/agents/transcripts/controls_surface.yaml','val radius = 10'),('ring','dev_docs/agents/transcripts/docs_capture/tour_interface_document.yaml','// A ring of points.'),('query_table','public/docs/graph_query.md','var reads = 0'),('chat_outline','public/docs/ai4_chat_archive.md','ChatLogViews.outline(tree)')]
    for name,path,contains in tests:
        source=body(path,contains)
        if name=='surface':source+='\ndraw()\n'
        await code(name,source);checks.append(dict(name=name,status='passed',source=path,capture=str((OUT/(name+'.png')).relative_to(S.ROOT))))
    path='dev_docs/agents/transcripts/docs_capture/vec2_gestures.yaml'
    await code('trail',body(path,'fun showTrail')+'\nshowTrail(vec(0.0, 0.0))\nshowTrail(vec(0.5, 0.5))\nshowTrail(vec(-0.4, 0.7))\n')
    checks.append(dict(name='trail',status='passed',source=path))
    path='dev_docs/agents/transcripts/audio_ai_analysis.yaml'
    await code('guide',body(path,'Beat and pitch analysis'))
    checks.append(dict(name='guide',status='passed',source=path))
    # Rerun the changed function/creative values in the existing box, using the real compiler.
    source=body('dev_docs/agents/transcripts/controls_presets_morph_midi.yaml','var size by keys.slider')
    import yaml
    fixture=OUT/'live_edits.yaml'
    fixture.write_text(yaml.safe_dump(dict(operations=[dict(create=dict(box='live',name='live-edits',frame=dict(x=0.,y=0.,w=300.,h=100.),properties=dict(code=source))),dict(execute=dict(box='live',property='code',type='fragment')),dict(execute=dict(box='live',property='code',type='fragment',code='size = 0.8\nring = true\ndraw()'))]),sort_keys=False))
    await call('live_edits',['macro write live --file '+shlex.quote(str(fixture)),'macro run live','screenshot-box live-edits '+shlex.quote(str(OUT/'live_edits.png'))+' --padding 16 --open-outputs'])
    checks.append(dict(name='live_edits',status='passed',method='Actual second fragment changes size and ring, then redraws the live figure'))
    (S.HERE/'runtime-verification.json').write_text(json.dumps(dict(status='passed',checks=checks),indent=2)+'\n')
    print(len(checks),'real Field rehearsals passed',flush=True)
if __name__=='__main__':asyncio.run(main())
