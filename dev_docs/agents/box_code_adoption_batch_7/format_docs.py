"""Format all Kotlin states on explicit pages, including display-only snippets.

Keep before/after states independent and carry every existing fold directive verbatim.
"""
from pathlib import Path
import re,json,subprocess,sys
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent
OUT=ROOT/'build/box-code-revision/adoption-7/format';OUT.mkdir(parents=True,exist_ok=True)
def sections(text, language="kotlin"):
    for fence in re.finditer(r'^(`{3,})([^\n]*)\n(.*?)^\1[ \t]*$',text,re.M|re.S):
        info=fence[2]
        if not (re.search(rf'\bdata-lang={language}\b',info) or re.match(rf'(?:{language}\b|\{{\.{language}\b)',info)):continue
        body=fence[3];markers=list(re.finditer(r'^:::(?:static|before|after)[ \t]*\n',body,re.M))
        if not markers:
            yield fence.start(3),fence.end(3);continue
        for i,m in enumerate(markers):yield fence.start(3)+m.end(),fence.start(3)+(markers[i+1].start() if i+1<len(markers) else len(body))
def main():
    pages=sorted({c['path'] for c in json.loads((HERE/'comment_changes.json').read_text())});extracted=[]
    for page in pages:
        text=(ROOT/page).read_text()
        for i,(start,end) in enumerate(sections(text)):
            folds=[]
            def carry(m):
                folds.append(m[0]);return f'/* BOX_CODE_FOLD_{len(folds)-1} */'
            code=re.sub(r'^[ \t]*:::(?:fold[^\n]*|endfold|)[ \t]*$',carry,text[start:end],flags=re.M)
            path=OUT/f'{Path(page).stem}_{i}_code_.kt';path.write_text(code);extracted.append((page,start,end,path,folds))
    subprocess.run([str(ROOT/'gradlew'),'formatBoxCode','--args=--imports '+' '.join(str(x[3]) for x in extracted)],cwd=ROOT,check=True)
    changed=[]
    for page in pages:
        path=ROOT/page;text=path.read_text()
        for _,start,end,target,folds in reversed([x for x in extracted if x[0]==page]):
            code=target.read_text()
            for i,m in enumerate(folds):
                code,n=re.subn(rf'^([^\n]*?)/\* BOX_CODE_FOLD_{i} \*/[ \t]*(?:\n|$)',lambda x:(x[1].rstrip()+'\n' if x[1].strip() else '')+m+'\n',code,flags=re.M);assert n==1,(target,m)
            text=text[:start]+code.rstrip()+'\n'+text[end:]
        if text!=path.read_text():
            changed.append(page)
            if '--check' not in sys.argv:path.write_text(text)
    print(len(extracted),'states;',len(changed),'pages changed')
    if '--check' in sys.argv and changed:raise SystemExit(1)
if __name__=='__main__':main()
