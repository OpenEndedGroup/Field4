"""Bind the real K2 regression verdicts to the current source before touching examples."""
from pathlib import Path
import hashlib,json
R=Path(__file__).resolve().parents[3];O=R/'build/repl-variable-rebinding';text=(O/'regression-reviewed.jsonl').read_text();decoder=json.JSONDecoder();runs=[]
while text.strip():
 text=text.lstrip();value,end=decoder.raw_decode(text);runs.append(value);text=text[end:]
assert len(runs)==2 and all(x.get('ok') and x.get('data',{}).get('exitCode')==0 for x in runs),[(x.get('command'),x.get('data',{}).get('stderr')) for x in runs]
files=['src/jvmMain/kotlin/afield/runtime/kotlin/RerunCompileCache.kt','src/jvmMain/kotlin/afield/runtime/kotlin/KotlinInterface2.kt','src/jvmMain/kotlin/afield/runtime/kotlin/ReplVariableRebindingSmokeTest.kt']
proof={'ok':True,'sourceHashes':{p:hashlib.sha256((R/p).read_bytes()).hexdigest() for p in files},'realK2RebindingAndCacheTestsPassed':[x['data']['entrypoint'] for x in runs]};(O/'regression-passed.json').write_text(json.dumps(proof,indent=2)+'\n');print(json.dumps(proof))
