from pathlib import Path
import importlib.util,sys,asyncio,json
R=Path.cwd();sys.path.insert(0,str(R/'dev_docs/agents/box_code_adoption_batch_3'))
s=importlib.util.spec_from_file_location('runtime16',R/'dev_docs/agents/box_code_adoption_batch_3/rehearse.py');m=importlib.util.module_from_spec(s);s.loader.exec_module(m);m.ROOT=R;m.PORT=9700;m.OUT=R/'build/box-code-revision/adoption-16/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
s=importlib.util.spec_from_file_location('sections16',R/'dev_docs/agents/box_code_adoption_batch_7/format_docs.py');d=importlib.util.module_from_spec(s);s.loader.exec_module(d)
async def main():
 text=(R/'public/docs/visualizers.md').read_text();code=next(text[a:b] for a,b in d.sections(text) if 'fun updatePreview()' in text[a:b])
 await m.program('mixed',code,channel='preview',capture=True)
 p=m.OUT/'inventory.js';p.write_text('''return Array.from(document.querySelectorAll('.field-slider,.field-vec2,.field-checkbox,.field-string,.field-color')).map(e=>({cls:e.className,html:e.outerHTML.slice(0,1500)}));''')
 await m.batch('edits',[f'page-eval --body --file {m.OUT}/edit.js --wait-ms 600'])
 await m.batch('assert_live',[f'page-eval --body --file {m.OUT}/assert.js',f'screenshot-box subject {m.OUT}/mixed-edited.png'])
 await m.batch('rerun',['run subject','wait-output subject --channel preview --timeout 30',f'page-eval --body --file {m.OUT}/assert.js'])
asyncio.run(main())
