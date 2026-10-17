"""Run the actual streamer example into a private UDP receiver and decode its real video."""
from pathlib import Path
import asyncio,importlib.util,socket,struct,json,re,subprocess
HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('r',HERE/'rehearse.py');r=importlib.util.module_from_spec(spec);spec.loader.exec_module(r);m=r.m;ROOT=r.ROOT
async def main():
 sock=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);sock.setsockopt(socket.SOL_SOCKET,socket.SO_RCVBUF,4*1024*1024);sock.bind(('127.0.0.1',0));sock.setblocking(False);port=sock.getsockname()[1]
 frames={};complete={};packets=0;enough=asyncio.Event();target=6
 async def collect():
  nonlocal packets
  loop=asyncio.get_running_loop()
  while True:
   data,_=await loop.sock_recvfrom(sock,65536);packets+=1
   version,flags,codec,_,seq,index,count,pts=struct.unpack('>BBBBIHHQ',data[:20]);assert version in (1,2) and codec==0
   size=36 if version==2 else 20
   f=frames.setdefault(seq,{'count':count,'parts':{},'flags':flags,'pts':pts});assert count==f['count'];f['parts'][index]=data[size:]
   if len(f['parts'])==count:complete[seq]=b''.join(f['parts'][i] for i in range(count))
   if len(complete)>=target:enough.set()
 task=asyncio.create_task(collect())
 try:
  renderer=(ROOT/'public/docs/assets/canonical_graphics/611072da-1af6-4d2d-85bf-07a9ba191aca_code_.kt').read_text().replace('Options.enableHDR = true','Options.enableHDR = false').replace('Window("Field")','Window("Private streamer verification")')
  await m.macro('stream_renderer',[{'create':{'box':'renderer','name':'Private streamer renderer','frame':{'x':0.,'y':0.,'w':400.,'h':100.},'properties':{'code':renderer}}},{'launch':{'box':'renderer'}},{'await':{}}],True)
  prefix=ROOT/'public/docs/assets/canonical_graphics/8323570b-87db-4623-95a2-bd61c7d0f6f7'
  code=Path(str(prefix)+'_code_.kt').read_text().replace('127.0.0.1:9001',f'127.0.0.1:{port}')
  props={'code':code,'vertex':Path(str(prefix)+'_vertex_.glsl').read_text(),'fragment':Path(str(prefix)+'_fragment_.glsl').read_text()}
  await m.macro('stream_setup',[{'create':{'box':'sender','name':'Actual streamer example','frame':{'x':500.,'y':0.,'w':400.,'h':100.},'properties':props}},{'connect':{'parent':'renderer','child':'sender'}},{'launch':{'box':'sender'}},{'await':{}}])
  await m.batch('stream_frames',['wait-native-renderer sender --frames 45 --timeout 60'])
  await asyncio.wait_for(enough.wait(),20)
  async def state(label):
   result=await r.fragment(label,'sender','check(streamer.streaming); println("STREAM_ID "+System.identityHashCode(streamer)); println("FRAMES "+streamer.framesEncoded)')
   text='\n'.join(o.get('html','') for x in result['results'] for o in x.get('data',{}).get('output',[]))
   return int(re.search(r'STREAM_ID (\d+)',text)[1]),int(re.search(r'FRAMES (\d+)',text)[1])
  before=await state('stream_before_rerun')
  target=len(complete)+4;enough.clear()
  await m.batch('stream_rerun',['launch sender','wait-native-renderer sender --frames 45 --timeout 60'])
  await asyncio.wait_for(enough.wait(),20)
  after=await state('stream_after_rerun');assert before[0]==after[0] and after[1]>before[1],(before,after)
  await r.fragment('stream_cleanup','sender','renderer.renderList.others.remove("target", target)\nstreamer.stop()\ncheck(!streamer.streaming)\nprintln("native sender stopped")')
  await m.batch('stream_stop',['stop sender','lifecycle-census sender --expect-stopped','stop renderer','lifecycle-census renderer --expect-stopped'])
  first=min(seq for seq in complete if frames[seq]['flags']&1)
  seqs=sorted(seq for seq in complete if seq>=first);video=m.OUT/'actual-stream.hevc';video.write_bytes(b''.join(complete[s] for s in seqs))
  subprocess.run(['ffmpeg','-y','-loglevel','error','-f','hevc','-i',str(video),'-vf','select=eq(n\\,2)','-frames:v','1',str(m.OUT/'actual-stream.png')],check=True)
  from PIL import Image,ImageStat
  image=Image.open(m.OUT/'actual-stream.png').convert('RGB');stats=ImageStat.Stat(image);assert image.size==(1280,720);assert max(stats.stddev)>10,(stats.mean,stats.stddev)
  (m.OUT/'stream-verification.json').write_text(json.dumps({'privateUdpPort':port,'packets':packets,'completeFrames':len(complete),'firstKeyframe':first,'identityBefore':before[0],'identityAfter':after[0],'encodedBefore':before[1],'encodedAfter':after[1],'decodedSize':image.size,'decodedMean':stats.mean,'decodedStddev':stats.stddev,'sourceChangesForFixture':['private loopback UDP port','private renderer title and SDR window']},indent=2)+'\n')
  print('Actual native stream decoded; cached sender survives rerun and stops cleanly',flush=True)
 finally:
  task.cancel();sock.close()
if __name__=='__main__':asyncio.run(main())
