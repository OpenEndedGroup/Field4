from pathlib import Path
import importlib.util,asyncio,sys,json
R=Path.cwd();sys.path.insert(0,str(R/'dev_docs/agents/box_code_adoption_batch_3'))
s=importlib.util.spec_from_file_location('runtime_paint',R/'dev_docs/agents/box_code_adoption_batch_3/rehearse.py');m=importlib.util.module_from_spec(s);s.loader.exec_module(m);m.ROOT=R;m.PORT=9700;m.OUT=R/'build/box-code-revision/paint-recheck/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
s=importlib.util.spec_from_file_location('states_paint',R/'dev_docs/agents/box_code_adoption_batch_7/format_docs.py');d=importlib.util.module_from_spec(s);s.loader.exec_module(d)
async def main():
 library=Path(sys.argv[1]).resolve()
 assert library.is_file(),library
 await m.program('library', 'System.setProperty("afield.paint.library", '+json.dumps(str(library))+')\n'+'''
check(afield.graphics.paint.PaintLibrary.isAvailable)
println(afield.graphics.paint.PaintLibrary.describe())''')
 text=(R/'public/docs/paint_canvas.md').read_text();code=next(text[a:b] for a,b in d.sections(text) if 'canvas.work(FLine().rect' in text[a:b])
 await m.program('paint_areas',code,channel='main',capture=True)
 (m.OUT/'verification.json').write_text(json.dumps({'nativeLibrary':str(library),'actualPaintAreasRun':True,'capturedRealOutput':True},indent=2)+'\n')
asyncio.run(main())
