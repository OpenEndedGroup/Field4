"""Use named creative values for controls and preserve live reset assignments."""
from pathlib import Path
import json,re
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;rows=[]
def record(path,fn):
 p=R/path;a=p.read_text();b=fn(a);assert a!=b,path;p.write_text(b);rows.append({'path':path,'before':a,'after':b})
def gain(s):
 assert s.count('val gain = 2.4')==1
 return s.replace('val gain = 2.4','var gain = 2.4\n          gain = 2.4 // Edit this assignment and Alt+Enter it to change all four running bands.')
record('dev_docs/agents/transcripts/pattern_particles.yaml',gain)
def page(s):
 a='step.band0 = { (bandEnergy.getOrElse(0) { 0.0 } * 2.4).f }';assert a in s
 s=s.replace(a,'var gain = 2.4\ngain = 2.4 // Edit and Alt+Enter this assignment to change the running response.\n\n'+a)
 for i in range(4):s=s.replace(f'step.band{i} = {{ (bandEnergy.getOrElse({i}) {{ 0.0 }} * 2.4).f }}',f'step.band{i} = {{ (bandEnergy.getOrElse({i}) {{ 0.0 }} * gain).f }}')
 return s
record('public/docs/chain_pattern_particles.md',page)
def controls(s,mapping):
 for label,value in mapping.items():
  pat=r'((?:slider|checkbox)\("'+re.escape(label)+r'"[^\n]*?initialValue\s*=\s*)([^,)]+)'
  s,n=re.subn(pat,lambda m:m[1]+value,s);assert n>=1,label
 return s
record('dev_docs/agents/transcripts/direct_lighting.yaml',lambda s:controls(s,{'Spatial neighbors':'neighbors.toDouble()','Area lights':'lightCount.toDouble()','Effect weight':'weight.toDouble()','Radiance':'power.toDouble()','Light phase':'phase','View (0: lit, 9: raw)':'diagnostic.toDouble()'}))
record('public/docs/open_dlss_nr.md',lambda s:controls(s,{'Network style':'style.toDouble()','Tone':'tone.toDouble()','Structure':'structure.toDouble()','NR intensity (0 = source)':'intensity.toDouble()','Animate source':'animate','Grade red':'red.toDouble()','Grade green':'green.toDouble()','Grade blue':'blue.toDouble()'}))
(H/'changes.json').write_text(json.dumps(rows,indent=2)+'\n')
