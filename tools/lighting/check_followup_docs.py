#!/usr/bin/env python3
"""Inspect the served reconstruction/training/flow docs with Field-owned Playwright."""
import argparse,json,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser();p.add_argument('--port',required=True,type=int);p.add_argument('--base',default='http://100.110.104.26:8000/field4');a=p.parse_args()
work=ROOT/'build/neural-tools/docs-check';work.mkdir(parents=True,exist_ok=True)
verify=work/'verify.js';verify.write_text(r'''(async()=>{
const article=document.querySelector('article');if(!article)throw new Error('No article');
const text=article.innerText;if(text.includes('```')||/!\[[^\]]+\]\(/.test(text))throw new Error('Raw markdown');
const images=Array.from(article.querySelectorAll('img'));await Promise.all(images.map(i=>{i.loading='eager';return i.decode();}));
const videos=[];for(const v of article.querySelectorAll('video')){v.muted=true;await v.play();await new Promise(r=>setTimeout(r,300));v.pause();if(v.videoWidth<1920||v.currentTime<=0||v.error)throw new Error('Clip failed');videos.push({src:v.getAttribute('src'),width:v.videoWidth,height:v.videoHeight,playedTo:v.currentTime});}
if(!videos.length&&!location.pathname.includes('reconstruction_plumbing'))throw new Error('Clip missing');
for(const a of article.querySelectorAll('a[href]')){const u=new URL(a.href);if(u.origin===location.origin&&u.hash&&u.pathname===location.pathname&&!document.getElementById(decodeURIComponent(u.hash.slice(1))))throw new Error('Anchor missing '+u.hash);}
return {url:location.href,title:article.querySelector('h1').innerText,images:images.map(i=>({src:i.getAttribute('src'),width:i.naturalWidth,height:i.naturalHeight})),videos,rawMarkdown:false};})()''')
commands=[]
for page in ['optical_flow','learned_shader','reconstruction_plumbing','ray_reconstruction','neural_reconstruction']:
 nav=work/f'{page}.js';nav.write_text('(()=>{setTimeout(()=>{location.href='+json.dumps(a.base.rstrip('/')+'/'+page+'/')+'},150);return true;})()')
 commands += [f'page-eval --file {nav}','sleep 2',f'page-eval --file {verify}']
cmd=['uv','run','--project','.','--group','backdoor','python','tools/field_backdoor.py','--json','--attach-existing','--port',str(a.port),'--with-playwright']
for c in commands:cmd+=['--command',c]
r=subprocess.run(cmd,cwd=ROOT,capture_output=True,text=True,timeout=240)
(work/'raw.json').write_text(r.stdout);(work/'stderr.txt').write_text(r.stderr)
assert r.returncode==0,'Inspect docs-check/raw.json'
rest=r.stdout;records=[];decoder=json.JSONDecoder()
while rest.strip():
 rec,n=decoder.raw_decode(rest.lstrip());rest=rest.lstrip()[n:];assert rec['ok'];records.append(rec)
proof=[x['data']['result'] for x in records if str(verify) in x['command']];assert len(proof)==5
(ROOT/'dev_docs/lighting/results/neural-tools/followup-docs-browser.json').write_text(json.dumps(proof,indent=2)+'\n')
print('FIVE DOC PAGES DECODED; NO RAW MARKDOWN')
