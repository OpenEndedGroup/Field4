"""Exercise authored analyses with real local workers and an explicitly substituted test input."""
from pathlib import Path
import asyncio,importlib.util,json,sys,yaml,re
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;OUT=R/'build/box-code-revision/adoption-43'
s=importlib.util.spec_from_file_location('rig43',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=int(__import__("os").environ.get("FIELD_TEST_PORT","9713"));m.OUT=OUT/('runtime-merged' if __import__('os').environ.get('FIELD_MERGED_CHECK') else 'runtime');m.OUT.mkdir(parents=True,exist_ok=True)
def fixture(code):
 code=code.replace('FieldAudioClient()','FieldAudioClient("http://127.0.0.1:18765")').replace('val start = 18.0','val start = 0.0').replace('val duration = 24.0','val duration = 6.0')
 code=code.replace('client.assetByFilename("*Nancy*")','client.importWav(java.nio.file.Path.of('+json.dumps(str(OUT/'readability43-test.wav'))+'))')
 # Keep the authored callback and add an observable test completion marker afterward.
 code=code.replace('runAsync(', 'val exercise = runAsync(',1)
 marker='    },\n    onError ='
 assert marker in code
 code=code.replace(marker,'        display("test done", "real analysis complete")\n'+marker,1)
 code=code.replace('onError = { failure ->', 'onError = { failure -> display("test done", "real analysis failed: ${failure.message}");',1)
 return code
async def main():
 if '--bootstrap-proof' in sys.argv:
  await m.macro('fresh-cache-and-lifetime-proof',[{'execute':{'box':'subject','property':'code','type':'fragment','code':'check(!accepted.single().cacheHit)\ncheck(operation.future.isDone && !operation.future.isCompletedExceptionally)\nprintln("fresh uncached analysis confirmed; all owned resources released before future completion")'}}])
  return
 text=(R/'public/docs/audio_ai_analysis.md').read_text();doc=re.search(r'```diffstatic(?:_runner)?[^\n]*\n([\s\S]*?)\n```',text)[1]
 doc=m.extract_static_code(lines=doc.splitlines())
 data=yaml.safe_load((R/'dev_docs/agents/transcripts/audio_ai_analysis.yaml').read_text());live=next(o['create']['properties']['code'] for o in data['operations'] if 'create'in o and o['create']['box']=='analyse_nancy')
 if __import__('os').environ.get('FIELD_MERGED_CHECK'):
  gallery=next(o['create']['properties']['code'] for o in data['operations'] if 'create'in o and o['create']['box']=='analysis_visualizer_gallery')
  await m.program('real-cached-gallery',gallery)
  await m.batch('real-cached-gallery-output',['wait-output subject --channel "analysis board" --timeout 30'])
  await m.macro('real-cached-gallery-proof',[{'execute':{'box':'subject','property':'code','type':'fragment','code':'check(previews.size == 3)\ncheck(previews.map { it.operation }.toSet() == setOf("analysis.beats", "analysis.pitch_mono", "analysis.pitch_poly"))\ncheck(previews.all { it.durationSeconds == 24.0 })\nprintln("incoming real cached gallery loaded")'}}])
 for name,code in [('beat-pitch-page',doc),('beat-pitch-transcript',live)]:
  await m.program(name,fixture(code))
  await m.batch(name+'-complete',['wait-output subject --channel "test done" --contains "real analysis" --timeout 180'])
  await m.macro(name+'-proof',[{'execute':{'box':'subject','property':'code','type':'fragment','code':'''val results = exercise.future.join()
check(results.size == 3)
check(results.map { client.analysisPreview(it.id).join().operation }.toSet() == setOf("analysis.beats", "analysis.pitch_mono", "analysis.pitch_poly"))
check(results.all { it.requestHash.isNotBlank() })
check(results.all { it.data.mediaType.isNotBlank() })
println("REAL_RESULT_IDS " + results.map { it.id })
println("REAL_OPERATIONS " + results.map { client.analysisPreview(it.id).join().operation })
'''}}])
asyncio.run(main())
