"""Real Field execution of before/after geometry, nested literals and the compiled kit delegate."""
from pathlib import Path
import asyncio,hashlib,json,re,shlex,subprocess,sys
import yaml
import session as S
sys.path.insert(0,str(S.ROOT/'tools'))
from field_session import send_request,socket_path_for
OUT=S.OUT/'runtime';OUT.mkdir(parents=True,exist_ok=True)
checks=[]
def htmls(x):
 if isinstance(x,dict):
  for k,v in x.items():
   if k=='html' and isinstance(v,str):yield v
   else:yield from htmls(v)
 elif isinstance(x,list):
  for v in x:yield from htmls(v)
async def run(name,code):
 path=OUT/(name+'.yaml');path.write_text(yaml.safe_dump(dict(operations=[dict(create=dict(box='subject',name=name,frame=dict(x=0.,y=0.,w=500.,h=100.),properties=dict(code=code))),dict(execute=dict(box='subject',property='code'))]),sort_keys=False))
 result=await send_request(socket_path_for(9757),dict(commands=['macro write '+name+' --file '+shlex.quote(str(path)),'macro run '+name],resetDoc=True,scratch=True,timeout=240),timeout=300)
 (OUT/(name+'.json')).write_text(json.dumps(result,indent=2)+'\n');assert result.get('ok'),(name,str(result)[-2400:])
 values=[re.search(r'CATCHUP_PROOF=([^<\n]+)',h).group(1) for h in htmls(result) if re.search(r'CATCHUP_PROOF=([^<\n]+)',h)]
 assert values,(name,'no runtime proof');return values[-1]
def body(path,contains,before):
 text=subprocess.check_output(['git','show',f"{json.loads((S.HERE/'scope.json').read_text())['head']}:{path}"],cwd=S.ROOT,text=True) if before else (S.ROOT/path).read_text()
 return next(r['code'] for r in S.spans(path,text) if contains in r['code'])
async def pair(name,path,contains,cut,proof):
 values=[]
 for label,before in [('before',True),('after',False)]:
  code=body(path,contains,before)
  if cut:code=code[:code.index(cut)]
  code=re.sub(r'^:::.*$','',code,flags=re.M)+'\nprintln("CATCHUP_PROOF=" + ('+proof+'))'
  values.append(await run(name+'_'+label,code))
 assert values[0]==values[1],(name,values)
 checks.append(dict(name=name,status='passed',proof=values[0],method='Both actual authored bodies execute in Field; compare node coordinates and types'))
async def main():
 await pair('wavering_primer','dev_docs/agents/transcripts/a_pen_that_sees.yaml','val moments =','val writer =','yours.nodes.map { it.javaClass.simpleName + ":" + it.toOrNull }.joinToString("|").let { yours.nodes.size.toString() + ":" + it.hashCode() }')
 await pair('outline_primer','dev_docs/agents/transcripts/a_pen_that_sees.yaml','fun inside(', 'fun inside(', 'outline.nodes.map { it.javaClass.simpleName + ":" + it.toOrNull }.joinToString("|").let { outline.nodes.size.toString() + ":" + it.hashCode() }')
 await pair('spiral_corpus','dev_docs/agents/transcripts/teaching_a_pen.yaml','fun spiral(seed:', 'val drawings =','(0L..5L).map { seed -> spiral(seed).nodes.map { it.javaClass.simpleName + ":" + it.toOrNull }.joinToString("|") }.joinToString(";").hashCode()')
 await pair('nested_collections','dev_docs/agents/transcripts/teaching_a_pen.yaml','val rows =','lines(all,','all.flatMap { it.nodes }.map { it.javaClass.simpleName + ":" + it.toOrNull }.joinToString("|").let { all.size.toString() + ":" + it.hashCode() }')
 kit=body('public/docs/kits.md','class Lifecycle',False);kit=re.sub(r'^package[^\n]*\n','',kit,flags=re.M)
 await run('typed_kit_delegate',kit+'\nval lifecycle = Lifecycle()\nroot.lessonMode = "explore"\ncheck(root.lessonMode == "explore")\nprintln("CATCHUP_PROOF=typed-delegate")')
 checks.append(dict(name='typed_kit_delegate',status='passed',method='Actual lifecycle class and typed delegate compile in Field; setter/getter execute on the real document root (package line omitted in private script)'))
 template=str(S.ROOT/'templates/presentation')
 await run('template_inline_sync','''val codec = afield.document.persistence.SavingContext()
val directory = '''+json.dumps(template)+'''
val paths = codec.refreshInlineFilesFromDisk(directory)
val entries = codec.readInlineFileSnapshots(directory)
check(entries.isNotEmpty())
entries.forEach { check(java.io.File(directory, it.path).readText() == it.inline) }
println("CATCHUP_PROOF=" + entries.size)''')
 checks.append(dict(name='template_inline_sync',status='passed',method='Normal SavingContext EDN codec refresh; every inline fallback compared byte for byte with its actual sidecar'))
 receipt=dict(status='passed',checks=checks,sourceHashes={p:hashlib.sha256((S.ROOT/p).read_bytes()).hexdigest() for p in json.loads((S.HERE/'scope.json').read_text())['paths']})
 (S.HERE/'runtime-verification.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(checks,indent=2))
if __name__=='__main__':asyncio.run(main())
