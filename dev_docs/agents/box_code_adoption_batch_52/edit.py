"""Keep reruns local and document the deliberate circle sampling topology."""
from pathlib import Path
import re,json,subprocess,sys
sys.path.insert(0,str(Path(__file__).resolve().parent.parent/"box_code_adoption_batch_7"))
from format_docs import sections
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;rows=[]
PLAIN=re.compile(r':code\s+("(?:\\.|[^"\\])*")')
INLINE=re.compile(r'"code"\s+#afield/file-inline\s*\{"path"\s+("(?:\\.|[^"\\])*")\s+"inline"\s+("(?:\\.|[^"\\])*")')
def record(path,pattern,group,fn):
 p=R/path;a=subprocess.check_output(["git","show","adc1eb308:"+path],cwd=R,text=True);s=a;edits=[]
 for m in pattern.finditer(s):
  code=json.loads(m[group]);changed=fn(code)
  if changed!=code:edits.append((m.start(group),m.end(group),json.dumps(changed,ensure_ascii=False)))
 assert edits,path
 for x,y,v in reversed(edits):s=s[:x]+v+s[y:]
 p.write_text(s);rows.append({'path':path,'before':a,'after':s})
def roots(c):return c.replace('GeometryAgentTools.clearSearchRoots()\n','')
record('public/docs/assets/ai_tool_demo/metadata.edn',PLAIN,1,roots)
removed=0
def fline(c):
 global removed
 c,n=re.subn(r'\nrunBlocking\s*\{\s*screenshot\("/Users/marc/[^"\n]+"\)\s*\}\s*$', '\n',c);removed+=n
 if '// A smooth target curve (a circle-ish polyline)' in c:
  c=c.replace('// A smooth target curve (a circle-ish polyline)','// A 140-segment target circle, with an explicit repeated endpoint.\n// Keep this node layout: byPerlinNoising below samples by node index; FLine.circle\n// closes without that duplicate and would change the noisy sample positions.')
 if 'byChoppingSpacing' in c:
  c=c.replace('byChoppingSpacing(spacing =', 'bySimplifying(epsilon =').replace('byResampling(', 'byArcLengthParameterizing(')
  c=re.sub(r'\bchopped\b','simplified',c)
 if 'm c+ z' in c and 'parameters = listOf(' in c:
  page=(R/'public/docs/fline_examples.md').read_text()
  c=next(page[a:b] for a,b in sections(page) if 'val petalPoints: List<Vec3>' in page[a:b]).rstrip()+'\n\ndisplayOpen("lines")\n'
 return c
record('public/docs/assets/fline_examples/metadata.edn',INLINE,2,fline)
(H/'changes.json').write_text(json.dumps(rows,indent=2)+'\n');(H/'edit-counts.json').write_text(json.dumps({'absoluteCaptureTailsRemoved':removed,'circleConstruction':'Retained deliberately pending actual topology/noise comparison; source comment explains node-index dependency.'},indent=2)+'\n');print('removed capture tails',removed)
