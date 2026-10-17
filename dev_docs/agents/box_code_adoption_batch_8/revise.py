"""Apply the reviewed rectangle and connected-path idioms on explicit teaching sources."""
from pathlib import Path
import re,json,yaml
ROOT=Path.cwd();CHANGES=[];pending={}
paths=[
 'dev_docs/agents/demos/gp_drawing/gp_conditioning_code_.kt',
 'dev_docs/agents/demos/gp_drawing/gp_loop_code_.kt',
 'dev_docs/agents/transcripts/gp_drawing.yaml',
 'dev_docs/agents/transcripts/gp_instruments.yaml',
 'public/docs/gaussian_process_optimization.md',
 'public/docs/gaussian_processes.md',
 'templates/first-steps/first_steps_drawing_code_.kt',
 'dev_docs/agents/transcripts/marf_demo.yaml',
 'dev_docs/agents/transcripts/marf_field_clocks.yaml',
 'public/docs/marf.md',
 'dev_docs/agents/transcripts/stipple_sheet.yaml',
 'optional/transport-flow/examples/stipple_sheet.kt',
 'public/docs/assets/stipple_transport/stipple_sheet.kt',
 'public/docs/movement_examples.md',
 'public/docs/graph_connectivity_examples.md',
 'public/docs/spatial_geometry_examples.md',
]
paths += [str(p.relative_to(ROOT)) for p in (ROOT/'dev_docs/agents/demos/gp_instruments').glob('*_code_.kt')]
paths += [str(p.relative_to(ROOT)) for p in (ROOT/'dev_docs/agents/demos/marf_demo').glob('*_code_.kt')]

def connected(s):
    # Retire the index where it existed only to identify the first point.
    s,n=re.subn(r'(?m)^([ \t]*)pts\.forEachIndexed \{ i, \(x, y\) ->\s*if \(i == 0\) f\.moveTo\(dx \+ 100 \* x, -100 \* y\) else f\.lineTo\(dx \+ 100 \* x, -100 \* y\)\s*}',
        lambda m: m[1]+'f.breakNext()\n'+m[1]+'pts.forEach { (x, y) -> f.lineTo(dx + 100 * x, -100 * y) }',s)
    # Repeated loop bodies can have YAML indentation; preserve their enclosing indentation.
    pat=re.compile(r'if \((i|s|it) == 0\) (f\.)?moveTo\(([^\n]+)\)\s+else (?:\2)?lineTo\(\3\)')
    matches=list(pat.finditer(s))
    for m in reversed(matches):
        idx,prefix,args=m[1],m[2] or '',m[3]
        line_start=s.rfind('\n',0,m.start())+1
        # Inline forEach constructions need no loop index after the change.
        line=s[line_start:s.find('\n',m.end()) if '\n' in s[m.end():] else len(s)]
        if 'forEach' in line:
            if 'grid.indices.forEach {' in line:
                head='FLine().apply { grid.indices.forEach {'
                start=s.rfind(head,0,m.start());assert start>=line_start,(line,'grid')
                s=s[:m.start()]+prefix+'lineTo('+args+')'+s[m.end():]
                s=s[:start]+s[start:].replace(head,'FLine().apply { breakNext(); grid.indices.forEach {',1)
            elif re.search(r'(path|trace)\.forEachIndexed',line):
                match=re.search(r'(path|trace)\.forEachIndexed \{ i, p -> ',line);assert match,line
                offset=line_start+match.start()
                s=s[:m.start()]+prefix+'lineTo('+args+')'+s[m.end():]
                s=s[:offset]+s[offset:].replace(match[0],f'breakNext()\n    {match[1]}.forEach {{ p -> ',1)
            elif 'field.transport' in line:
                # The source's branch is on the next line, handled below instead.
                raise AssertionError(line)
            else:raise AssertionError(('unhandled inline',line))
            continue
        transport=re.search(r'(?m)^([ \t]*)(field\.transport\([^\n]+)\.forEachIndexed \{ i, p ->\s*$',s[:m.start()])
        if transport:
            s=s[:m.start()]+prefix+'lineTo('+args+')'+s[m.end():]
            s=s[:transport.start()]+transport[1]+prefix+'breakNext()\n'+s[transport.start():]
            s=s.replace(transport[2]+'.forEachIndexed { i, p ->',transport[2]+'.forEach { p ->',1)
            continue
        loops=list(re.finditer(r'(?m)^([ \t]*)for \('+re.escape(idx)+r' in [^\n]+\) \{',s[:m.start()]))
        if loops:
            loop=loops[-1];pad=loop[1]
            # Only an enclosing loop can own this first-point test.
            branch_pad=re.match(r'[ \t]*',s[line_start:])[0]
            if len(pad)<=len(branch_pad):
                s=s[:m.start()]+prefix+'lineTo('+args+')'+s[m.end():]
                s=s[:loop.start()]+pad+prefix+'breakNext()\n'+s[loop.start():]
                continue
        # Transported points are traversed by forEachIndexed on the preceding line.
        before=s[:m.start()];head=re.search(r'(?m)^([ \t]*)(field\.transport\([^\n]+)\.forEachIndexed \{ i, p ->\s*$',before)
        assert head,('no enclosing point loop',m[0])
        s=s[:m.start()]+prefix+'lineTo('+args+')'+s[m.end():]
        s=s[:head.start()]+head[1]+prefix+'breakNext()\n'+s[head.start():]
        s=s.replace(head[2]+'.forEachIndexed { i, p ->',head[2]+'.forEach { p ->',1)
    return s

