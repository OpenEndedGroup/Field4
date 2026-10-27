"""Focused readability edits from the human-style review, beyond formatter output."""
import json,re
import session as S
import revise as R

def refine(code,path,lang):
 if lang=='kotlin' and path in {'dev_docs/agents/transcripts/a_pen_that_sees.yaml','public/docs/a_pen_that_sees.md','dev_docs/agents/transcripts/teaching_a_pen.yaml','public/docs/teaching_a_pen.md'}:
  # These constructors each contain one zero-based point loop; keep every sampled coordinate.
  matches=list(re.finditer(r'FLine\(\)\s*\.also\s*\{\s*f\s*->',code))
  for m in reversed(matches):
   start=m.end();depth=1;i=start
   while depth:
    j=R.skip(code,i)
    if j!=i:i=j;continue
    if code[i]=='{':depth+=1
    elif code[i]=='}':depth-=1
    i+=1
   body=code[start:i-1]
   hit=re.search(r'if\s*\(i == 0\)\s*f\.moveTo\(',body)
   if hit:
    a=body.index('(',body.index('f.moveTo',hit.start()));b=R.close(body,a)
    nxt=re.match(r'\s*else\s*f\.lineTo\(',body[b+1:])
    if nxt:
     c=b+1+nxt.end()-1;d=R.close(body,c)
     if body[a+1:b]==body[c+1:d]:
      body=body[:hit.start()]+'lineTo('+body[a+1:b]+')'+body[d+1:]
      assert 'f.' not in body
      code=code[:m.start()]+'fline {\n    breakNext()'+body+code[i-1:]
 if lang=='kotlin' and path.endswith('temporal_memory.yaml'):
  a='trail.forEachIndexed { i, f -> if (i == 0) moveTo(f[0] * 150, -f[1] * 150) else lineTo(f[0] * 150, -f[1] * 150) }'
  b='breakNext()\ntrail.forEach { f -> lineTo(f[0] * 150, -f[1] * 150) }'
  code=re.sub(r'trail\.forEachIndexed \{ i, f ->\s*if \(i == 0\) moveTo\(f\[0\] \* 150, -f\[1\] \* 150\) else lineTo\(f\[0\] \* 150, -f\[1\] \* 150\)\s*\}',lambda _:b,code)
 if lang=='kotlin':
  code=code.replace('press alt-R to recompile','press Alt+Enter to recompile')
 if lang=='glsl' and path in {'dev_docs/agents/transcripts/shadertrinket.yaml','dev_docs/agents/transcripts/webrtc_shader_jam.yaml'}:
  # GLSL smoothstep has undefined results when edge0 >= edge1. Keep the intended falling mask.
  pattern=r'\bsmoothstep\(\s*([0-9]+\.[0-9]+)\s*,\s*([0-9]+\.[0-9]+)\s*,'
  edits=[]
  for m in re.finditer(pattern,code):
   if float(m[1])<=float(m[2]):continue
   a=code.index('(',m.start());end=R.close(code,a);third=code[m.end():end].strip()
   edits.append((m.start(),end+1,f'(1.0 - smoothstep({m[2]}, {m[1]}, {third}))'))
  for a,b,c in reversed(edits):code=code[:a]+c+code[b:]
  if edits:
   at=code.index('void mainImage') if 'void mainImage' in code else code.index('void main()')
   code=code[:at]+'// Falling masks invert an increasing smoothstep; reversed edges are undefined in GLSL.\n'+code[at:]
 return code

def main():
 changes=[]
 for p in json.loads((S.HERE/'scope.json').read_text())['paths']:
  path=S.ROOT/p;before=path.read_text();edits=[]
  for r in S.spans(p,before):
   code=r['code'];after=refine(code,p,r['language'])
   if after==code:continue
   changes.append(dict(path=p,language=r['language'],before=code,after=after))
   if r['kind']=='yaml':after=('|\n' if after.endswith('\n') else '|-\n')+''.join(' '*r['indent']+l+'\n' if l else '\n' for l in after.rstrip('\n').splitlines())
   edits.append((r['start'],r['end'],after))
  after=before
  for a,b,c in sorted(edits,reverse=True):after=after[:a]+c+after[b:]
  if after!=before:path.write_text(after)
 journal=S.OUT/'clarity-changes.json';prior=json.loads(journal.read_text()) if journal.exists() else [];journal.write_text(json.dumps(prior+changes,indent=2)+'\n');print(len(changes),'body edits');print('\n'.join(sorted({c['path'] for c in changes})))
if __name__=='__main__':main()
