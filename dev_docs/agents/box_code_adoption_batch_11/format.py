"""Format every Kotlin/GLSL state on the graphics page and the explicit saved sources."""
from pathlib import Path
import importlib.util,sys,json,subprocess,re,yaml
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent;OUT=ROOT/'build/box-code-revision/adoption-11/format';OUT.mkdir(parents=True,exist_ok=True)
sys.path.insert(0,str(HERE.parent/'box_code_adoption_batch_7'))
def load(name,path):
 spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
fmt=load('docs_fmt',HERE.parent/'box_code_adoption_batch_7/format_docs.py');tok=load('body_tokens',HERE.parent/'box_code_adoption_batch_7/verify_tokens.py')
paths=json.loads((OUT.parent/'paths.json').read_text());page=ROOT/'public/docs/afield_four_graphics.md';original=page.read_text()
before=[original[a:b] for a,b in fmt.sections(original)];ktBefore={p:(ROOT/p).read_text() for p in paths if p.endswith('.kt')}
fmt.OUT=OUT/'kotlin';fmt.HERE=OUT/'input';fmt.HERE.mkdir(parents=True,exist_ok=True);(fmt.HERE/'comment_changes.json').write_text(json.dumps([{'path':str(page.relative_to(ROOT))}]))
fmt.OUT.mkdir(parents=True,exist_ok=True)
fmt.main()
text=page.read_text();after=[text[a:b] for a,b in fmt.sections(text)];assert len(before)==len(after)
for a,b in zip(before,after):assert tok.tokens(a)==tok.tokens(b),'Kotlin documentation body changed'
shaders=[]
for i,(a,b) in enumerate(fmt.sections(text,'glsl')):
 q=OUT/f'docs_shader{i}.yaml';source=text[a:b];q.write_text('fragment: |\n'+''.join('    '+x+'\n' for x in source.splitlines()));shaders.append((page,a,b,q,source))
for p in paths:
 if p.endswith('.glsl'):
  q=OUT/(Path(p).stem+'.yaml');source=(ROOT/p).read_text();q.write_text('fragment: |\n'+''.join('    '+x+'\n' for x in source.splitlines()));shaders.append((ROOT/p,0,len(source),q,source))
subprocess.run([str(ROOT/'gradlew'),'formatBoxCode','--args=--imports '+' '.join([*(str(ROOT/p) for p in ktBefore),*(str(s[3]) for s in shaders)])],cwd=ROOT,check=True)
protected=re.compile(r'//[^\r\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"')
for p in {s[0] for s in shaders}:
 text=p.read_text()
 for _,a,b,q,source in reversed([s for s in shaders if s[0]==p]):
  code=yaml.safe_load(q.read_text())['fragment'];assert tok.tokens(source)==tok.tokens(code),(p,q,'shader body')
  assert protected.findall(source)==protected.findall(code),(p,q,'shader comments/literals')
  text=text[:a]+code.rstrip()+'\n'+text[b:]
 p.write_text(text)
for p,a in ktBefore.items():assert tok.tokens(a)==tok.tokens((ROOT/p).read_text()),p
result={'kotlinDocumentationStates':len(before),'glslDocumentationStates':sum(s[0]==page for s in shaders),'savedKotlinSources':len(ktBefore),'savedShaders':sum(s[0]!=page for s in shaders),'bodyTokensPreserved':True,'shaderCommentsAndLiteralsPreserved':True}
(OUT.parent/'mechanical.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
