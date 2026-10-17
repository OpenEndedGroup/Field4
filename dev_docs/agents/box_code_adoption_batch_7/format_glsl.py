"""Use the editor's shader formatter on explicit GLSL documentation states."""
import json,subprocess,yaml
from format_docs import sections,ROOT,HERE,OUT
from verify_tokens import tokens,BASE
records=[]
for page in sorted({c['path'] for c in json.loads((HERE/'comment_changes.json').read_text())}):
    text=(ROOT/page).read_text()
    for i,(start,end) in enumerate(sections(text,'glsl')):
        body=text[start:end];path=OUT/f'{(ROOT/page).stem}_{i}_shader.yaml'
        path.write_text('fragment: |\n'+''.join('  '+line+'\n' for line in body.splitlines()))
        records.append((page,start,end,path,body))
subprocess.run([str(ROOT/'gradlew'),'formatBoxCode','--args='+' '.join(str(r[3]) for r in records)],cwd=ROOT,check=True)
for page in sorted({r[0] for r in records}):
    path=ROOT/page;text=path.read_text()
    for _,start,end,source,original in reversed([r for r in records if r[0]==page]):
        code=yaml.safe_load(source.read_text())['fragment']
        assert tokens(original)==tokens(code),(page,source)
        text=text[:start]+code.rstrip()+'\n'+text[end:]
    path.write_text(text)
print(len(records),'GLSL states formatted with identical executable tokens')
