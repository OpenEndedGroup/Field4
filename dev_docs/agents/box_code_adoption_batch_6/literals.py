"""Convert untyped nonempty listOf calls in explicit Kotlin bodies, ignoring strings/comments.

Keeps spread calls and explicit type arguments. Compiler replay still decides overload validity.
"""
import re,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3];sys.path.insert(0,str(ROOT/'tools'))
from check_box_idioms import kotlin_blocks
TOKEN=re.compile(r'//[^\n]*|/\*.*?\*/|""".*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|`[^`]*`|[A-Za-z_]\w*|\S',re.S)
def convert(code):
 tokens=[m for m in TOKEN.finditer(code) if not m[0].startswith(('//','/*'))]
 edits=[]
 for i,m in enumerate(tokens):
  if m[0]!='listOf' or (i and tokens[i-1][0]=='.') or i+1>=len(tokens) or tokens[i+1][0]!='(':continue
  depth=1;spread=False;at_arg=True
  for j in range(i+2,len(tokens)):
   t=tokens[j][0]
   if depth==1 and t=='*' and at_arg:spread=True
   if depth==1:at_arg=t==','
   if t in ['(','[','{']:depth+=1
   elif t in [')',']','}']:depth-=1
   if depth==0:break
  else:raise ValueError('unbalanced call')
  if spread or j==i+2:continue
  edits.extend([(m.start(),tokens[i+1].end(),'['),(tokens[j].start(),tokens[j].end(),']')])
 for start,end,value in sorted(edits,reverse=True):code=code[:start]+value+code[end:]
 return code,len(edits)//2
if __name__=='__main__':
 for name in sys.argv[1:]:
  path=ROOT/name;lines=path.read_text().splitlines(keepends=True);count=0
  for start,end in reversed(kotlin_blocks(name,[x.rstrip('\n') for x in lines])):
   body,n=convert(''.join(lines[start:end]));lines[start:end]=body.splitlines(keepends=True);count+=n
  path.write_text(''.join(lines));print(name,count)