for name in sorted(set(paths)):
    p=ROOT/name;old=p.read_text();new=old
    new=new.replace('FLine().moveTo(-1.8, -2.5).lineTo(1.8, -2.5).lineTo(1.8, 2.5).lineTo(-1.8, 2.5).close()',
                    'FLine().rect(-1.8, -2.5, 3.6, 5.0)')
    new=new.replace('FLine().moveTo(-1.6, -1.4).lineTo(1.6, -1.4).lineTo(1.6, 1.4).lineTo(-1.6, 1.4).close()',
                    'FLine().rect(-1.6, -1.4, 3.2, 2.8)')
    if name == 'public/docs/graph_connectivity_examples.md':
        new=re.sub(r'(?m)^([ \t]*)p\.nodes\.forEachIndexed \{ idx, node ->',lambda m:m[1]+'breakNext()\n'+m[1]+'p.nodes.forEach { node ->',new)
        new=new.replace('if (idx == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y)','lineTo(pt.x, pt.y)')
    new=connected(new)
    if name.endswith('first_steps_drawing_code_.kt'):
        new=new.replace('    true\n}', '}')
    if name=='public/docs/spatial_geometry_examples.md':
        oldrect='''        moveTo(0.0, 0.0)
        lineTo(1.0, 0.0)
        lineTo(1.0, 1.0)
        lineTo(0.0, 1.0)
        lineTo(0.0, 0.0)'''
        assert oldrect in new;new=new.replace(oldrect,'        rect(0.0, 0.0, 1.0, 1.0)')
        for head in reversed(list(re.finditer(r'fun gridLines\([^\n]+\) = fline \{\n',new))):
            end=new.index('    color =',head.end());body=new[head.end():end]
            assert 'for (i in' in body and 'for (j in' in body
            counts='nx, ny' if 'nx: Int' in head[0] else 'n, n'
            new=new[:head.end()]+'    grid(Rect(x0, y0, x1 - x0, y1 - y0), '+counts+')\n'+new[end:]

    if new!=old:pending[name]=new;CHANGES.append(name)
# The capture transcript is a literal copy of the shipped template; preserve the other fields.
p=ROOT/'dev_docs/agents/transcripts/docs_capture/first_steps.yaml';old=p.read_text();before=yaml.safe_load(old)
code=pending['templates/first-steps/first_steps_drawing_code_.kt'];match=re.search(r'(?m)^([ \t]*)code:',old);pad=match[1]
new=old[:match.start()]+pad+'code: |\n'+''.join(pad+'  '+line+'\n' for line in code.splitlines())
after=yaml.safe_load(new);before['operations'][0]['create']['properties']['code']=code
assert before==after
pending[str(p.relative_to(ROOT))]=new;CHANGES.append(str(p.relative_to(ROOT)))
# Validate transcript syntax before writing any file.
for name,text in pending.items():
    if name.endswith('.yaml'):yaml.safe_load(text)
if '--apply' in __import__('sys').argv:
    for name,text in pending.items():(ROOT/name).write_text(text)
import difflib
(ROOT/'build/box-code-revision/adoption-8/preview.diff').write_text(''.join(''.join(difflib.unified_diff((ROOT/name).read_text().splitlines(keepends=True),text.splitlines(keepends=True),fromfile=name,tofile=name)) for name,text in pending.items()))
(ROOT/'build/box-code-revision/adoption-8/edited-paths.json').write_text(json.dumps(CHANGES,indent=2)+'\n')
print(len(CHANGES),'sources changed')
