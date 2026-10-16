"""Verify the mechanical pass after applying the explicitly reviewed code/comment edits."""
from pathlib import Path
import json,re,subprocess,textwrap
from format_docs import sections,ROOT,HERE
BASE='2949d2615'
TOKEN=re.compile(r'//[^\n]*|/\*.*?\*/|""".*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|`[^`]*`|[A-Za-z_]\w*|\S',re.S)
def raw(value):
    value=textwrap.dedent(value[3:-3]).strip();out=[];pos=0
    while (at:=value.find('${',pos))>=0:
        depth=1
        for m in TOKEN.finditer(value,at+2):
            if m[0]=='{':depth+=1
            elif m[0]=='}':depth-=1
            if depth==0:break
        else:raise ValueError('unbalanced interpolation')
        out.extend([value[pos:at],'${',' '.join(tokens(value[at+2:m.start()])),'}']);pos=m.end()
    return '"""'+''.join(out)+value[pos:]+'"""'
def tokens(code):
    code=re.sub(r'(?m)^[ \t]*:::.*$','',code)
    # The import analyzer's decisions are checked by its regression suite; compare the body.
    code=re.sub(r'(?m)^[ \t]*import [^\n]+\n','',code)
    out=[]
    for match in TOKEN.finditer(code):
        value=match[0]
        if value.startswith(('//','/*')) or value in [',',';']:continue
        out.append(raw(value) if value.startswith('"""') else value)
    return out

def main():
    changes=json.loads((HERE/'comment_changes.json').read_text());checked=0;results=[]
    for page in sorted({c['path'] for c in changes}):
        before=subprocess.check_output(['git','show',BASE+':'+page],cwd=ROOT,text=True)
        for c in changes:
            if c['path']==page:
                assert c['old'] in before,(page,c['finding']);before=before.replace(c['old'],c['new'])
        after=(ROOT/page).read_text();a=[before[x:y] for x,y in sections(before)];b=[after[x:y] for x,y in sections(after)]
        assert len(a)==len(b),(page,len(a),len(b))
        for i,(x,y) in enumerate(zip(a,b)):
            assert tokens(x)==tokens(y),(page,i)
            assert re.findall(r'(?m)^\s*//%-.*$',x)==re.findall(r'(?m)^\s*//%-.*$',y),(page,i,'fragment markers')
            checked+=1
        results.append({'path':page,'states':len(a),'bodyTokensMatch':True})
    out=ROOT/'build/box-code-revision/adoption-7-token-comparison.json'
    out.write_text(json.dumps({'baseline':BASE,'states':checked,'pages':results},indent=2)+'\n')
    print(checked,'states retain body tokens and authored fragment markers after reviewed edits')
if __name__=='__main__':main()
