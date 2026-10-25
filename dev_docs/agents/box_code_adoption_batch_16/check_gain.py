exec(open('build/box-code-revision/adoption-16/rehearse.py').read().split('async def main():')[0])
import shlex,yaml
async def main():
 text=(R/'public/docs/visualizers.md').read_text();code=next(text[a:b] for a,b in d.sections(text) if 'prop<Double>(gain)' in text[a:b] and 'syncToProperty' in text[a:b])
 await m.program('typed_gain',code+'\ncheck(gain == 0.5)')
 p=m.OUT/'gain.js';p.write_text("window.updateFieldSlider(document.querySelector('.field-slider'),0.9,{send:true}); return 'gain edited';")
 await m.batch('gain_edit',[f'page-eval --body --file {p}'])
 await m.batch('gain_read',['run subject --value '+shlex.quote('check(gain == 0.9); println("gain follows edit")'),'run subject','run subject --value '+shlex.quote('check(gain == 0.9); println("gain survives rerun")')])
 ops=yaml.safe_load((R/'dev_docs/agents/transcripts/docs_capture/slider_gestures.yaml').read_text())['operations']
 await m.macro('gesture_property',ops,True)
 await m.batch('gesture_edit',[f'page-eval --body --file {p}'])
 await m.batch('gesture_read',['run slider_gestures --value '+shlex.quote('check(gain == 0.9); println("typed publication follows gesture")')])
asyncio.run(main())
