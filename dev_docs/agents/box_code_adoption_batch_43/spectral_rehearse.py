"""Exercise the live page's three real Smile fits on a real worker artifact."""
from pathlib import Path
import asyncio,importlib.util,json,re,sys
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;OUT=R/'build/box-code-revision/adoption-43'
s=importlib.util.spec_from_file_location('rig43',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=int(__import__("os").environ.get("FIELD_TEST_PORT","9713"));m.OUT=OUT/('spectral-runtime-merged' if __import__('os').environ.get('FIELD_MERGED_CHECK') else 'spectral-runtime');m.OUT.mkdir(parents=True,exist_ok=True)
async def main():
 text=(R/'public/docs/audio_ai_spectral_embeddings.md').read_text();code=re.search(r'```diffstatic \{data-lang=kotlin\}\n([\s\S]*?)\n```',text)[1]
 code=m.extract_static_code(lines=code.splitlines())
 code=code.replace('FieldAudioClient()','FieldAudioClient("http://127.0.0.1:18765")').replace('"near_drowning.wav"','"readability43-test.wav"').replace('Path.of(System.getProperty("user.home"), "Downloads", "readability43-test.wav")','Path.of('+json.dumps(str(OUT/'readability43-test.wav'))+')')
 code=code.replace('runAsync(', 'val spectralExercise = runAsync(',1).replace('    },\n    onError =','        display("test done", "spectral completed")\n    },\n    onError =',1).replace('onError = { failure ->','onError = { failure -> display("test done", "spectral failed");',1)
 await m.program('spectral-page',code)
 await m.batch('spectral-page-complete',['wait-output subject --channel "test done" --contains spectral --timeout 180'])
 await m.macro('spectral-page-proof',[{'execute':{'box':'subject','property':'code','type':'fragment','code':'''val realSpaces = spectralExercise.future.join()
check(realSpaces.size == 3)
check(realSpaces.all { it.series.count { series -> series.name.startsWith("tsne_") } >= 4 })
check(realSpaces.all { it.series.all { series -> series.values.all { value -> value == null || value.isFinite() } } })
println("REAL_SPECTRAL_SPACES " + realSpaces.map { it.series.size })
'''}}])
asyncio.run(main())
