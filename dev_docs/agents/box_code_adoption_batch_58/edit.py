"""Keep the advertised crowd count valid at one character, rejecting zero early."""
from pathlib import Path
import json
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;rows=[]
for name in ['dev_docs/agents/transcripts/skinned_characters.yaml','dev_docs/agents/demos/skinned_characters/gpu_crowd_code_.kt']:
 p=R/name;before=p.read_text();s=before;indent='          ' if p.suffix=='.yaml' else ''
 needle=indent+'val count = 64';assert needle in s;s=s.replace(needle,needle+'\n'+indent+'require(count > 0) { "count must be positive; use 1 for a single character" }',1)
 old='mix = i / (count - 1f),';new='mix = if (count == 1) 0f else i / (count - 1f),';assert old in s;s=s.replace(old,'// One character uses the first clip; there is no blend span to divide by.\n'+indent+'                '+new,1) if p.suffix=='.yaml' else s.replace(old,'// One character uses the first clip; there is no blend span to divide by.\n                '+new,1)
 p.write_text(s);rows.append({'path':name,'before':before,'after':s})
(H/'changes.json').write_text(json.dumps(rows,indent=2)+'\n')
