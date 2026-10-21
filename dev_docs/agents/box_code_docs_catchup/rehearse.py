"""Exercise the actual published evolution body and native geometry in a private Field session.

Start tools/field_session.py serve on the selected port first. No fake render or synthetic result
is used; parameter changes in the evolution fixture keep real work alive long enough to Stop.
"""
from pathlib import Path
import argparse
import asyncio
import hashlib
import html
import importlib.util
import json
import re
import shlex
import subprocess
import sys
import time
import yaml

ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent
OUT=ROOT/'build/box-code-revision/docs-catchup/runtime'
sys.path.insert(0,str(ROOT/'tools'))
from field_session import send_request,socket_path_for
spec=importlib.util.spec_from_file_location('catchup_sections',HERE.parent/'box_code_adoption_batch_7/format_docs.py');docs=importlib.util.module_from_spec(spec);spec.loader.exec_module(docs)
parser=argparse.ArgumentParser();parser.add_argument('--port',type=int,default=9742);parser.add_argument('--resume',action='store_true');parser.add_argument('--page',action='append',default=[]);parser.add_argument('--transcript-ownership',action='store_true');parser.add_argument('--notation',action='store_true');args=parser.parse_args()
OUT.mkdir(parents=True,exist_ok=True)
checks=[]
REFERENCE_PROPERTY="champion"

def bodies(text):
    return [re.sub(r'^[ \t]*:::(?:fold[^\n]*|endfold|)[ \t]*$','',text[a:b],flags=re.M) for a,b in docs.sections(text)]

def html_values(value):
    if isinstance(value,dict):
        for key,child in value.items():
            if key=='html' and isinstance(child,str):yield child
            else:yield from html_values(child)
    elif isinstance(value,list):
        for child in value:yield from html_values(child)

async def batch(name,commands,reset=False,expect_ok=True):
    result=await send_request(socket_path_for(args.port),dict(commands=commands,resetDoc=reset,scratch=True,timeout=180),timeout=240)
    (OUT/(name+'.json')).write_text(json.dumps(result,indent=2)+'\n')
    assert result.get('ok')==expect_ok,(name,str(result)[-1800:])
    return result

async def create(name,code,extra=None):
    ops=[dict(create=dict(box='subject',name='catchup-subject',frame=dict(x=0.,y=0.,w=600.,h=100.),properties=dict(code=code)))]
    if extra:ops+=extra
    path=OUT/(name+'.yaml');path.write_text(yaml.safe_dump(dict(operations=ops),sort_keys=False))
    return await batch(name,['macro write catchup --file '+shlex.quote(str(path)),'macro run catchup'],True)

async def probe(name,code):
    code=code.replace('getValue("champion")', 'getValue("'+REFERENCE_PROPERTY+'")')
    path=OUT/(name+'-fragment.yaml')
    path.write_text(yaml.safe_dump(dict(operations=[dict(execute=dict(box='probe',property='code',type='fragment',code=code))]),sort_keys=False))
    macro='probe_'+re.sub(r'[^A-Za-z0-9_]','_',name)
    return await batch(name,['macro write '+macro+' --file '+shlex.quote(str(path)),'macro run '+macro])

async def ready(name,expression):
    deadline=time.monotonic()+60
    attempt=0
    while time.monotonic()<deadline:
        result=await probe(f'{name}-{attempt}',f'println("CATCHUP_READY=" + ({expression}))')
        if any('CATCHUP_READY=true' in h for h in html_values(result)):return
        attempt+=1;await asyncio.sleep(.3)
    raise AssertionError(name+' did not become ready')

