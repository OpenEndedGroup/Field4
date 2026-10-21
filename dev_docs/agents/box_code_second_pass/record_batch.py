"""Record reviewed sweep evidence and commit explicit owned paths; landing still owns full guards."""
from pathlib import Path
import argparse,hashlib,io,json,subprocess,tarfile
R=Path(__file__).resolve().parents[3]
def main():
 p=argparse.ArgumentParser();p.add_argument('--batch',type=int,required=True);p.add_argument('--finding',action='append',required=True);p.add_argument('--title',required=True);p.add_argument('--baseline',required=True);p.add_argument('--notes',type=Path,required=True);p.add_argument('--extra',action='append',default=[]);p.add_argument('--state',required=True);p.add_argument('--mechanical-evidence');a=p.parse_args()
 h=R/f'dev_docs/agents/box_code_adoption_batch_{a.batch}';out=R/f'build/box-code-revision/adoption-{a.batch}';checkpoint=R/'dev_docs/agents/box_code_second_pass/checkpoint.json';ledger=checkpoint.with_name('README.md');d=json.loads(checkpoint.read_text());worklist=json.loads((checkpoint.parent/d['worklist']).resolve().read_text());known={x['finding_id'] for x in worklist}
 assert len(set(a.finding))==len(a.finding)
 for key in a.finding:assert key in known and d['findings'].get(key,{}).get('status')!='addressed',key
 evidence=f'../box_code_adoption_batch_{a.batch}/README.md'
 for key in a.finding:d['findings'][key]={'status':'addressed','evidence':evidence}
 assert evidence not in d['batches'];d['batches'].append(evidence);count=sum(x.get('status')=='addressed' for x in d['findings'].values());remaining=len(worklist)-count
 branch=subprocess.check_output(['git','branch','--show-current'],cwd=R,text=True).strip();assert branch.startswith('codex/')
 d['inProgress']={'batch':a.batch,'branch':branch,'baseline':a.baseline,'state':a.state,'evidence':evidence};checkpoint.write_text(json.dumps(d,indent=2)+'\n')
 notes=a.notes.read_text().strip();mechanical=('Mechanical coverage: '+a.mechanical_evidence+'.') if a.mechanical_evidence else 'Corpus-wide mechanical coverage remains incomplete.';(h/'README.md').write_text(f'# {a.title}\n\nFrozen findings: '+', '.join('`'+x+'`' for x in a.finding)+f'.\n\n{notes}\n\nCheckpoint: {count}/405 addressed, {remaining} open. {mechanical} Full guards are delegated to the lander.\n')
 text=ledger.read_text();import re
 text,n=re.subn(r'\*\*\d+ of 405 findings addressed\*\*; \d+ remain open\.',f'**{count} of 405 findings addressed**; {remaining} remain open.',text);assert n==1
 text=text.replace('[Machine checkpoint](checkpoint.json):',f'| [{a.title}]({evidence}) | {len(a.finding)} findings resolved; see the batch for actual evidence and limits. |\n\n[Machine checkpoint](checkpoint.json):');ledger.write_text(text)
 omitted=[]
 with tarfile.open(h/'evidence.tar.gz','w:gz') as archive:
  for path in sorted(out.rglob('*')):
   if not path.is_file() or path.name.startswith('session-') or path.suffix not in ['.json','.log','.png','.xml']:continue
   if path.stat().st_size>2_000_000:
    omitted.append({'path':str(path.relative_to(out)),'bytes':path.stat().st_size,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()});continue
   archive.add(path,arcname=str(path.relative_to(out)))
  data=(json.dumps(omitted,indent=2)+'\n').encode();info=tarfile.TarInfo('omitted-bulk.json');info.size=len(data);archive.addfile(info,io.BytesIO(data))
 rows=json.loads((h/'changes.json').read_text());paths=list(dict.fromkeys([x['path'] for x in rows]+[str(x.relative_to(R)) for x in h.iterdir() if x.is_file()]+[str(checkpoint.relative_to(R)),str(ledger.relative_to(R))]+a.extra))
 subprocess.run(['git','diff','--check'],cwd=R,check=True)
 subprocess.run(['git','add','--']+[x for x in paths if not x.endswith('.tar.gz')],cwd=R,check=True);subprocess.run(['git','add','-f','--',*[x for x in paths if x.endswith('.tar.gz')]],cwd=R,check=True)
 subprocess.run(['git','commit','--only','-m',a.title,'--']+paths,cwd=R,check=True)
 subprocess.run(['uv','run','python','tools/land.py','enqueue'],cwd=R,check=True)
if __name__=='__main__':main()
