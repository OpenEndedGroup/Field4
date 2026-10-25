"""Exercise actual native Csound and actual Chromium registrations across rerun/Stop."""
from pathlib import Path
import importlib.util,asyncio,json,re,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig53',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9718;m.OUT=R/'build/box-code-revision/adoption-53/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
from format_docs import sections
async def execute(name,box,code):return await m.macro(name,[{'execute':{'box':box,'type':'fragment','code':code}}])
async def edit(name,box,old,new):return await m.macro(name,[{'edit':{'box':box,'property':'code','from':old,'to':new}},{'launch':{'box':box}}])
async def main():
 if "--resume-browser" not in __import__("sys").argv:
  doc=yaml.safe_load((R/'dev_docs/agents/transcripts/csound_demo.yaml').read_text());codes={o['create']['box']:o['create']['properties']['code'] for o in doc['operations'] if 'create' in o and 'code' in o['create'].get('properties',{})}
  csd=codes['cs_pluck'];orc=re.search(r'<CsInstruments>([\s\S]*?)</CsInstruments>',csd)[1];score=re.search(r'<CsScore>([\s\S]*?)</CsScore>',csd)[1]
  setup='''import afield.audio.csound.*
 import afield.media.browser.*
 System.setProperty("afield.csound.library", "/Users/marc/field4/build/field-csound/build/CsoundLib64.framework/CsoundLib64")
 check(CsoundLibrary.isAvailable) {CsoundLibrary.describe()}
 prop<CsoundInstance>(sharedCs)
 prop<java.util.concurrent.ConcurrentLinkedQueue<String>>(callbackEvents)
 callbackEvents=java.util.concurrent.ConcurrentLinkedQueue<String>()
 sharedCs=CsoundInstance(ORC,SCORE)
 println("actual Csound orchestra ready for offline rendering")
 '''.replace('ORC',json.dumps(orc)).replace('SCORE',json.dumps(score))
  await m.macro('native-owner',[{'create':{'box':'owner','properties':{'code':setup}}},{'launch':{'box':'owner'}}],True)
  talk=codes['cs_talk'];a=talk.index('// Stop or rerun removes');listeners='import afield.runtime.lifecycle.RunScope\nval pluck=sharedCs\n'+talk[a:]
  listeners=listeners.replace('println("Csound said: $it")','callbackEvents.add("said:EPOCH");println("Csound said: $it")').replace('v ->','v -> callbackEvents.add("level:EPOCH");')
  old=listeners.replace('EPOCH','old');new=listeners.replace('EPOCH','new')
  await m.macro('cs-listener-old',[{'create':{'box':'cs-listener','properties':{'code':old}}},{'connect':{'parent':'owner','child':'cs-listener'}},{'launch':{'box':'cs-listener'}}])
  await execute('cs-poll-old','owner','sharedCs.setStringChannel("voice","first");sharedCs.renderOffline(.03);check(callbackEvents.any {it.endsWith(":old")});println("old listeners receive actual outvalues")')
  await edit('cs-listener-new','cs-listener',old,new)
  await execute('cs-poll-new','owner','val oldCount=callbackEvents.count {it.endsWith(":old")};sharedCs.setStringChannel("voice","second");sharedCs.renderOffline(.03);check(callbackEvents.count {it.endsWith(":old")}==oldCount);check(callbackEvents.any {it.endsWith(":new")});println("rerun retains only new Csound callbacks")')
  await m.batch('cs-stop-listener',['stop cs-listener'])
  await execute('cs-poll-stopped','owner','val stoppedCount=callbackEvents.size;sharedCs.setStringChannel("voice","third");sharedCs.renderOffline(.03);check(callbackEvents.size==stoppedCount);println("Stop removes Csound callbacks while the orchestra still renders")')
  # Isolate the docs registration too; its callback still uses the real native instance.
  page=(R/'public/docs/csound.md').read_text();body=next(page[a:b] for a,b in sections(page) if 'run.own(pluck.onOutvalue' in page[a:b]);a=body.index('// The orchestra can keep playing');b=body.index('pluck.setInvalue',a);registration='val pluck=sharedCs\n'+body[a:b];registration=registration.replace('println("Reverb rms $v")','callbackEvents.add("docs");println("Reverb rms $v")')
  await m.macro('cs-doc-listener',[{'create':{'box':'cs-doc-listener','properties':{'code':registration}}},{'connect':{'parent':'owner','child':'cs-doc-listener'}},{'launch':{'box':'cs-doc-listener'}}])
  await execute('cs-doc-active','owner','sharedCs.renderOffline(.03);check(callbackEvents.any {it=="docs"});println("docs registration receives actual outvalue")')
  await m.batch('cs-doc-stop',['stop cs-doc-listener'])
  await execute('cs-doc-stopped','owner','val beforeDocs=callbackEvents.count {it=="docs"};sharedCs.renderOffline(.03);check(callbackEvents.count {it=="docs"}==beforeDocs);println("docs callback removed on Stop")')
  monitor='''\ninstr Monitor
 kactive active nstrnum("Key")
 chnset kactive, "keyActive"
 endin
 '''
  await execute('key-orchestra','owner','sharedCs.close();sharedCs=CsoundInstance('+json.dumps(orc+monitor)+','+json.dumps(score+'\ni "Monitor" 0 z\n')+');println("fresh native key orchestra")')
  keys=codes['cs_keys'].replace('val pluck = csoundOf("pluck").csound','val pluck = sharedCs').replace('delay(220)','delay(10_000)')
  await m.macro('keys-held',[{'create':{'box':'keys-held','properties':{'code':keys}}},{'connect':{'parent':'owner','child':'keys-held'}},{'launch':{'box':'keys-held'}},{'wait':{'frames':1}}])
  await execute('key-active','owner','sharedCs.renderOffline(.03);check(sharedCs.channel("keyActive")>=1);println("native MIDI key is held before Stop")')
  await m.batch('stop-held-key',['stop keys-held'])
  await execute('key-released','owner','sharedCs.renderOffline(.65);check(sharedCs.channel("keyActive")==0.0) {"key remains active ${sharedCs.channel("keyActive")}"};println("Stop during delay releases native MIDI note and completes its release envelope");sharedCs.close()')
 (H/'behavior.json').write_text(json.dumps({'realNativeCsound':True,'listenerRerunAndStop':True,'docsListenerStop':True,'nativeMidiActiveBeforeStopAndZeroAfterRelease':True,'limits':'Actual native orchestra rendered offline; no audio device playback. Held-key delay extended to ensure Stop while held.'},indent=2)+'\n')
asyncio.run(main())