async def ownership(code=None):
    if code is None:
        text=(ROOT/'public/docs/evolving_creatures.md').read_text()
        code=next(c for c in bodies(text) if 'AtomicReference<CreatureEvaluation?>' in c)
    fixture=re.sub(r'(generations\s*=\s*)\d+',r'\g<1>100000',code,count=1)
    fixture=re.sub(r'(measuredCycles\s*=\s*)\d+',r'\g<1>1',fixture,count=1)
    assert fixture!=code
    extra=[dict(create=dict(box='probe',name='catchup-probe',frame=dict(x=700.,y=0.,w=300.,h=100.),properties=dict(code='')))]
    await create('ownership-setup',fixture,extra)
    try:
        await batch('ownership-launch',['launch subject'])
        await probe('retain-run-one','''val subjectBox = inside.boxes.values.first { it.id == "subject" }
val firstChampion = subjectBox.ki.context.vars.getValue("champion").value as java.util.concurrent.atomic.AtomicReference<*>
println("CATCHUP_RETAINED")''')
        await ready('first-champion','firstChampion.get() != null')
        await batch('active-worker',['lifecycle-census subject --expect-at-least jobs=1'])
        await batch('stop-run-one',['stop subject','lifecycle-census subject --expect-stopped --timeout 10'])
        await probe('snapshot-stopped','val stoppedChampion = firstChampion.get(); check(stoppedChampion != null)')
        await asyncio.sleep(.5)
        await probe('stopped-publication','check(firstChampion.get() === stoppedChampion); println("CATCHUP_STOPPED")')
        await batch('relaunch',['launch subject'])
        await probe('retain-run-two','''val secondChampion = subjectBox.ki.context.vars.getValue("champion").value as java.util.concurrent.atomic.AtomicReference<*>
check(secondChampion !== firstChampion)
check(firstChampion.get() === stoppedChampion)''')
        await ready('second-champion','secondChampion.get() != null')
        await probe('old-run-remains-retired','check(firstChampion.get() === stoppedChampion)')
        await batch('stop-run-two',['stop subject','lifecycle-census subject --expect-stopped --timeout 10'])
        checks.append('Actual evolution publishes champions, stops its running jobs, freezes old publication, and relaunches into a distinct holder')
        # Fail inside the real worker, after go has registered it, and inspect the editor error route.
        invalid=fixture.replace('CreatureEvolution.evolveSuspend(config)', 'CreatureEvolution.evolveSuspend(config.copy(populationSize = 0))')
        path=OUT/'invalid-worker.kt';path.write_text(invalid)
        await batch('worker-failure-launch',['set subject code --file '+shlex.quote(str(path)),'launch subject'])
        result=await batch('worker-error-output',['sleep 1','output subject'])
        assert 'populationSize must be > 0' in html.unescape(json.dumps(result)),'Worker error did not reach box output'
        checks.append('Actual worker validation failure reaches the box error output')
    finally:
        await batch('ownership-final-stop',['stop subject','lifecycle-census subject --expect-stopped --timeout 10'])

async def native_equivalence():
    baseline=json.loads((HERE/'scope.json').read_text())['baseline']
    pages=[('contour_trees.md','check(tree.nodes.isNotEmpty()); println("CATCHUP_GEOMETRY=" + listOf(tree.nodes.size, tree.arcs.size, dots.nodes.size, field.normalized().colormap("viridis").drawFLines([dots], scale = 2.0, color = Vec4(1f, 1f, 1f, 1f)).data.contentHashCode()))'),
           ('geometry_chains.md','check(tree.nodeCount > 0); println("CATCHUP_GEOMETRY=" + tree.toFLines().flatMap { it.nodes }.map { it.toOrNull }.toString())')]
    pages.append(('morse_smale.md','check(arcs.isNotEmpty()); check(pairs.isNotEmpty()); println("CATCHUP_GEOMETRY=" + arcs + pairs)'))
    for page,check in pages:
        if args.page and page not in args.page:continue
        old=subprocess.check_output(['git','show',baseline+':public/docs/'+page],cwd=ROOT,text=True)
        current=(ROOT/'public/docs'/page).read_text();values=[]
        for label,text in [('before',old),('after',current)]:
            code=bodies(text)[4 if page=='morse_smale.md' else 0]+'\n'+check
            await create(page+'-'+label,code)
            result=await batch(page+'-'+label+'-execute',['run subject'])
            output=[h[h.index('CATCHUP_GEOMETRY='):] for h in html_values(result) if 'CATCHUP_GEOMETRY=' in h]
            assert output,(page,label,'No geometry proof')
            values.append(output[-1].strip())
        assert values[0]==values[1],(page,'Native geometry/render changed',values)
        checks.append(page+': before/after native geometry and raster proof match')

