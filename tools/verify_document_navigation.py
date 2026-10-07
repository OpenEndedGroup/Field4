"""Exercise AI4 semantic navigation through the real document, RPC and Three viewer.

No model calls. Uses an isolated document and the normal backdoor-owned browser.
"""
import argparse, asyncio, json, sys, uuid
from pathlib import Path
from field_backdoor import FieldBackdoor, SessionLogger
ROOT = Path(__file__).resolve().parents[1]
SOURCE = '''import afield.graphics.*
import afield.stdlib.*
three(name="navigation-proof", channel="geometry", width=620, height=430) {
 mesh(TriMesh(floatArrayOf(0f,0f,0f,1000f,0f,0f,0f,1000f,0f,0f,0f,1000f), intArrayOf(0,2,1,0,1,3,0,3,2,1,2,3)),
  id="shape", label="Navigation test geometry", pickable=true, cullBackFaces=false, flatShading=true)
 inspect(); helpers(false); caption("Existing viewer: navigation preserves this camera and X-ray")
}
display("status", "<p>Navigation verification: a separate output channel.</p>")
'''
async def verify(port, output):
    output.mkdir(parents=True, exist_ok=True)
    log = SessionLogger(output/'logs', sys.argv)
    c = FieldBackdoor(port=port, workspace=None, default_timeout=180, logger=log)
    evidence = []
    async def call(name, **arguments):
        r = await c.ai_tool_call(name=name, arguments=arguments, timeout=180)
        evidence.append(dict(tool=name, arguments=arguments, response=r))
        (output/'evidence.json').write_text(json.dumps(evidence, indent=2))
        return r
    async def nav(**arguments):
        r = await call('DocumentBoxNavigateRequest', uid=uid, **arguments)
        assert r.get('ok'), r
        return r['result']
    async def page(script):
        r = await c.playwright_input(action='pageEval', script=script, script_mode='body', timeout=60)
        assert r.get('ok'), r
        evidence.append(dict(browser=r))
        return r
    try:
        await c.ensure_server(); await c.connect()
        doc = 'navigation-proof-'+uuid.uuid4().hex[:8]
        await c.open_document(doc, scratch=False, timeout=120, keep_running=True)
        uid = (await c.create_box(name='Distant visualizer', x=4200, y=-1200, w=420, h=80, timeout=90))['box']['uid']
        absent = await nav(surface='box')
        assert not absent['success'] and 'No frontend confirmed' in absent['message'], absent
        await c.open_playwright(timeout=90)
        r = await call('RunBoxRequest', box=uid, code=SOURCE, timeoutSeconds=120)
        assert r['result']['success'], r
        cold = await nav(surface='output', channel='geometry')
        assert cold['success'] and cold['visible'], cold
        await page("const f=[...document.querySelectorAll('iframe')].find(f=>f.src.includes('navigation-proof')); const r=f.getBoundingClientRect(); if(r.width<innerWidth-40 && Math.abs((r.left+r.right)/2-innerWidth/2)>30) throw Error('Cold output not centred'); return {ok:true,coldRevealCentred:true};")
        await page('''
        let frame;
        for(let i=0;i<150;i++) {
          frame=[...document.querySelectorAll('iframe')].find(f=>f.src.includes('navigation-proof'));
          if(frame?.contentWindow.FieldViewer?.captureStatus().ready) break;
          await new Promise(r=>setTimeout(r,100));
        }
        if(!frame?.contentWindow.FieldInspector) throw Error('Real viewer not ready');
        window.navProofFrame=frame;
        const api=frame.contentWindow.FieldInspector, state=api.snapshot();
        api.restore({...state,camera:state.camera.map((v,i)=>v+(i===0?340:90)),xray:true,xrayOpacity:.3});
        await new Promise(r=>setTimeout(r,500));
        window.navProofState=JSON.stringify(api.snapshot());
        window.navProofEditor=document.querySelector('.monaco-editor-container')?.style.display;
        return {ok:true, viewerReady:true};
        ''')
        missing = await nav(surface='output', channel='absent')
        assert not missing['success'] and set(missing['channels']) == {'geometry','status'}, missing
        ambiguous = await nav(surface='output')
        assert not ambiguous['success'] and len(ambiguous['channels']) == 2, ambiguous
        shown = await nav(surface='output', channel='geometry')
        assert shown['success'] and shown['visible'], shown
        await page('''
        const frame=[...document.querySelectorAll('iframe')].find(f=>f.src.includes('navigation-proof'));
        if(frame!==window.navProofFrame) throw Error('Viewer was replaced');
        if(JSON.stringify(frame.contentWindow.FieldInspector.snapshot())!==window.navProofState) throw Error('Viewer state changed');
        if(document.querySelector('.monaco-editor-container')?.style.display!==window.navProofEditor) throw Error('Output navigation opened the editor');
        const r=frame.getBoundingClientRect();
        if(!(r.right>0&&r.left<innerWidth&&r.bottom>0&&r.top<innerHeight)) throw Error('Output outside viewport');
        if(r.width<innerWidth-40 && Math.abs((r.left+r.right)/2-innerWidth/2)>30) throw Error('Output was not centred after layout settled');
        return {ok:true,cameraAndXrayPreserved:true,editorPreserved:true,rect:{x:r.x,y:r.y,width:r.width,height:r.height}};
        ''')
        await c.screenshot_window(path=str(output/'output-navigation.png'), uid=None, property_name='code', show_editor=False,
            editor_only=False,open_outputs=False,padding=0,pan_into_view=False,wait_ms=0,timeout=60,preserve_selection=True)
        assert (await nav(surface='box'))['success']
        assert (await nav(surface='editor', line=3))['success']
        assert (await nav(surface='output', channel='geometry'))['success']
        await page("if(JSON.stringify(window.navProofFrame.contentWindow.FieldInspector.snapshot())!==window.navProofState) throw Error('Repeat navigation changed viewer'); return {ok:true};")
        invalid = await call('DocumentBoxNavigateRequest',uid=uid,surface='typo')
        assert not invalid.get('ok') or invalid.get('result',{}).get('success') is False, invalid
        await c.open_document(doc+'-other',scratch=False,timeout=120,keep_running=True)
        foreign = await call('DocumentBoxNavigateRequest',uid=uid,surface='output',channel='geometry')
        assert not foreign.get('ok') or foreign.get('result',{}).get('success') is False, foreign
        (output/'result.json').write_text(json.dumps(dict(passed=True,document=doc,uid=uid,checks=[
            'offline frontend fails honestly','named output revealed','ambiguous and missing channels reported',
            'camera and xray unchanged','viewer iframe retained','no editor opened for output',
            'box and editor navigation','repeat output preserves viewer','invalid surface rejected','foreign document UID rejected']),indent=2))
        print('PASSED', output, flush=True)
    finally:
        (output/'evidence.json').write_text(json.dumps(evidence,indent=2))
        await c.close(); log.close()
if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port',type=int,required=True)
    parser.add_argument('--output',type=Path,default=ROOT/'build/document-navigation-verification')
    args=parser.parse_args()
    asyncio.run(verify(args.port,args.output.resolve()))
