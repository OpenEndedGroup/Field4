"""Bring remaining statistics starting-point copies up to the verified page standard."""
from pathlib import Path
import json,re,sys,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;sys.path.insert(0,str(H.parent/'box_code_adoption_batch_7'))
from format_docs import sections
page=(R/'public/docs/statistics_examples.md').read_text();rolling=next(page[a:b] for a,b in sections(page) if 'val xiNull =' in page[a:b]);rolling=re.sub(r'(?m)^\s*:::(?:fold[^\n]*|endfold)\s*$','',rolling)
p=R/'dev_docs/agents/transcripts/statistics_examples.yaml';before=p.read_text();s=before;edits=[]
def visit(n):
 if isinstance(n,yaml.MappingNode):
  d={k.value:v for k,v in n.value}
  if d.get('box') and d['box'].value=='stats_rolling' and 'properties' in d:
   k,v=next((k,v) for k,v in d['properties'].value if k.value=='code');ind=k.start_mark.column+2;value='|\n'+''.join(' '*ind+l+'\n' if l else '\n' for l in rolling.rstrip().splitlines());edits.append((v.start_mark.index,v.end_mark.index,value))
  else:
   for k,v in n.value:visit(v)
 elif isinstance(n,yaml.SequenceNode):
  for v in n.value:visit(v)
visit(yaml.compose(s));assert len(edits)==1
for a,b,c in reversed(edits):s=s[:a]+c+s[b:]
assert s.count('sine, 1.5 cycles')==1;s=s.replace('sine, 1.5 cycles','sine, 3 cycles over x = -1..1')
s=s.replace("change, against rolling |pearson| and against the dCor null's 95th percentile\n                     at this window size.","change, against rolling |pearson| and each statistic's own pointwise null\n                     95th percentile at this window size. These references are not adjusted for\n                     repeated testing across overlapping windows.")
p.write_text(s);(H/'changes.json').write_text(json.dumps([{'path':str(p.relative_to(R)),'before':before,'after':s}],indent=2)+'\n')