def retained_ownership(source=None):
    if source is None:source=next(c for c in bodies((ROOT/'public/docs/evolving_creatures.md').read_text()) if 'AtomicReference<CreatureEvaluation?>' in c)
    fixture=re.sub(r'(generations\s*=\s*)\d+',r'\g<1>100000',source,count=1)
    fixture=re.sub(r'(measuredCycles\s*=\s*)\d+',r'\g<1>1',fixture,count=1)
    recorded=yaml.safe_load((OUT/'ownership-setup.yaml').read_text())['operations'][0]['create']['properties']['code']
    assert recorded==fixture,'Published evolution body changed after rehearsal'
    names=['ownership-launch','retain-run-one','first-champion-0','active-worker','stop-run-one','snapshot-stopped','stopped-publication','relaunch','retain-run-two','second-champion-0','old-run-remains-retired','stop-run-two','worker-failure-launch','worker-error-output','ownership-final-stop']
    results={name:json.loads((OUT/(name+'.json')).read_text()) for name in names}
    assert all(r.get('ok') for r in results.values()),'An earlier ownership command failed'
    assert any('CATCHUP_READY=true' in h for h in html_values(results['first-champion-0']))
    assert any('CATCHUP_READY=true' in h for h in html_values(results['second-champion-0']))
    assert 'populationSize must be > 0' in html.unescape(json.dumps(results['worker-error-output']))
    checks.extend(['Actual evolution publishes champions, stops its running jobs, freezes old publication, and relaunches into a distinct holder','Actual worker validation failure reaches the box error output'])

async def main():
    global OUT,REFERENCE_PROPERTY
    if args.notation:
        previous=json.loads((HERE/'runtime-verification.json').read_text())
        assert all(hashlib.sha256((ROOT/p).read_bytes()).hexdigest()==sha for p,sha in previous['sourceHashes'].items()),'Source changed since retained checks'
        checks.extend(previous['checks'])
        source=yaml.safe_load((ROOT/'dev_docs/agents/transcripts/notation_elements.yaml').read_text())
        operations=[op for op in source['operations'] if 'create' in op]
        operations += [dict(execute=dict(box=box,property='code')) for box in ['ne_elements','ne_score','ne_audit']]
        path=OUT/'notation.yaml';path.write_text(yaml.safe_dump(dict(operations=operations),sort_keys=False))
        result=await batch('notation-read-write-cut-audit',['macro write notation_catchup --file '+shlex.quote(str(path)),'macro run notation_catchup'],True)
        output=list(html_values(result))
        assert any('works' in text for text in output),'Audit produced no results'
        assert not any('BROKEN' in text for text in output),'Notation API audit reported a broken result'
        checks.append('Actual notation elements/score/audit bodies execute; all mark, frame, grouping, edit and intersection checks report works')
    elif args.transcript_ownership:
        previous=json.loads((HERE/'runtime-verification.json').read_text())
        assert all(hashlib.sha256((ROOT/p).read_bytes()).hexdigest()==sha for p,sha in previous['sourceHashes'].items()),'Source changed since retained checks'
        checks.extend(previous['checks'])
        path=ROOT/'dev_docs/agents/transcripts/evolving_water_bugs.yaml'
        record=yaml.safe_load(path.read_text());root_out=OUT
        REFERENCE_PROPERTY='latest'
        for op in record['operations']:
            if 'create' not in op:continue
            box=op['create'];OUT=root_out/box['box'];OUT.mkdir(parents=True,exist_ok=True)
            start=len(checks)
            if args.resume and (OUT/'worker-error-output.json').exists():retained_ownership(box['properties']['code'])
            else:await ownership(box['properties']['code'])
            for i in range(start,len(checks)):checks[i]=box['box']+': '+checks[i]
        OUT=root_out
    elif args.page:
        previous=json.loads((HERE/'runtime-verification.json').read_text())
        assert all(hashlib.sha256((ROOT/p).read_bytes()).hexdigest()==sha for p,sha in previous['sourceHashes'].items()),'Source changed since retained checks'
        checks.extend(previous['checks'])
    elif args.resume:
        retained_ownership()
        print('Retained successful exact-source ownership and error-routing checks',flush=True)
    else:await ownership()
    if not args.transcript_ownership and not args.notation:await native_equivalence()
    sources=json.loads((HERE/'scope.json').read_text())['paths']
    receipt=dict(checks=checks,sourceHashes={p:hashlib.sha256((ROOT/p).read_bytes()).hexdigest() for p in sources},fixtureOverrides=dict(generations=100000,measuredCycles=1),serverPort=args.port)
    (HERE/'runtime-verification.json').write_text(json.dumps(receipt,indent=2)+'\n')
    print(json.dumps(dict(passed=len(checks),checks=checks)),flush=True)

if __name__=='__main__':asyncio.run(main())
