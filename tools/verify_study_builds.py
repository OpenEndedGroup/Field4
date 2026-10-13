"""Exercise native AI4 stage tools and Field buttons against real Manifold geometry.

Run with uv run --project . --group backdoor python tools/verify_study_builds.py.
Uses an owned automation document. Retains actual screenshots and a JSON check record.
"""
import argparse
import asyncio
import json
import sys
import uuid
from pathlib import Path

from field_backdoor import FieldBackdoor, SessionLogger, select_automation_port, DEFAULT_AUTOMATION_PORT

ROOT = Path(__file__).resolve().parents[1]

SOURCE = r'''
import afield.*
import afield.geometry.solid.manifold.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

val owner = Box.context.get()!!
val json = jacksonObjectMapper()
val builds = studyBuilds("proof", listOf(
    StudyBuildStage("solid", "Build geometry", "perforated-block-v1", setOf("width")) { c ->
        val width = c.inputs.getValue("width").toDouble()
        if (width <= 20.0) throw StudyBuildFailure(StudyBuildProblem("width-too-small",
            "The block is too narrow for this hole.", listOf("Increase the width above 20 mm."), mapOf("width" to width)))
        c.progress(0.1, "Preparing the solid")
        // Explicit verification barrier, used only to exercise an edit or cancellation in flight.
        while (owner.getPropertyRaw("verificationHold", "false").toString() == "true") {
            c.checkCancelled(); Thread.sleep(20)
        }
        Solid.cube(width, 80.0, 60.0).use { body ->
            Solid.cylinder(80.0, 8.0, segments = 48).translated(width/2, 40.0, -10.0).use { cutter ->
                (body - cutter).use { result ->
                    result.checked()
                    check(result.volume > 0 && result.volume < body.volume)
                    c.progress(0.75, "Checking the cut and writing its mesh")
                    result.toTriMesh().saveObj(c.file("solid.obj"))
                    val facts = mapOf("volumeMm3" to result.volume, "triangleCount" to result.triangleCount, "widthMm" to width)
                    c.file("measurements.json").writeText(json.writeValueAsString(facts))
                    StudyBuildProduct(listOf("solid.obj", "measurements.json"), facts)
                }
            }
        }
    },
    StudyBuildStage("readback", "Check exported geometry", "obj-readback-v1", dependencies = listOf("solid")) { c ->
        c.progress(0.3, "Reading the exact upstream mesh")
        triMeshFromObj(c.dependencyFile("solid", "solid.obj")).toSolid().use { result ->
            result.checked()
            val original = json.readTree(c.dependencyFile("solid", "measurements.json"))["volumeMm3"].asDouble()
            check(kotlin.math.abs(result.volume-original) < .1)
            val facts = mapOf("volumeMm3" to result.volume, "differenceMm3" to kotlin.math.abs(result.volume-original))
            c.file("readback.json").writeText(json.writeValueAsString(facts))
            StudyBuildProduct(listOf("readback.json"), facts)
        }
    }
)) { mapOf("width" to owner.getPropertyRaw("verificationWidth", "120").toString()) }
showStudyBuilds("proof")
println("STUDY_BUILDS_READY")
'''


