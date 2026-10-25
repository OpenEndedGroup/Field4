exec(open('build/box-code-revision/adoption-16/rehearse.py').read().split('async def main():')[0])
import yaml,shlex
async def main():
 text=(R/'public/docs/reactive_properties.md').read_text();code=next(text[a:b] for a,b in d.sections(text) if 'val ran = thisBox.named("Scale").runs' in text[a:b])
 ops=yaml.safe_load((R/'dev_docs/agents/transcripts/docs_capture/reactive_props_up.yaml').read_text())['operations']
 for op in ops:
  if op.get('create',{}).get('box')=='rp_plot':op['create']['properties']['code']=code+'\ncheck(s == 2.5 && ran == 1)'
 await m.macro('reactive_implicit_receiver',ops,True)
asyncio.run(main())
