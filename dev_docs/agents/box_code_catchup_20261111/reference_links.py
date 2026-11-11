"""Index real generated reference anchors; propose direct links for delegated examples."""
from pathlib import Path
from collections import defaultdict
import html,json,re
import session as S

def index():
    symbols=defaultdict(set)
    for path in sorted((S.ROOT/'public/docs/reference').glob('*.md')):
        if path.name=='index.md':continue
        owner=None
        for line in path.read_text().splitlines():
            heading=re.match(r'^### (\w+) \{\s*#([^ }]+)\s*\}',line)
            if heading:
                owner=heading[1]
                symbols[owner].add(f'reference/{path.name}#{heading[2]}')
            elif line.startswith('## '):owner=None
            row=re.search(r'<a id="([^"]+)"></a>(.*?)(?:\|\s|$)',line)
            if not row:continue
            names=re.findall(r'<span class="nf">(.*?)</span>',row[2])
            for name in names:
                name=html.unescape(re.sub('<[^>]+>','',name))
                link=f'reference/{path.name}#{row[1]}'
                symbols[name].add(link)
                if owner:symbols[owner+'.'+name].add(link)
    return symbols

def candidates():
    symbols=index();rows=json.loads((S.OUT/'bodies.json').read_text());result=[]
    for i,r in enumerate(rows):
        if r['kind']!='markdown' or r['language']!='kotlin' or not r['path'].startswith('public/docs/'):continue
        plain=re.sub(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|//[^\n]*|/\*[\s\S]*?\*/','',r['code'])
        lines=[l for l in plain.splitlines() if l.strip() and not l.strip().startswith(('import ',':::'))]
        if len(lines)>18:continue
        names=list(dict.fromkeys(re.findall(r'\b([A-Z]\w*(?:\.[a-zA-Z]\w*)?)\s*(?:\(|\{)',plain)))
        # Instance calls get an owner when a simple constructor assignment establishes it.
        variables=dict(re.findall(r'\b(?:val|var)\s+(\w+)\s*=\s*([A-Z]\w*)\s*\(',plain))
        for receiver,name in re.findall(r'\b(\w+)\.(\w+)\s*\(',plain):
            if receiver in variables:names.append(variables[receiver]+'.'+name)
        links={n:next(iter(symbols[n])) for n in names if len(symbols[n])==1}
        if not links:continue
        result.append(dict(i=i,path=r['path'],lines=len(lines),code=r['code'],links=links))
    (S.OUT/'reference-candidates.json').write_text(json.dumps(result,indent=2)+'\n')
    print(len(result),'short examples with resolvable delegated APIs')
    for r in result:print(r['i'],r['path'],r['lines'],', '.join(r['links']))


def apply():
    rows=json.loads((S.OUT/'reference-candidates.json').read_text())
    by_page=defaultdict(dict)
    for row in rows:
        for name,target in row['links'].items():
            if name.split('.')[0] not in {'FLine','Img','Param','Window','Renderer','AnimationClock','PngEncoder','Vector2f','Vector3f','Vector4f','Table','Keyframe'}:
                by_page[row['path']][name]=target
    prior=json.loads((S.HERE/'reference-verification.json').read_text())['changes'] if (S.HERE/'reference-verification.json').exists() else []
    changes=[]
    for page,links in by_page.items():
        path=S.ROOT/page;text=path.read_text();before=text
        # Never place Markdown inside executable fences or change code/capture instructions.
        parts=re.split(r'(^`{3,}[^\n]*\n.*?^`{3,}[ \t]*$)',text,flags=re.M|re.S)
        missing=[];added=[]
        for name,target in links.items():
            prose=''.join(parts[::2])
            if target in prose:continue
            candidates=[name]
            if '.' in name:
                tail=name.rsplit('.',1)[1]
                if sum(k.rsplit('.',1)[-1]==tail for k in links)==1:candidates.append(tail)
            done=False
            for label in candidates:
                pattern=r'(?<![\[`])`('+re.escape(label)+r'(?:\([^`]*?\))?)`(?!\])'
                for i in range(0,len(parts),2):
                    parts[i],n=re.subn(pattern,lambda m:f'[`{m[1]}`]({target})',parts[i],count=1)
                    if n:done=True;break
                if done:break
            if not done:missing.append((name,target))
            added.append(dict(symbol=name,target=target,placement='existing prose' if done else 'API reference section'))
        text=''.join(parts)
        if missing:
            text=text.rstrip()+('\n\n' if '\n## API reference\n' in text else '\n\n## API reference\n\n')
            text+='Implementation and parameter details for the helpers used above:\n\n'
            text+='\n'.join(f'- [`{name}`]({target})' for name,target in missing)+'\n'
        if text!=before:path.write_text(text);changes.append(dict(path=page,links=added))
    merged={r['path']:r for r in prior}
    for row in changes:
        if row['path'] in merged:merged[row['path']]['links']+=row['links']
        else:merged[row['path']]=row
    changes=list(merged.values())
    (S.HERE/'reference-verification.json').write_text(json.dumps(dict(changes=changes,policy='Only symbols called in the frozen changed short examples; unique real reference anchors; executable fences untouched.'),indent=2)+'\n')
    print(len(changes),'pages linked;',sum(len(r['links']) for r in changes),'direct class/member links')

def verify():
    receipt=json.loads((S.HERE/'reference-verification.json').read_text());count=0
    for page in receipt['changes']:
        text=(S.ROOT/page['path']).read_text()
        for link in page['links']:
            target,anchor=link['target'].split('#');source=(S.ROOT/'public/docs'/target).read_text()
            assert f'id="{anchor}"' in source or re.search(r'\{\s*#'+re.escape(anchor)+r'\s*\}',source),link
            assert link['target'] in text,link
            count+=1
    print(count,'reference targets and anchors verified')

if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['candidates','apply','verify'],default='candidates',nargs='?')
    globals()[parser.parse_args().action]()
