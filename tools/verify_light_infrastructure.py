#!/usr/bin/env python3
"""Live source/receipt/export/UI verification against an isolated existing Field.

Writes unique test documents and exports below build/. Never starts or restarts
Field; refuses a workspace outside this checkout's build tree.
"""
import argparse
import asyncio
import hashlib
import json
import time
from pathlib import Path
import yaml
from field_backdoor import FieldBackdoor, SessionLogger, LOG_ROOT, REPO_ROOT

async def verify(port):
    evidence = REPO_ROOT / 'build/light-infrastructure'
    evidence.mkdir(parents=True, exist_ok=True)
    name = f'infrastructure_check_{time.time_ns()}'
    logger = SessionLogger(LOG_ROOT, ['verify-light-infrastructure'])
    c = FieldBackdoor(port=port, workspace=None, default_timeout=90, logger=logger, attach_existing=True)
    checks = []
    def passed(name, details=None):
        checks.append({'check': name, 'details': details})
        (evidence / 'integration.json').write_text(json.dumps(checks, indent=2))
        print(f'PASS {name}', flush=True)
    async def tool(name, args):
        response = await c.ai_tool_call(name=name, arguments=args, timeout=90)
        assert response.get('ok'), response
        return response['result']
    async def read(uid):
        return await tool('DocumentSourceReadRequest', {'uid': uid})
    async def patch(uid, old, contents):
        return await tool('DocumentSourcePatchRequest', {'uid': uid, 'property': 'code', 'revision': old['liveRevision'], 'contents': contents})
    try:
        await c.ensure_server()
        assert Path(c.server_workspace).resolve().is_relative_to(REPO_ROOT / 'build'), c.server_workspace
        await c.connect()
        await c.open_document(name, scratch=False, timeout=90)
        names = {t['name'] for t in await c.ai_tools_list(timeout=60)}
        required = {'DocumentSourceReadRequest', 'DocumentSourcePatchRequest', 'DocumentSourceExportRequest', 'DocumentBoxNavigateRequest', 'CaptureRenderWindowRequest', 'ReloadShadersRequest'}
        assert required <= names, required - names
        passed('shared tool coverage', sorted(required))
        parent = (await c.create_box(name='Receipt witness', x=4000, y=4000, w=180, h=80, properties={'code': 'println("first")\n'}, timeout=90))['box']['uid']
        child = (await c.create_box(name='Nested child', x=4250, y=4000, w=180, h=80, properties={'code': 'println("child")\n', 'fragment': '// editable shader\n'}, timeout=90))['box']['uid']
        first = await read(parent)
        text = 'println("exact authored source")\n\n'
        changed = await patch(parent, first, text)
        assert changed['success'] and changed['liveRevision'] == hashlib.sha256(text.encode()).hexdigest()
        conflict = await patch(parent, first, 'error("must not overwrite")')
        assert not conflict['success'] and conflict['conflict'] and conflict['contents'] == text
        passed('revision conflict refuses overwrite')
        data, events, timeout = await c.launch_box(parent, property_name='code', value=None, timeout=90)
        assert not timeout and not c.execution_errors(events), (data, events)
        successful = await read(parent)
        assert successful['executed']['revision'] == changed['liveRevision'], successful
        bad = await patch(parent, successful, 'thereIsNoSuchFunctionForReceiptTest()\n')
        data, events, timeout = await c.launch_box(parent, property_name='code', value=None, timeout=90)
        assert not timeout and c.execution_errors(events), (data, events)
        failed = await read(parent)
        assert failed['executed']['revision'] == successful['executed']['revision'], failed
        assert failed['attempted']['success'] is False and failed['attempted']['revision'] == bad['liveRevision'], failed
        passed('failed setup retains last successful source', {'success': successful['executed'], 'failure': failed['attempted']})
        macro = {'name': 'failure_witness', 'operations': [
            {'create': {'box': 'before_failure', 'name': 'Preserved edit', 'properties': {'code': '// preserved'}}},
            {'launch': {'box': parent}},
            {'create': {'box': 'after_failure', 'name': 'Must not run', 'properties': {'code': '// unreachable'}}}]}
        assert (await tool('MacroWriteRequest', {'name': 'failure_witness', 'content': yaml.safe_dump(macro)}))['success']
        result = await tool('MacroRunRequest', {'name': 'failure_witness'})
        assert not result['success'] and result['errors'], result
        snapshot = await c.refresh_snapshot(timeout=90)
        assert 'before_failure' in c.boxes and 'after_failure' not in c.boxes, snapshot
        passed('macro failure stops dependents and preserves prior work', result)
        await patch(parent, failed, text)
        nesting = {'name': 'nesting', 'operations': [{'connect': {'parent': parent, 'child': child, 'nested': True}}]}
        await c.macro_transcript_command(command='write', name='nesting', content=yaml.safe_dump(nesting), timeout=90)
        assert (await c.macro_transcript_command(command='run', name='nesting', timeout=90))['success']
        note = '# Export witness\n\nExact guide bytes.\n'
        assert (await tool('DocumentGuideCreateRequest', {'path': 'HANDOFF.md', 'contents': note}))['success']
        export = evidence / name
        result = await tool('DocumentSourceExportRequest', {'uids': [parent], 'directory': str(export), 'guides': ['HANDOFF.md']})
        assert result['success'] and set(result['boxes']) == {parent, child}, result
        assert (export / 'HANDOFF.md').read_text() == note
        manifest = yaml.safe_load((export / 'install.yaml').read_text())
        assert any(op.get('connect', {}).get('nested') for op in manifest['operations'])
        refused = await c.ai_tool_call(name='DocumentSourceExportRequest', arguments={'uids': [parent], 'directory': str(export)}, timeout=90)
        assert not refused.get('ok') or not refused.get('result', {}).get('success', True), refused
        passed('export preserves nesting and guide bytes; refuses overwrite')
        await c.open_document(name + '_import', scratch=False, timeout=90)
        await c.macro_transcript_command(command='write', name='import', content=(export/'install.yaml').read_text(), timeout=90)
        result = await c.macro_transcript_command(command='run', name='import', timeout=90)
        assert result['success'], result
        assert (await read(parent))['contents'] == text
        assert (await read(child))['contents'] == 'println("child")\n'
        roundtrip = evidence / (name + '_roundtrip')
        await tool('DocumentSourceExportRequest', {'uids': [parent], 'directory': str(roundtrip)})
        again = yaml.safe_load((roundtrip / 'install.yaml').read_text())
        assert manifest['operations'] == again['operations']
        passed('export installer roundtrip preserves graph and source')
        await c.open_playwright(timeout=90)
        ready = await c.playwright_input(action='pageEval', timeout=90, script="new Promise((resolve,reject)=>{const end=Date.now()+15000; const poll=()=>{if(document.querySelectorAll('.box').length>=2 && typeof window.navigateToBoxProperty==='function')resolve(true); else if(Date.now()>end)reject(Error('document not ready')); else setTimeout(poll,50)};poll()})")
        assert ready.get('ok'), ready
        result = await tool('DocumentBoxNavigateRequest', {'uid': child, 'property': 'fragment', 'line': 1})
        assert result['success']
        navigation = await c.playwright_input(action='pageEval', timeout=90, wait_ms=500,
            script="new Promise((resolve,reject)=>{const end=Date.now()+15000;const poll=()=>{if(window.globalEditor?.getValue()?.includes('// editable shader'))resolve({uid:window.currentSelectedCode?.[0]?.uid,property:window.currentSelectedProperty,text:window.globalEditor.getValue()});else if(Date.now()>end)reject(Error('navigation not received: '+window.currentSelectedProperty));else setTimeout(poll,50)};poll()})")
        (evidence / 'navigation.json').write_text(json.dumps(navigation, indent=2))
        assert navigation.get('ok') and '// editable shader' in json.dumps(navigation), navigation
        passed('shared tool opens requested editor property', navigation)
        placement = await c.playwright_input(action='pageEval', timeout=90, wait_ms=650,
            script="(()=>{const b=window.currentSelectedCode[0];const r=b.getBoundingClientRect();const e=document.querySelector('.monaco-editor-container').getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+r.height/2,expectedX:(16+e.left-14)/2,expectedY:window.innerHeight/2}})()")
        q = placement['result']
        assert abs(q['x']-q['expectedX'])<6 and abs(q['y']-q['expectedY'])<6, placement
        passed('navigation centers the box in the available canvas',q)
        empty = {'name': 'json_witness', 'operations': [{'wait': {'frames': 1}}]}
        await c.macro_transcript_command(command='write', name='json_witness', content=yaml.safe_dump(empty), timeout=90)
        plain = await c.playwright_input(action='pageEval', timeout=90,
            script="window.macroPaletteRun('json_witness','document').then(r => ({plain:Object.getPrototypeOf(r)===Object.prototype, success:r.success, keys:Object.keys(r)}))")
        assert plain.get('ok') and '"plain":true' in json.dumps(plain).replace(' ', ''), plain
        passed('frontend macro result is plain JSON', plain)
        # A guest runtime keeps its existing macro dispatch path. Its result must
        # not be mistaken for the awaited Kotlin setup contract.
        guest = {'name': 'guest_dispatch', 'operations': [
            {'create': {'box': 'guest_root', 'name': 'Datalog runtime', 'properties': {'mainLanguage': 'datalog'}}},
            {'create': {'box': 'guest_program', 'name': 'Facts', 'properties': {'code': 'edge(1, 2).'}}},
            {'connect': {'parent': 'guest_root', 'child': 'guest_program'}},
            {'launch': {'box': 'guest_program'}}]}
        await tool('MacroWriteRequest', {'name': 'guest_dispatch', 'content': yaml.safe_dump(guest)})
        dispatch = await tool('MacroRunRequest', {'name': 'guest_dispatch'})
        assert dispatch['success'] and dispatch['setupVerified'] is False, dispatch
        assert 'unverified' in dispatch['message'], dispatch
        passed('guest-language macro preserves dispatch and marks completion unverified', dispatch)
        print(f'Evidence: {evidence / "integration.json"}', flush=True)
    finally:
        await c.close()
        logger.close()

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, required=True)
    asyncio.run(verify(parser.parse_args().port))
