"""Run Field's real formatter on frozen source spans; verify literals and tokens before applying."""
from pathlib import Path
from collections import Counter,defaultdict
import argparse
import hashlib
import importlib.util
import json
import re
import shlex
import subprocess
import yaml

ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent
OUT=ROOT/'build/box-code-revision/docs-catchup'
def load(name,path):
    spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
DOCS=load('catchup_sections',HERE.parent/'box_code_adoption_batch_7/format_docs.py')
TOKENS=load('catchup_tokens',HERE.parent/'box_code_first_pass/mechanical.py')

def prepare(only_paths=()):
    rows=[];bodies={};hashes={}
    def add(path,code,kind,start,end,**extra):
        folds=[]
        def carry(match):
            folds.append(match[0]);return f'/* BOX_CODE_FOLD_{len(folds)-1} */'
        plain=re.sub(r'^[ \t]*:::(?:fold[^\n]*|endfold|)[ \t]*$',carry,code,flags=re.M)
        key=hashlib.sha256(plain.encode()).hexdigest()
        target=OUT/'format-inputs'/f'{key}_code_.kt';target.parent.mkdir(parents=True,exist_ok=True)
        if key not in bodies:target.write_text(plain);bodies[key]=dict(before=plain,target=str(target.relative_to(ROOT)))
        rows.append(dict(path=path,key=key,kind=kind,start=start,end=end,folds=folds,**extra))
    for name in json.loads((HERE/'scope.json').read_text())['paths']:
        if only_paths and name not in only_paths:continue
        path=ROOT/name;text=path.read_text();hashes[name]=hashlib.sha256(text.encode()).hexdigest()
        if path.suffix=='.md':
            for start,end in DOCS.sections(text):add(name,text[start:end],'markdown',start,end)
        else:
            def visit(node):
                if isinstance(node,yaml.MappingNode):
                    for k,v in node.value:
                        if k.value=='code' and isinstance(v,yaml.ScalarNode):
                            assert v.style=='|';add(name,v.value,'yaml',v.start_mark.index,v.end_mark.index,indent=k.start_mark.column+2)
                        else:visit(v)
                elif isinstance(node,yaml.SequenceNode):
                    for child in node.value:visit(child)
            visit(yaml.compose(text))
    plan=dict(hashes=hashes,rows=rows,bodies=bodies)
    (OUT/'format-plan.json').write_text(json.dumps(plan,indent=2)+'\n')
    print(len(rows),'source spans;',len(bodies),'distinct Kotlin bodies',flush=True)

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--prepare-only',action='store_true');parser.add_argument('--only-path',action='append',default=[]);parser.add_argument('--resume',action='store_true');parser.add_argument('--apply-existing',action='store_true');args=parser.parse_args()
    if not args.resume:prepare(args.only_path)
    if args.prepare_only:return
    plan=json.loads((OUT/'format-plan.json').read_text());bodies=plan['bodies']
    for path,sha in plan['hashes'].items():assert hashlib.sha256((ROOT/path).read_bytes()).hexdigest()==sha,('Source changed after format preparation',path)
    command=['./gradlew','formatBoxCode','--args='+shlex.join(['--imports',*[r['target'] for r in bodies.values()]])]
    if not args.apply_existing:
        with (OUT/'format.log').open('w') as log:result=subprocess.run(command,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT)
        assert result.returncode==0,'Formatter failed; inspect build/box-code-revision/docs-catchup/format.log'
    else:
        assert 'BUILD SUCCESSFUL' in (OUT/'format.log').read_text(),'No successful formatter invocation to reuse'
    assert not any(l.startswith('skipped ') for l in (OUT/'format.log').read_text().splitlines()),'A body needs explicit parser review'
    formatted={};changed=0
    for key,row in bodies.items():
        before=row['before'];after=(ROOT/row['target']).read_text()
        # Raw string indentation is data, including embedded JS in capture drivers.
        left=list(re.finditer(r'"""[\s\S]*?"""',before));right=list(re.finditer(r'"""[\s\S]*?"""',after));assert len(left)==len(right)
        for a,b in reversed(list(zip(left,right))):after=after[:b.start()]+a[0]+after[b.end():]
        assert [t for t in TOKENS.tokens(before,'kotlin') if t not in {',',';'}]==[t for t in TOKENS.tokens(after,'kotlin') if t not in {',',';'}],('Executable tokens changed',key)
        assert TOKENS.markers(before)==TOKENS.markers(after),('Fragment markers changed',key)
        formatted[key]=after;changed+=before.rstrip()!=after.rstrip()
    edits=defaultdict(list)
    for row in plan['rows']:
        code=formatted[row['key']]
        for i,fold in enumerate(row['folds']):
            code,count=re.subn(rf'^([^\n]*?)/\* BOX_CODE_FOLD_{i} \*/[ \t]*(?:\n|$)',lambda m:(m[1].rstrip()+'\n' if m[1].strip() else '')+fold+'\n',code,flags=re.M);assert count==1
        if row['kind']=='yaml':
            code=('|\n' if code.endswith('\n') else '|-\n')+''.join(' '*row['indent']+line+'\n' if line else '\n' for line in code.rstrip('\n').splitlines())
        else:code=code.rstrip()+'\n'
        edits[row['path']].append((row['start'],row['end'],code))
    changes=[]
    for name,spans in edits.items():
        path=ROOT/name;before=path.read_text();after=before
        for start,end,code in sorted(spans,reverse=True):after=after[:start]+code+after[end:]
        if path.suffix=='.yaml':yaml.safe_load(after)
        if before!=after:path.write_text(after);changes.append(dict(path=name,before=before,after=after))
    (OUT/'format-changes.json').write_text(json.dumps(changes,indent=2)+'\n')
    summary=dict(spans=len(plan['rows']),distinctKotlinBodies=len(bodies),formattedBodies=changed,editedFiles=len(changes),executableTokensAndRawLiteralsPreserved=True,layoutPunctuation='Ktfmt may change commas and semicolons; all other executable tokens match',fragmentAndFoldMarkersPreserved=True,actualFormatter='Field editor import analysis and ktfmt; no GLSL bodies in scoped sources')
    (HERE/'format-verification.json').write_text(json.dumps(summary,indent=2)+'\n');print(json.dumps(summary),flush=True)

if __name__=='__main__':main()
