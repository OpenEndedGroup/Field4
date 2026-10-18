"""Exercise actual RTP packets, peer synchronization, timed notes, Stop and rerun release."""
from pathlib import Path
import asyncio,importlib.util,json,sys,yaml,os
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig42midi',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=int(os.environ.get('FIELD_TEST_PORT','9712'));m.OUT=R/'build/box-code-revision/adoption-42/midi';m.OUT.mkdir(parents=True,exist_ok=True)
sys.path.insert(0,str(H.parent/'box_code_adoption_batch_7'));from format_docs import sections
from docs_hooks import extract_static_code
async def fragment(name,code):return await m.macro(name,[{'execute':{'box':'setup','property':'code','type':'fragment','code':code}}])
CURRENT_PLAY=None
async def launch(name,code,wait):
 global CURRENT_PLAY
 ops=([{'create':{'box':'play','properties':{'code':code}}},{'connect':{'parent':'setup','child':'play'}}] if CURRENT_PLAY is None else [{'edit':{'box':'play','property':'code','from':CURRENT_PLAY,'to':code}}] if CURRENT_PLAY!=code else [])
 await m.macro(name,ops+[{'launch':{'box':'play'}},{'await':{}}])
 CURRENT_PLAY=code
 await m.batch(name+'-ready',[wait])
SETUP_CREATED=False
async def run():
 global SETUP_CREATED,CURRENT_PLAY
 setup='''import afield.io.midi.rtp.*
prop<BoxRtpMidi>(incoming)=rtpMidi("Readability42 receiver",port=0,advertise=false)
prop<BoxRtpMidi>(outgoing)=rtpMidi("Readability42 sender",port=0,advertise=false)
prop<MutableList<String>>(noteEvents)=mutableListOf()
prop<String>(testPhase)="setup"
val sender=outgoing
val receiver=incoming
receiver.onNote { pitch,velocity,channel ->
    noteEvents.add("${System.nanoTime()},$pitch,$velocity,$channel")
    display("notes","$testPhase:${if(velocity>0) "on" else "off"}:$pitch:${noteEvents.size}")
}
sender.invite("127.0.0.1",receiver.port)
frames {
    val deadline=System.nanoTime()+12_000_000_000L
    while(sender.peers.none { it.connected && it.roundTripStatsMs!=null }) {
        check(System.nanoTime()<deadline)
        frame()
    }
    display("ready","connected and synchronized")
}
'''
 await m.macro('setup',[{'create':{'box':'setup','properties':{'code':setup}}},{'launch':{'box':'setup'}},{'await':{}}],True)
 SETUP_CREATED=True
 await m.batch('handshake',['wait-output setup --channel ready --contains "connected and synchronized" --timeout 20'])
 folder=R/'public/docs/assets/rtp_midi_demo'
 def adapt(code):
  return code.replace('rtpMidi("Field peer", port = 5006, advertise = false)','outgoing').replace('rtpMidi("Field")','incoming').replace('other.invite("127.0.0.1", 5004)','other.invite("127.0.0.1", incoming.port)')
 peer=adapt((folder/'rtpm-peer_code_.kt').read_text())
 await m.macro('source-peer',[{'create':{'box':'peer','properties':{'code':peer}}},{'connect':{'parent':'setup','child':'peer'}},{'launch':{'box':'peer'}},{'await':{}}])
 await m.batch('source-peer-ready',['wait-output peer --channel peers --contains "round trip" --timeout 20'])
 page=(R/'public/docs/rtp_midi.md').read_text();states=[page[a:b] for a,b in sections(page) if 'finally' in page[a:b]];assert len(states)==1
 docs='val midi=outgoing\n'+extract_static_code(lines=states[0].splitlines());docs=docs.rstrip();assert docs.endswith('}')
 docs=docs[:-1]+'\n    display("test-finished","docs sequence completed")\n}\n'
 demo=adapt((folder/'rtpm-play_code_.kt').read_text())
 metrics=[]
 for surface,code in [('demo',demo),('docs',docs)]:
  await fragment(surface+'-clear','noteEvents.clear();testPhase="'+surface+'-normal"')
  wait='wait-output play --channel '+('received --contains "held notes: 0"' if surface=='demo' else 'test-finished --contains "docs sequence completed"')+' --timeout 20'
  await launch(surface+'-normal',code,wait)
  target=m.OUT/(surface+'-normal.csv')
  await fragment(surface+'-normal-proof','''check(incoming.held.isEmpty())
check(noteEvents.size==8) { noteEvents }
java.nio.file.Files.writeString(java.nio.file.Path.of(PATH),noteEvents.joinToString("\\n"))
println("four real note-on/off pairs, none held")'''.replace('PATH',json.dumps(str(target))))
  rows=[[int(x) for x in line.split(',')] for line in target.read_text().splitlines()];assert [r[1] for r in rows]==[60,60,64,64,67,67,72,72];assert [r[2] for r in rows]==[90,0]*4
  holds=[(rows[i+1][0]-rows[i][0])/1e6 for i in range(0,8,2)];assert all(120<v<500 for v in holds),holds
  metrics.append({'surface':surface,'noteHoldsMs':holds,'sourceDelayMs':150})
  # Keep the teaching delay unchanged. A longer private hold makes cancellation observable.
  fixture=code.replace('delay(150)','delay(10000)');assert fixture!=code
  for operation in ['stop','rerun']:
   phase=surface+'-'+operation
   await fragment(phase+'-clear','noteEvents.clear();testPhase="'+phase+'"')
   await launch(phase,fixture,f'wait-output setup --channel notes --contains "{phase}:on:60:1" --timeout 20')
   await fragment(phase+'-held',f'check(incoming.held.containsKey(0 to 60));println("{phase}: actual first note held before cancellation")')
   if operation=='rerun':
    await m.batch(phase+'-relaunch',['launch play',f'wait-output setup --channel notes --contains "{phase}:on:60:3" --timeout 20'])
    await fragment(phase+'-held-again',f'check(incoming.held.containsKey(0 to 60));println("{phase}: new run owns the held note")')
   count=2 if operation=='stop' else 4
   await m.batch(phase+'-stop',['stop play',f'wait-output setup --channel notes --contains "{phase}:off:60:{count}" --timeout 20'])
   await fragment(phase+'-released',f'''check(incoming.held.isEmpty())
check(noteEvents.size=={count}) {{ noteEvents }}
check(noteEvents.map {{ it.split(",")[2].toInt() }}==listOf({','.join(['90','0']*(count//2))}))
java.nio.file.Files.writeString(java.nio.file.Path.of({json.dumps(str(m.OUT/(phase+'-order.csv')))}),noteEvents.joinToString("\\n"))
println("{phase}: every started note released")''')
 (m.OUT/'timing.json').write_text(json.dumps(metrics,indent=2)+'\n')
async def main():
 try:await run()
 finally:
  if SETUP_CREATED:
   if CURRENT_PLAY is not None:await m.batch('stop-play-cleanup',['stop play','wait-running play --state stopped --timeout 5'])
   await fragment('close-owned-sessions','afield.io.midi.rtp.RtpMidiSessions.close(outgoing.port);afield.io.midi.rtp.RtpMidiSessions.close(incoming.port);println("only the two owned sessions closed")')
   await m.batch('stop-setup',['stop peer','stop setup'])
asyncio.run(main())
