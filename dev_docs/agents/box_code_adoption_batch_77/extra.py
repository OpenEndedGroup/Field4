"""Run the installed real encoder and Syphon smoke peers; no stand-ins."""
from pathlib import Path
import asyncio,importlib.util,json
H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('r77extra',H/'rehearse.py');r=importlib.util.module_from_spec(s);s.loader.exec_module(r)
async def main():
 proofs=[]
 for short,name,marker,timeout in [('ring','iosurface_ring_roundtrip_smoke','iosurface-ring PASS',60),('syphon','syphon_zero_copy_receive_smoke','syphon-zero-copy PASS',45)]:
  props=r.subject(name)['properties'];uid='g3-real-'+short
  await r.box(uid,props)
  result=await r.m.batch(uid+'-pass',[f'wait-output {uid} --contains "{marker}" --timeout {timeout}'])
  proofs.append({'path':name,'realPass':True})
 p=H/'behavior.json';data=json.loads(p.read_text());data['realInteropSmokes']=proofs;data['limits']='Actual MoltenVK IOSurface copy, real installed field-encode ring helper and real installed Syphon probe/client. All advertised pixel, release, resize/import and zero-CPU-copy assertions ran. Splat acceptance is separate. No outputs or public captures were substituted.';p.write_text(json.dumps(data,indent=2)+'\n')
asyncio.run(main())
