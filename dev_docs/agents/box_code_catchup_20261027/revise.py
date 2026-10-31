"""Apply reviewed literal/helper edits only inside the frozen Kotlin bodies."""
from pathlib import Path
import collections,json,re
import session as S
OUT=S.OUT
# Skip strings/comments when balancing factory calls: their contents are teaching data.
def skip(s,i):
 if s.startswith('"""',i):return s.index('"""',i+3)+3
 if s.startswith('//',i):
  p=s.find('\n',i);return len(s) if p<0 else p
 if s.startswith('/*',i):
  depth=1;j=i+2
  while depth:
   if s.startswith('/*',j):depth+=1;j+=2
   elif s.startswith('*/',j):depth-=1;j+=2
   else:j+=1
  return j
 if s[i:i+1] in ['"',"'"]:
  q=s[i];j=i+1
  while j<len(s):
   if s[j]=='\\':j+=2
   elif s[j]==q:return j+1
   else:j+=1
 return i

def close(s,i):
 depth=1;j=i+1
 while depth:
  k=skip(s,j)
  if k!=j:j=k;continue
  if s[j]=='(':depth+=1
  elif s[j]==')':depth-=1
  j+=1
 return j-1

def literals(s):
 edits=[];i=0
 while i<len(s):
  j=skip(s,i)
  if j!=i:i=j;continue
  m=re.match(r'(?<![\w.])listOf\(',s[i:]) if i==0 or not re.match(r'[\w.]',s[i-1]) else None
  if m:
   a=i+len('listOf');b=close(s,a);body=s[a+1:b]
   if body.strip() and not body.lstrip().startswith('*') and not re.search(r',\s*\*',body):edits.extend([(i,a+1,'['),(b,b+1,']')])
  i+=1
 for a,b,c in sorted(edits,reverse=True):s=s[:a]+c+s[b:]
 return s,len(edits)//2

def decimals(s):
 edits=[]
 for m in re.finditer(r'"%\.([0-9])f"\.format\(',s):
  a=m.end()-1;b=close(s,a);arg=s[a+1:b].strip();expr=arg if re.fullmatch(r'[\w.]+',arg) else '('+arg+')'
  edits.append((m.start(),b+1,expr+'.f'+m[1]))
 for a,b,c in reversed(edits):s=s[:a]+c+s[b:]
 return s,len(edits)

def main():
 selected=json.loads((S.HERE/'scope.json').read_text())['paths'];baseline=json.loads((OUT/'bodies.json').read_text());changes=[];counts=collections.Counter()
 for p in selected:
  path=S.ROOT/p;before=path.read_text();edits=[]
  # After the mechanical pass, identify delta bodies by unchanged executable tokens.
  T=S.load('t',S.HERE.parent/'box_code_first_pass/mechanical.py')
  def token(c):return tuple(t for t in T.tokens(re.sub(r'^:::.*$', '', c, flags=re.M),'kotlin') if t not in {',',';'})
  wanted={token(r['code']) for r in baseline if r['path']==p and r['language']=='kotlin'}
  for r in S.spans(p,before):
   if r['language']!='kotlin' or token(r['code']) not in wanted:continue
   code=r['code'];new,n=literals(code);counts['collectionLiterals']+=n;new,k=decimals(new);counts['fixedDecimalHelpers']+=k
   if p.endswith('madp_ga.yaml'):new=new.replace('again (alt-R)','again (Alt+Enter)')
   if new==code:continue
   if r['kind']=='yaml':new=('|\n' if new.endswith('\n') else '|-\n')+''.join(' '*r['indent']+l+'\n' if l else '\n' for l in new.rstrip('\n').splitlines())
   edits.append((r['start'],r['end'],new))
  after=before
  for a,b,c in sorted(edits,reverse=True):after=after[:a]+c+after[b:]
  if after!=before:path.write_text(after);changes.append(p)
 receipt=dict(counts=counts,paths=changes);(S.HERE/'revision-verification.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt,indent=2))
if __name__=='__main__':main()
