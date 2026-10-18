"""Run the page's index, query and placement fragments in one actual box."""
from pathlib import Path
import asyncio,importlib.util,json,re,sys,wave,numpy as np
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;OUT=R/'build/box-code-revision/adoption-43'
s=importlib.util.spec_from_file_location('rig43',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9713;m.OUT=OUT/'corpus-runtime';m.OUT.mkdir(parents=True,exist_ok=True)
p=OUT/'readability43-second.wav'
if not p.exists():
 sr=48000;t=np.arange(sr*9)/sr;signal=.3*np.sin(2*np.pi*247*t)+.2*np.sin(2*np.pi*370*t)+.1*np.sin(2*np.pi*494*t)
 with wave.open(str(p),'wb') as f:f.setnchannels(1);f.setsampwidth(2);f.setframerate(sr);f.writeframes((signal*32767).astype('<i2').tobytes())
async def main():
 text=(R/'public/docs/audio_ai_corpus_microscope.md').read_text();codes=[f[1] for f in re.finditer(r'```diffstatic \{data-lang=kotlin\}\n([\s\S]*?)\n```',text)];assert len(codes)==3
 index=codes[0].replace('FieldAudioClient()','FieldAudioClient("http://127.0.0.1:18765")')
 old='["*Nancy*", "*Candlestick Park*"].map { client.assetByFilename(it) }'
 replacement='['+','.join(json.dumps(str(q)) for q in [OUT/'readability43-test.wav',p])+'].map { client.importWav(java.nio.file.Path.of(it)) }'
 assert old in index;index=index.replace(old,replacement).replace('runAsync<AudioAsset>(', 'val indexExercise = runAsync<AudioAsset>(',1)
 index=index.replace('onError = { failure ->','onError = { failure -> display("index", "Index failed");',1)
 await m.program('corpus-index',index)
 await m.batch('corpus-index-ready',['wait-output subject --channel index --contains Index --timeout 180'])
 await m.macro('corpus-index-proof',[{'execute':{'box':'subject','property':'code','type':'fragment','code':'check(indexExercise.future.join().id == nancy.id)\nprintln("real corpus index ready: " + nancy.id)'}}])
 query=codes[1].replace('startSeconds = 18.0','startSeconds = 0.0').replace('runAsync<CorpusPreview>(', 'val queryExercise = runAsync<CorpusPreview>(',1)
 query=query.replace('display("microscope", corpusMicroscopeHtml(preview, canonical))','display("microscope", corpusMicroscopeHtml(preview, canonical))\n        display("query done", "query completed")').replace('onError = { failure ->','onError = { failure -> display("query done", "query failed");',1)
 await m.macro('corpus-query',[{'execute':{'box':'subject','property':'code','type':'fragment','code':query}}])
 await m.batch('corpus-query-ready',['wait-output subject --channel "query done" --contains query --timeout 180'])
 await m.macro('corpus-query-and-placement-proof',[{'execute':{'box':'subject','property':'code','type':'fragment','code':'''check(queryExercise.future.join() == preview)
check(preview.neighbours.isNotEmpty())
check(preview.neighbours.all { it.segment.assetId != nancy.id })
check(canonical.assetId == nancy.id)
'''+codes[2]+'''
println("real corpus neighbour count: " + preview.neighbours.size)
'''}}])
asyncio.run(main())
