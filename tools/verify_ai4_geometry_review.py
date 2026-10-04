"""Live AI4 geometry/review regression using the real Field viewer and shared tool registry.

Run with uv run --project . --group backdoor python tools/verify_ai4_geometry_review.py.
The backdoor proves checkout ownership before reusing a server. Evidence goes under build/.
"""
import argparse
import asyncio
import base64
import json
import sys
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
from field_backdoor import FieldBackdoor, SessionLogger, select_automation_port, DEFAULT_AUTOMATION_PORT


async def verify(port: int, output: Path, attach_existing: bool = False):
    output.mkdir(parents=True, exist_ok=True)
    logger = SessionLogger(output / 'logs', sys.argv)
    client = FieldBackdoor(port=port, workspace=None, default_timeout=120, logger=logger, attach_existing=attach_existing)
    evidence = {'checks': [], 'calls': [], 'passed': False}

    def check(condition, label):
        evidence['checks'].append({'check': label, 'passed': bool(condition)})
        print(label, bool(condition), flush=True)
        if not condition:
            raise AssertionError(label)

    async def tool(tool_name, **args):
        result = await client.ai_tool_call(name=tool_name, arguments=args, timeout=120)
        evidence['calls'].append({'name':tool_name, 'arguments':args, 'ok':result.get('ok'), 'result':result.get('result'), 'images':len(result.get('images', []))})
        if not result.get('ok'):
            raise RuntimeError(result)
        return result['result'], result.get('images', [])

    async def browser(script):
        reply = await client.playwright_input(action='pageEval', script=script, script_mode='body', timeout=60)
        if not reply.get('ok'):
            raise RuntimeError(reply)
        return reply.get('result')

    try:
        await client.ensure_server()
        await client.connect()
        await client.open_document('geometry-workbench-proof-' + uuid.uuid4().hex[:8], scratch=False, timeout=120, keep_running=True)
        result, _ = await tool('NewBoxRequest', name='Geometry tool proof')
        uid = result['uid']
        code = '''val geometry = three(name = "proof", width = 760, height = 540) {
    mesh(boxMesh(120, 80, 60), id = "block", wireframe = false, pickable = true, group = 0)
    mesh(boxMesh(20, 20, 20), id = "hidden-block", wireframe = false, pickable = true, group = 1)
    camera(yaw = 34.0, pitch = 24.0)
    background("#080b0c")
    inspect(units = "mm", groups = listOf("Block", "Hidden block"))
    caption("AI4 geometry workbench verification")
}
println(geometry.uid)'''
        result, _ = await tool('RunBoxRequest', box=uid, code=code, timeoutSeconds=120)
        check(result['success'], 'scene runs through native AI4 tool')
        view = uid + '_proof'
        listed, _ = await tool('ListGeometryViewsRequest')
        entry = next(v for v in listed['details']['views'] if v['viewId'] == view)
        revision = entry['revision']
        shot, images = await tool('InspectGeometryViewRequest', viewId=view, expectedRevision=revision, includeMetadata=True)
        check(shot['success'] and len(images)==1, 'native inspection returns a real image')
        check(shot['details']['state']['artifactDigest']==revision, 'picture carries exact geometry revision')
        evidence['initialPng'] = shot['details']['path']
        front = next(s['id'] for s in shot['details']['sources'] if s['source'].get('id') == 'block')
        compact, _ = await tool('InspectGeometryViewRequest', viewId=view, expectedRevision=revision,
                                camera=shot['details']['state']['camera'], target=shot['details']['state']['target'])
        check(compact['success'] and compact['details']['state']==shot['details']['state'], 'camera arguments preserve complete current state')
        check(len(json.dumps(compact['details'])) < len(json.dumps(shot['details'])), 'default metadata is smaller and explicit full metadata remains available')
        region, _ = await tool('InspectGeometryViewRequest', viewId=view, expectedRevision=revision, xray=True, region={'cx':.5,'cy':.5,'width':.2,'height':.2,'angle':25.0})
        check(region['success'], 'oriented region works through the native tool')
        selection = region['details']['state']['selection']
        check(selection['kind']=='region' and selection['region']['angle']==25 and selection['region']['surfaces'] and all(s['partId']==front for s in selection['region']['surfaces']), 'x-ray region selects only the frontmost surface')
        restored, _ = await tool('InspectGeometryViewRequest', viewId=view, expectedRevision=revision, stateJson=json.dumps(region['details']['state']))
        check(restored['success'] and restored['details']['state']['selection']==selection, 'inspection state round-trips its oriented region')
        await client.refresh_snapshot(timeout=30)
        await client.open_playwright(timeout=90)
        prefix = '''let f; for(let i=0;i<200;i++) {
 f=[...document.querySelectorAll('iframe')].find(f=>{try{return new URL(f.src).searchParams.get('uid')===VIEW && f.contentWindow.FieldInspector?.snapshot().artifactDigest;}catch(_){return false;}});
 if(f)break;await new Promise(r=>setTimeout(r,50));
}
if(!f)throw new Error('No geometry iframe');
const w=f.contentWindow, api=w.FieldInspector;\n'''.replace('VIEW',json.dumps(view))
        saved = await browser(prefix + '''
api.pick(.5,.5,'face',true);
w.document.getElementById('feature-note').value='Check this visible face and remember what you verified.';
w.document.getElementById('feature-save').click();
for(let i=0;i<100;i++) {
 const inbox=await (await w.fetch('/three-review?uid='+encodeURIComponent(new URL(w.location.href).searchParams.get('uid')))).json();
 if(inbox.notes.length)return {state:api.snapshot(),inbox};
 await new Promise(r=>setTimeout(r,50));
}
throw new Error('Annotation was not saved: '+w.document.getElementById('feature-message').textContent);
''')
        check(len(saved['inbox']['notes'])==1, 'viewer saves a real annotation')
        note = saved['inbox']['notes'][0]
        read, images = await tool('ReadGeometryNoteRequest', viewId=view, noteId=note['id'])
        check(read['success'] and len(images)==2, 'AI4 receives saved context and crop images')
        for name, image in zip(['context','crop'],images):
            (output / (name+'.png')).write_bytes(base64.b64decode(image['dataUrl'].split(',',1)[1]))
        observed, images = await tool('InspectGeometryViewRequest', viewId=view, expectedRevision=revision, noteId=note['id'], explode=20.0, xray=True, pick={'x':.5,'y':.5,'kind':'face'})
        check(observed['success'] and len(images)==1, 'agent restores and inspects the annotation independently')
        after = await browser(prefix + "return {state:api.snapshot(),inbox:await (await w.fetch('/three-review?uid='+encodeURIComponent(new URL(w.location.href).searchParams.get('uid')))).json()};")
        check(after['state']==saved['state'], 'agent leaves human camera and view settings unchanged')
        check(after['inbox']['selection']==saved['inbox']['selection'], 'agent leaves human manual selection unchanged')
        replied, _ = await tool('ReplyGeometryNoteRequest', viewId=view, noteId=note['id'], text='Verified the selected face and retained both source pictures.', actor='geometry-tool-proof', checkedRevision=revision, expectedVersion=0)
        check(replied['success'] and replied['details']['note']['status']=='open', 'reply records knowledge without resolving')
        stale, _ = await tool('ReviewGeometryNoteRequest', viewId=view, noteId=note['id'], status='resolved', summary='Stale write', actor='geometry-tool-proof', checkedRevision=revision, expectedVersion=0)
        check(not stale['success'], 'competing stale write is refused')
        resolved, _ = await tool('ReviewGeometryNoteRequest', viewId=view, noteId=note['id'], status='resolved', summary='Verified current geometry and saved evidence.', actor='geometry-tool-proof', checkedRevision=revision, expectedVersion=1)
        check(resolved['success'], 'verified note resolves explicitly')
        remaining, _ = await tool('ReadGeometryReviewsRequest', viewId=view)
        check(remaining['details']['total']==0, 'resolved notes leave the default agent inbox')
        historical, images = await tool('ReadGeometryNoteRequest', viewId=view, noteId=note['id'])
        check(len(images)==2 and len(historical['details']['note']['replies'])==1, 'resolution retains original pictures and replies')
        camera_before = await browser(prefix + '''
const s=api.snapshot();s.selection=null;s.selectedPart=null;s.selectedTriangle=null;
s.camera=[s.target[0]+150,s.target[1]+170,s.target[2]+290];s.cameraZoom=1.17;
api.restore(s);return api.snapshot();
''')
        rerun, _ = await tool('RunBoxRequest', box=uid, code=code.replace('120, 80, 60','140, 80, 60'), timeoutSeconds=120)
        check(rerun['success'], 'changed geometry rebuilds')
        camera_after = await browser(prefix + '''
for(let i=0;i<100;i++){if(api.snapshot().artifactDigest!==OLD)break;await new Promise(r=>setTimeout(r,50));}
return api.snapshot();
'''.replace('OLD',json.dumps(revision)))
        check(camera_after['artifactDigest']!=revision and
              all(camera_after[k]==camera_before[k] for k in ['camera','target','cameraZoom','cameraFov']),
              'same camera preset preserves the positioned view across geometry updates')
        resized = await browser(prefix + '''
f.style.width='690px';f.style.height='490px';
await new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)));
return api.snapshot();
''')
        check(all(resized[k]==camera_before[k] for k in ['camera','target','cameraZoom','cameraFov']),
              'resizing a positioned view preserves its camera')
        explicit, _ = await tool('RunBoxRequest', box=uid, code=code.replace('120, 80, 60','140, 80, 60').replace('yaw = 34.0','yaw = -28.0'), timeoutSeconds=120)
        check(explicit['success'], 'an explicit camera change runs')
        moved = await browser(prefix + '''
for(let i=0;i<100;i++){if(JSON.stringify(api.snapshot().camera)!==OLD)return true;await new Promise(r=>setTimeout(r,50));}return false;
'''.replace('OLD',json.dumps(json.dumps(camera_before['camera'],separators=(',',':')))))
        check(moved, 'a changed camera specification still establishes the requested view')
        obsolete, _ = await tool('InspectGeometryViewRequest', viewId=view, expectedRevision=revision)
        check(not obsolete['success'], 'old revision cannot masquerade as current geometry')
        historical, images = await tool('ReadGeometryNoteRequest', viewId=view, noteId=note['id'])
        check(historical['details']['currentRevision'] != revision and len(images)==2, 'old annotation remains available after rebuild')
        evidence.update(viewId=view, sourceBox=uid, passed=True)
    finally:
        (output / 'verification.json').write_text(json.dumps(evidence, indent=2))
        try:
            await client.close()
        finally:
            logger.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/ai4-geometry-review')
    parser.add_argument('--attach-existing', action='store_true', help='Use an explicitly selected live server; never stops it')
    args = parser.parse_args()
    if args.attach_existing and args.port is None: parser.error('--attach-existing requires --port')
    port = args.port if args.port is not None else select_automation_port(DEFAULT_AUTOMATION_PORT, explicit=False)
    asyncio.run(verify(port, args.output.resolve(), args.attach_existing))


if __name__ == '__main__':
    main()