async def verify(port, output):
    output.mkdir(parents=True, exist_ok=True)
    logger = SessionLogger(output / 'logs', sys.argv)
    client = FieldBackdoor(port=port, workspace=None, default_timeout=120, logger=logger)
    evidence = {'checks': [], 'calls': [], 'passed': False}

    def check(condition, label):
        evidence['checks'].append({'check': label, 'passed': bool(condition)})
        print(label, bool(condition), flush=True)
        if not condition:
            raise AssertionError(label)

    async def tool(tool_name, **arguments):
        raw = await client.ai_tool_call(name=tool_name, arguments=arguments, timeout=120)
        evidence['calls'].append({'name': tool_name, 'arguments': arguments, 'result': raw.get('result')})
        if not raw.get('ok'):
            raise RuntimeError(raw)
        return raw['result']

    async def browser(script):
        raw = await client.playwright_input(action='pageEval', script=script, script_mode='body', timeout=60)
        if not raw.get('ok'):
            raise RuntimeError(raw)
        return raw.get('result')

    try:
        await client.ensure_server()
        await client.connect()
        await client.open_document('stage-build-proof-' + uuid.uuid4().hex[:8], scratch=False, timeout=120, keep_running=True)
        created = await tool('NewBoxRequest', **{'name': 'Live geometry stages'})
        uid = created['uid']
        await client.refresh_snapshot(timeout=30)
        await client.open_playwright(timeout=90)
        run = await tool('RunBoxRequest', box=uid, code=SOURCE, timeoutSeconds=120)
        check(run['success'], 'box registers real geometry stages and their controls')
        listed = await tool('ListStudyBuildsRequest')
        check(any(r['boxId'] == uid for r in listed['details']['runners']), 'AI4 discovers the document runner')

        async def stages():
            return (await tool('InspectStudyBuildsRequest', boxId=uid, name='proof'))['details']['stages']

        async def start(stage):
            state = next(s for s in await stages() if s['id'] == stage)
            accepted = await tool('StartStudyBuildRequest', boxId=uid, name='proof', stageId=stage, expectedFingerprint=state['fingerprint'])
            check(accepted['success'], f'{stage} build accepted with frozen inputs')
            return accepted['details']['job']['id']

        async def await_job(job_id, status=None):
            for _ in range(300):
                result = await tool('InspectStudyBuildsRequest', boxId=uid, name='proof', jobId=job_id)
                job = result['details']['job']
                if status is not None and job['status'] == status:
                    return job
                if status is None and job['status'] not in ('QUEUED', 'RUNNING'):
                    return job
                await asyncio.sleep(.1)
            raise TimeoutError(job)

        async def set_property(name, value):
            return await tool('SetBoxPropertyRequest', box=uid, property=name, value=value)

        ready = await browser("for(let i=0;i<200;i++){if(document.querySelector('[data-study-builds=proof] button'))return true;await new Promise(r=>setTimeout(r,50));}return false;")
        check(ready, 'stage card appears inside Field')
        await set_property('verificationHold', 'true')
        clicked = await client.playwright_input(action='clickSelector', selector='[data-study-builds=proof] [data-stage=solid] button', timeout=30)
        check(clicked.get('ok'), 'real Build button accepts a pointer click')
        first = None
        for _ in range(100):
            first = next(s for s in await stages() if s['id'] == 'solid')['latest']
            if first:
                break
            await asyncio.sleep(.05)
        if not first:
            raise RuntimeError('Button did not queue a job')
        await await_job(first['id'], 'RUNNING')
        progress = await browser("for(let i=0;i<100;i++){const p=document.querySelector('[data-study-builds=proof] progress');if(p&&p.value>0)return {value:p.value,text:p.closest('section').textContent};await new Promise(r=>setTimeout(r,50));}return null;")
        check(progress and progress['value'] == .1, 'live progress reports the actual worker checkpoint')
        await set_property('verificationHold', 'false')
        built = await await_job(first['id'])
        check(built['status'] == 'SUCCEEDED' and built['facts']['volumeMm3'] > 0, 'native Boolean geometry completes and retains measurements')
        downstream = await await_job(await start('readback'))
        check(downstream['status'] == 'SUCCEEDED' and downstream['facts']['differenceMm3'] < .1, 'downstream stage verifies the exact exported native mesh')
        check(all(s['current'] for s in await stages()), 'both stages are current after their checks')
        await browser("for(let i=0;i<100;i++){const s=[...document.querySelectorAll('[data-study-builds=proof] .study-build-state')];if(s.length===2&&s.every(s=>s.textContent==='Current'))return true;await new Promise(r=>setTimeout(r,50));}throw new Error('Current results were not shown');")
        shot = await client.screenshot_box(uid=uid, path=str(output / 'current-stages.png'), padding=12, pan_into_view=True, open_outputs=True, timeout=60)
        evidence['currentScreenshot'] = shot

        await set_property('verificationHold', 'true')
        old = await start('solid')
        await await_job(old, 'RUNNING')
        await set_property('verificationWidth', '140')
        check(all(s['current'] is None for s in await stages()), 'an authored change invalidates both derived results')
        changed_card = await browser("for(let i=0;i<100;i++){const s=document.querySelector('[data-study-builds=proof] [data-stage=solid] .study-build-state');if(s?.textContent==='Building an earlier design')return true;await new Promise(r=>setTimeout(r,50));}return false;")
        check(changed_card, 'the card identifies an in-flight build of an earlier design')
        await set_property('verificationHold', 'false')
        obsolete = await await_job(old)
        check(obsolete['status'] == 'OBSOLETE' and obsolete['inputs']['width'] == '120', 'late old-input geometry is retained but never published')
        good = await await_job(await start('solid'))
        check(good['status'] == 'SUCCEEDED' and good['facts']['widthMm'] == 140, 'rebuild uses the new dimensions')

        await set_property('verificationWidth', '10')
        failed = await await_job(await start('solid'))
        check(failed['status'] == 'FAILED' and failed['problem']['code'] == 'width-too-small', 'impossible geometry returns an actionable structured error')
        style = await browser("for(let i=0;i<100;i++){const p=document.querySelector('[data-study-builds=proof] .study-build-error');if(p)return {text:p.textContent,color:getComputedStyle(p).color,select:getComputedStyle(p).userSelect};await new Promise(r=>setTimeout(r,50));}return null;")
        check(style and style['color'] == 'rgb(255, 105, 105)' and style['select'] == 'text', 'Field shows a red copyable error message')
        evidence['errorScreenshot'] = await client.screenshot_box(uid=uid, path=str(output / 'failed-stage.png'), padding=12, pan_into_view=True, open_outputs=True, timeout=60)

        await set_property('verificationWidth', '160')
        error_cleared = await browser("for(let i=0;i<100;i++){if(!document.querySelector('[data-study-builds=proof] .study-build-error'))return true;await new Promise(r=>setTimeout(r,50));}return false;")
        check(error_cleared, 'a failure from earlier inputs is not displayed as the current error')
        await set_property('verificationHold', 'true')
        cancelling = await start('solid')
        await await_job(cancelling, 'RUNNING')
        await tool('CancelStudyBuildRequest', boxId=uid, name='proof', jobId=cancelling)
        check((await await_job(cancelling))['status'] == 'CANCELLED', 'agent cancellation reaches a terminal state without publication')
        await set_property('verificationHold', 'false')
        rerun = await tool('RunBoxRequest', box=uid, code=SOURCE, timeoutSeconds=120)
        check(rerun['success'], 'rerunning the source safely reopens its persistent runner')
        historical = await await_job(first['id'])
        check(historical['status'] == 'SUCCEEDED' and len(historical['artifacts']) == 2, 'attempt history and artifact identities survive reopening')
        evidence.update(passed=True, sourceBox=uid)
    finally:
        (output / 'verification.json').write_text(json.dumps(evidence, indent=2))
        try:
            await client.close()
        finally:
            logger.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/study-builds')
    args = parser.parse_args()
    port = args.port if args.port is not None else select_automation_port(DEFAULT_AUTOMATION_PORT, explicit=False)
    asyncio.run(verify(port, args.output.resolve()))


if __name__ == '__main__':
    main()
