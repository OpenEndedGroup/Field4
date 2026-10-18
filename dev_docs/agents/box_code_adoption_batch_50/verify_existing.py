"""Verify source mirrors and reuse an unchanged page's existing semantic proof."""
from pathlib import Path
import sys,json,re,hashlib,subprocess,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;sys.path.insert(0,str(H.parent/'box_code_adoption_batch_7'))
from format_docs import sections
from verify_tokens import tokens
page=(R/'public/docs/statistics_examples.md').read_text();rolling=next(page[a:b] for a,b in sections(page) if 'val xiNull =' in page[a:b]);rolling=re.sub(r'(?m)^\s*:::(?:fold[^\n]*|endfold)\s*$','',rolling)
doc=yaml.safe_load((R/'dev_docs/agents/transcripts/statistics_examples.yaml').read_text());live=next(o['create']['properties']['code'] for o in doc['operations'] if o.get('create',{}).get('box')=='stats_rolling');assert tokens(rolling)==tokens(live)
p=R/'public/docs/alife_examples.md';text=p.read_text();assert text==subprocess.check_output(['git','show','a637751ae:public/docs/alife_examples.md'],cwd=R,text=True)
for name in ['GOAL_X','GOAL_Y','STEPS','SEEDS','PER_SEED']:
 line=next(l for l in text.splitlines() if l.startswith('val '+name+' ='));assert ';' not in line
old=json.loads((H.parent/'box_code_adoption_batch_46/semantics.json').read_text());assert any(s['path']=='public/docs/alife_examples.md' and s['baselineExecutableTokensPreserved'] for s in old['sources'])
(H/'existing-proof.json').write_text(json.dumps({'rollingSourceMatchesVerifiedDocsPage':True,'alifeSourceUnchangedSinceBatch46':True,'alifeSha256':hashlib.sha256(text.encode()).hexdigest(),'separateControlDeclarations':['GOAL_X','GOAL_Y','STEPS','SEEDS','PER_SEED'],'reusedProof':'../box_code_adoption_batch_46/semantics.json'},indent=2)+'\n')
