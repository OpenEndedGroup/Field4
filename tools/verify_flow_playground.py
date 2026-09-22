#!/usr/bin/env python3
"""Exercise editable GPU stages in an isolated, already-running Field server.

Replay replaces the named test document. The server workspace must be under build/.
Run after verify_light_infrastructure.py; requires a native Vulkan renderer.
Add --server-log for a validated document-close check. No other client may keep
flow_grain_verified open during that check.
"""
import argparse, asyncio, base64, json
from pathlib import Path
from field_backdoor import FieldBackdoor, SessionLogger, LOG_ROOT, REPO_ROOT

async def verify(port, server_log=None):
    log_offset = 0
    if server_log is not None:
        server_log = server_log.resolve(strict=True)
        assert server_log.is_relative_to(LOG_ROOT.resolve()), server_log
        log_offset = server_log.stat().st_size
    logger = SessionLogger(LOG_ROOT, ['verify-flow-playground'])
    c = FieldBackdoor(port=port, workspace=None, default_timeout=120, logger=logger, attach_existing=True)
    folder = REPO_ROOT / 'build/light-infrastructure'
    folder.mkdir(parents=True, exist_ok=True)
    evidence = []
    def passed(name, details=None):
        evidence.append({'check':name,'details':details})
        (folder/'graphics.json').write_text(json.dumps(evidence,indent=2))
        print('PASS',name,flush=True)
    async def tool(name, args):
        r=await c.ai_tool_call(name=name,arguments=args,timeout=120)
        assert r.get('ok'),r
        return r
    async def source(prop):
        return (await tool('DocumentSourceReadRequest',{'uid':'flow_grain','property':prop}))['result']
    async def patch(old, text):
        r=(await tool('DocumentSourcePatchRequest',{'uid':'flow_grain','property':'fragment','revision':old['liveRevision'],'contents':text}))['result']
        assert r['success'],r
        return r
    async def run(code):
        data,events,timeout=await c.run_box(probe,property_name='code',value=code,timeout=120)
        assert not timeout and not c.execution_errors(events),(data,c.execution_errors(events))
    try:
        await c.ensure_server()
        assert Path(c.server_workspace).resolve().is_relative_to(REPO_ROOT/'build'),c.server_workspace
        await c.connect()
        await c.open_document('flow_grain_verified',scratch=False,timeout=120)
        await c.macro_transcript_command(command='write',name='flow_grain',content=(REPO_ROOT/'dev_docs/agents/transcripts/flow_grain.yaml').read_text(),timeout=120)
        result=await c.macro_transcript_command(command='run',name='flow_grain',timeout=180)
        assert result['success'],result
        passed('clean GPU graph replay')
        original=await source('fragment')
        bad=await patch(original,original['contents']+'\ninvalid GLSL on purpose;\n')
        result=await tool('ReloadShadersRequest',{'target':'flow_grain','mode':'graphics','visualCheck':True})
        assert not result['result']['success'] and not result.get('images'),result['result']
        passed('failed shader reload reports failure without a success image',result['result'])
        await patch(bad,original['contents'])
        result=await tool('ReloadShadersRequest',{'target':'flow_grain','mode':'graphics','visualCheck':True})
        assert result['result']['success'] and len(result.get('images',[]))==1,result['result']
        raw=result['images'][0]['dataUrl']
        path=folder/('reload.png' if 'image/png' in raw.split(',')[0] else 'reload.jpg')
        path.write_bytes(base64.b64decode(raw.split(',',1)[1]))
        passed('shader recovery returns an actual native image',str(path))
        result=await tool('CaptureRenderWindowRequest',{'uid':'not-a-box','settleMs':0})
        assert not result['result']['success'] and not result.get('images'),result['result']
        passed('invalid capture target does not fall back to another window')
        probe=(await c.create_box(name='Verification',x=1500,y=300,w=200,h=80,timeout=120))['box']['uid']
        await c.change_connections(action='connect',parent_uid='flow_resources',child_uids=[probe],timeout=120)
        common='import afield.four.modules.fromContextWithNameOrNull\n'
        if server_log is not None:
            await run('check(afield.four.util.Options.validate) { "Vulkan validation must be enabled" }\n')
        await run('System.gc()\n')
        result = await c.native_window_screenshot(
            path=str(folder/'after-gc.png'),uid='flow_renderer',wait_frames=32,timeout=120)
        assert result.get('ok'), result
        passed('live pipelines retain descriptor layouts across garbage collection')
        sim='val simulation = fromContextWithNameOrNull<afield.four.targets.ComputeTarget>("flowSimulation")!!\n'
        await c.stop_box('flow_advect',timeout=120)
        await run(common+sim+'check("advect" !in simulation.modules)\n')
        data,events,timeout=await c.launch_box('flow_advect',property_name='code',value=None,timeout=120)
        assert not timeout and not c.execution_errors(events),(data,events)
        await run(common+sim+'check(simulation.modules.keys == setOf("advect","divergence","pressure","project","particles"))\n')
        passed('ordinary stop removes a stage; relaunch restores one copy')
        await run(common+'val p = fromContextWithNameOrNull<afield.stdlib.EffectiveParameter>("force")!!\np.requested=100.0\ncheck(p.effective==12.0 && p.snapshot().requested==100.0)\np.requested=2.5\n')
        passed('requested and effective values agree with live shader input bounds')
        look='val look = fromContextWithNameOrNull<afield.stdlib.Vec4>("flowLook")!!\nval travel = fromContextWithNameOrNull<java.util.concurrent.atomic.AtomicBoolean>("travel")!!\n'
        await c.stop_box('flow_strokes',timeout=120)
        await run(common+look+'travel.set(true)\nlook.w=4f\n')
        result=await c.native_window_screenshot(path=str(folder/'displacement.png'),uid='flow_renderer',wait_frames=24,timeout=120)
        assert result.get('ok'),result
        await run(common+look+'look.w=3f\n')
        result=await c.native_window_screenshot(path=str(folder/'previous-uv.png'),uid='flow_renderer',wait_frames=24,timeout=120)
        assert result.get('ok'),result
        await run(common+look+'look.w=0f\ntravel.set(false)\n')
        await c.launch_box('flow_strokes',property_name='code',value=None,timeout=120)
        passed('camera plus object motion attachment debug captures')
        marker=folder/'render-done.txt'
        marker.unlink(missing_ok=True)
        code=(REPO_ROOT/'dev_docs/agents/light_infrastructure/VERIFY_RENDER.kt').read_text()
        data,events,timeout=await c.launch_box(probe,property_name='code',value=code,timeout=120)
        assert not timeout and not c.execution_errors(events),(data,c.execution_errors(events))
        deadline=asyncio.get_running_loop().time()+120
        while not marker.exists():
            assert asyncio.get_running_loop().time()<deadline,'render verification timed out'
            await asyncio.sleep(.25)
        passed('32 resets and two fixed 180-frame profiles',(folder/'reset.txt').read_text())
        await c.delete_boxes([probe],timeout=120)
        print('Evidence:',folder,flush=True)
    finally:
        await c.close();logger.close()

    if server_log is not None:
        # The last client detaches after all visual/reset checks. Teardown happens on
        # the render thread, so wait for its positive completion marker before grading.
        deadline = asyncio.get_running_loop().time() + 20
        while True:
            with server_log.open('rb') as log:
                log.seek(log_offset)
                observed = log.read().decode('utf-8', errors='replace')
            assert 'VUID-' not in observed, 'Vulkan validation error: inspect ' + str(server_log)
            assert 'graphics context dispose failed' not in observed, 'Renderer shutdown failed: ' + str(server_log)
            if 'disposed renderer for box flow_renderer;' in observed:
                break
            assert asyncio.get_running_loop().time() < deadline, (
                'No completed renderer shutdown. Close other clients of flow_grain_verified; '
                'inspect ' + str(server_log)
            )
            await asyncio.sleep(.1)
        assert 'Inserted device layer "VK_LAYER_KHRONOS_validation"' in observed, (
            'No evidence that the validation layer was loaded: ' + str(server_log)
        )
        passed('document close destroys renderer without Vulkan validation errors', str(server_log))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--port',type=int,required=True)
    p.add_argument('--server-log',type=Path,help='This server’s managed log under .field-backdoor/logs')
    args=p.parse_args()
    asyncio.run(verify(args.port,args.server_log))
