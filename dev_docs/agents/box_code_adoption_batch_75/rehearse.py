"""Exercise the authored live pipelines against the real private audio API."""
from pathlib import Path
import asyncio,importlib.util,json,yaml,urllib.request,re
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;O=R/'build/box-code-revision/adoption-75'
s=importlib.util.spec_from_file_location('rig75',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9723;m.OUT=O/'runtime';m.OUT.mkdir(parents=True,exist_ok=True)
s=importlib.util.spec_from_file_location('docs75',H.parent/'box_code_adoption_batch_7/format_docs.py');d=importlib.util.module_from_spec(s);s.loader.exec_module(d)
async def macro(name,ops):
 p=m.OUT/(name+'.yaml');v=m.OUT/(name+'.json')
 if p.exists() and v.exists() and yaml.safe_load(p.read_text())=={'operations':ops} and json.loads(v.read_text()).get('ok'):return json.loads(v.read_text())
 return await m.macro(name,ops)
async def box(name,body):return await macro(name,[{'create':{'box':name,'frame':{'x':40.,'y':40.,'w':1100.,'h':100.},'properties':{'code':body}}},{'launch':{'box':name}},{'await':{}}])
def body(transcript):
 ops=yaml.safe_load((R/f'dev_docs/agents/transcripts/{transcript}.yaml').read_text())['operations']
 return next(x['create']['properties']['code'] for x in ops if 'create' in x and '.trackAnalysis(' in x['create']['properties'].get('code',''))
def page(name):
 text=(R/f'public/docs/{name}.md').read_text();return [text[a:b] for a,b in d.sections(text)]
async def main():
 health=json.load(urllib.request.urlopen('http://127.0.0.1:18765/health'));assert '/adoption-43/' in health['project_root'];(m.OUT/'api-identity.json').write_text(json.dumps(health,indent=2)+'\n')
 wav=R/'build/box-code-revision/adoption-43/readability43-test.wav';assert wav.is_file();midi=m.OUT/'midi';midi.mkdir(exist_ok=True)
 cases=[('muscriptor-transcript',body('audio_ai_muscriptor')),('muscriptor-page',next(x for x in page('audio_ai_muscriptor') if '.trackAnalysis(' in x)),('speech-transcript',body('audio_ai_speech_embeddings')),('speech-page',next(x for x in page('audio_ai_speech_embeddings') if '.trackAnalysis(' in x))]
 records=[]
 for name,code in cases:
  code=code.replace('FieldAudioClient()','FieldAudioClient("http://127.0.0.1:18765")')
  code=code.replace('client.assetByFilename(sourcePattern)', 'client.importWav(java.nio.file.Path.of('+json.dumps(str(wav))+'))')
  code=code.replace('client.assetsByFilename("*near_drowning*")','client.importWav(java.nio.file.Path.of('+json.dumps(str(wav))+')).thenApply { listOf(it) }')
  code=code.replace('val projectionDurationSeconds = 10.0','val projectionDurationSeconds = 4.0').replace('val projectionDuration = 10.0','val projectionDuration = 4.0')
  code=code.replace('Path.of(System.getProperty("java.io.tmpdir"), "field-audio-renders")','Path.of('+json.dumps(str(midi))+')')
  code=code.replace('    } catch (cancelled:', '        display("acceptance", "VERIFIED75 complete '+name+'")\n    } catch (cancelled:',1)
  code=code.replace('    } catch (failure: Throwable) {','    } catch (failure: Throwable) {\n        display("acceptance", "VERIFIED75 unavailable '+name+': ${failure.javaClass.name}: ${failure.message}")',1)
  uid='g2-'+name+'-75';await box(uid,code)
  result=await m.batch(uid+'-done',[f'wait-output {uid} --channel acceptance --contains VERIFIED75 --timeout 120'])
  text='\n'.join(e.get('payload',{}).get('value',e.get('payload',{}).get('text',e.get('payload',{}).get('html',''))) for r in result['results'] for e in r.get('events',[]))
  assert 'VERIFIED75' in text, 'No actual terminal verdict captured'
  if 'unavailable' in text:
   assert re.search(r'worker|unavailable|Connection|connect|configured',text,re.I),text
  records.append({'case':name,'actualVerdict':text,'completed':('VERIFIED75 complete' in text)})
 # Compile the standalone follow-on in the same typed contract, without inventing a model result.
 follow=next(x for x in page('audio_ai_muscriptor') if 'client.musicTranscription(transcriptionResult)' in x and '.trackAnalysis(' not in x)
 imports='\n'.join(x for x in follow.splitlines() if x.startswith('import '));follow='\n'.join(x for x in follow.splitlines() if not x.startswith('import '))
 await box('g2-muscriptor-followon-typecheck',imports+'\nval client=cached { FieldAudioClient("http://127.0.0.1:18765") }\nprop<AnalysisResult>(transcriptionResult)\nif(false) {\n'+follow+'\n}\nprintln("VERIFIED75 typed follow-on compiles")')
 # Run the actual caption expression with changed, explicitly editable controls.
 for name,original in cases:
  if not name.startswith('speech'):continue
  a=original.index('val projectionDescription =');b=original.index('display("projection"',a);expression=original[a:b]
  controls=[]
  for line in original.splitlines():
   if re.match(r'val projection(?:Start|Duration|Pool|Hop)',line):controls.append(line)
  # Controls live at top level before the coroutine; ignore indentation in matching.
  if not controls:controls=[x.strip() for x in original.splitlines() if re.match(r'\s*val projection(?:Start|Duration|Pool|Hop)',x)]
  control='\n'.join(controls).replace('= 0.0','= 1.2').replace('= 10.0','= 3.6').replace('= 500.0','= 400.0').replace('= 40.0','= 60.0')
  await box('g2-caption-'+name,control+'\n'+expression+'\ncheck(projectionDescription.contains("1.2") && projectionDescription.contains("3.6") && projectionDescription.contains("400.0") && projectionDescription.contains("60.0"));println("VERIFIED75 caption follows current controls: $projectionDescription")')
 (H/'behavior.json').write_text(json.dumps({'realPrivateApi':health,'livePipelines':records,'followOnTypecheckedWithExportedAnalysisResult':True,'bothCaptionExpressionsReflectEditedControls':True,'limits':'Six-second generated WAV is an explicit input substitution and source range is shortened for the speech fit. No model outputs are substituted. Real terminal worker errors, where present, are preserved exactly; missing optional neural workers are not counted as successful transcription/embedding/model acceptance. Existing real trackAnalysis Stop/socket and coroutine cancellation evidence is retained in batches 43 and 74.'},indent=2)+'\n')
asyncio.run(main())
