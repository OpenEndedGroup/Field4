"""Measure the actual fixture output before/after its flat-background edit."""
from pathlib import Path
import asyncio,importlib.util,json,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;OUT=R/'build/box-code-revision/adoption-45/bookmarks';OUT.mkdir(parents=True,exist_ok=True)
s=importlib.util.spec_from_file_location('bm45',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9716;m.OUT=OUT
async def measure(name):
 path=OUT/(name+'.json')
 script='''ui.waitForSelector("div[style*='height:300px']", 8000.0)
val measured = ui.eval("""
 const box = [...document.querySelectorAll('.box')].find(b => b.uid === 'bm_render');
 const output = [...box.querySelectorAll('div')].find(e => e.textContent === 'render output' && e.style.width === '520px');
 if (!output) throw new Error('real render output missing');
 const r = output.getBoundingClientRect(), style = getComputedStyle(output);
 return JSON.stringify({width:r.width,height:r.height,color:style.color,padding:style.padding,backgroundImage:style.backgroundImage});
""".trimIndent())
java.io.File(PATH).writeText(measured.toString())'''.replace('PATH',json.dumps(str(path)))
 await m.macro(name+'-measure',[{'drive':{'box':'bm_render','script':script}}])
 value=json.loads(path.read_text())
 return json.loads(value) if isinstance(value,str) else value
async def main():
 row=next(r for r in json.loads((H/'changes.json').read_text()) if r['path'].endswith('bookmarks_panel.yaml'))
 old=yaml.safe_load(row['before']);new=yaml.safe_load((R/row['path']).read_text())
 await m.macro('before',old['operations'],True)
 before=await measure('before')
 def source(data):return next(o['create']['properties']['code'] for o in data['operations'] if 'create'in o and o['create']['box']=='bm_render')
 await m.macro('flat',[{'edit':{'box':'bm_render','property':'code','from':source(old),'to':source(new)}},{'launch':{'box':'bm_render'}},{'await':{}}])
 after=await measure('after')
 assert before['backgroundImage'].startswith('linear-gradient'),before
 assert after['backgroundImage']=='none',after
 assert {k:v for k,v in before.items() if k!='backgroundImage'}=={k:v for k,v in after.items() if k!='backgroundImage'},(before,after)
 (OUT/'dimensions.json').write_text(json.dumps({'before':before,'after':after,'sameExtentsAndContrast':True},indent=2)+'\n')
asyncio.run(main())
