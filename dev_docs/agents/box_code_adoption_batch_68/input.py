"""Check authored WAV setup diagnostics and import a real supplied WAV without submitting inference."""
from pathlib import Path
import asyncio,hashlib,importlib.util,json,math,struct,wave,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;O=R/'build/box-code-revision/adoption-68';O.mkdir(parents=True,exist_ok=True)
wav=O/'provided-source.wav'
with wave.open(str(wav),'wb') as f:
 f.setparams((1,2,16000,0,'NONE','not compressed'));f.writeframes(b''.join(struct.pack('<h',round(1600*math.sin(2*math.pi*220*i/16000))) for i in range(12*16000)))
sha='sha256:'+hashlib.sha256(wav.read_bytes()).hexdigest()
s=importlib.util.spec_from_file_location('rig68input',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9722;m.OUT=O/'input';m.OUT.mkdir(parents=True,exist_ok=True)
doc=yaml.safe_load((R/'dev_docs/agents/transcripts/audio_ai_underfit_mancini.yaml').read_text());source=next(o['create']['properties']['code'] for o in doc['operations'] if o.get('create',{}).get('box')=='underfit_mancini_generate');a=source.index('// Supply a WAV');b=source.index('val loraRoot',a);guard=source[a:b].replace('System.getenv("FIELD_AUDIO_UNDERFIT_SOURCE_WAV")','configuredPath')
missing=O/'missing-user-source.wav';assert not missing.exists()
code='''import java.nio.file.Path
import java.nio.file.Files
prop<java.util.concurrent.ConcurrentLinkedQueue<String>>(proofEvents)
proofEvents=java.util.concurrent.ConcurrentLinkedQueue<String>()
val absent=runCatching {
    val configuredPath:String?=null
GUARD
    sourcePath
}.exceptionOrNull()
check(absent is IllegalArgumentException && absent.message!!.contains("FIELD_AUDIO_UNDERFIT_SOURCE_WAV"))
val missing=runCatching {
    val configuredPath:String?=__MISSING_PATH__
GUARD
    sourcePath
}.exceptionOrNull()
check(missing is IllegalArgumentException && missing.message!!.contains("Source WAV not found"))
val configuredPath:String?=__WAV_PATH__
GUARD
val client=FieldAudioClient("http://127.0.0.1:18765")
val pendingImport=client.importWav(sourcePath)
frames {
    while(!pendingImport.isDone) frame()
    val imported=pendingImport.get()
    check(imported.id==__SHA256__)
    check(imported.representations.any {kotlin.math.abs((it.durationSeconds ?: 0.0)-12.0)<1e-4})
    val region=AssetRegion(imported.id,startSeconds=0.0,durationSeconds=12.0)
    check(region.assetId==imported.id && region.durationSeconds==12.0)
    java.io.File(__ASSET_OUT__).writeText(client.json.encodeToString(AudioAsset.serializer(),imported))
    proofEvents.add("real WAV import and 12-second source region")
}
'''.replace('GUARD',guard).replace('__MISSING_PATH__',json.dumps(str(missing))).replace('__WAV_PATH__',json.dumps(str(wav))).replace('__SHA256__',json.dumps(sha)).replace('__ASSET_OUT__',json.dumps(str(m.OUT/'imported-asset.json')))
async def main():
 await m.macro('g4-source-input-path',[{'create':{'box':'g4-source-input-path','properties':{'code':code}}},{'launch':{'box':'g4-source-input-path'}},{'wait':{'frames':30}}])
 await m.macro('g4-source-input-path-assert',[{'execute':{'box':'g4-source-input-path','type':'fragment','code':'check(proofEvents.toList()==listOf("real WAV import and 12-second source region"));println("missing setup and missing-file diagnostics verified; real supplied WAV imported")'}}])
 asset=json.loads((m.OUT/'imported-asset.json').read_text());assert asset['id']==sha
 (H/'input-behavior.json').write_text(json.dumps({'actualBoxMissingEnvironmentDiagnostic':True,'actualBoxMissingFileDiagnostic':True,'realProvidedWavImported':sha,'actualRegionSeconds':12,'privateApiProject':'build/box-code-revision/adoption-43/field-audio-project','limits':'A generated test WAV is real input, not a fabricated model output. No Underfit inference or private LoRA checkpoint is run; existing checkpoint/model/seed/request settings are preserved.'},indent=2)+'\n')
asyncio.run(main())
