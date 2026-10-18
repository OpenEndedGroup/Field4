"""Real sACN loopback, missing traffic and constructor failures against the edited teaching body."""
from pathlib import Path
import asyncio,importlib.util,json,sys,socket,re,os
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig42dmx',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=int(os.environ.get('FIELD_TEST_PORT','9712'));m.OUT=R/'build/box-code-revision/adoption-42/dmx';m.OUT.mkdir(parents=True,exist_ok=True)
sys.path.insert(0,str(H.parent/'box_code_adoption_batch_7'));from format_docs import sections
from docs_hooks import extract_static_code
text=(R/'public/docs/chain_scene_to_light.md').read_text();states=[text[a:b] for a,b in sections(text) if 'val deadline =' in text[a:b]];assert len(states)==1
code=extract_static_code(lines=states[0].splitlines());imports='\n'.join(line for line in code.splitlines() if line.startswith('import '));code='\n'.join(line for line in code.splitlines() if not line.startswith('import '))
# Observe actual resources without replacing them or their packets.
code=code.replace('.use { out ->','.also { outputs.add(it) }.use { out ->',1).replace('.use { node ->','.also { inputs.add(it) }.use { node ->',1)
marker='showReceived(rig.patch, node, channel = "received", w = 540.0, h = 330.0)';assert marker in code;code=code.replace(marker,'receivedCalled = true\n'+marker)
with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as sock:
 sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
prefix=imports+'''\nval outputs=mutableListOf<DmxOutput>()
val inputs=mutableListOf<DmxInput>()
var receivedCalled=false
'''
normal=code.replace('val port = 35692',f'val port = {port}')
async def main():
 for i in range(2):
  await m.program('sacn-normal-'+str(i),prefix+normal,'''check(receivedCalled)
check(outputs.size==1 && inputs.size==1)
check(outputs.all { it.isClosed } && inputs.all { it.isClosed })
check((1..2).all { inputs.single().version(it)>0L && inputs.single().universe(it).contentEquals(outputs.single().universe(it).data) })
proof("both actual universes agree byte-for-byte; sender and receiver closed")''',channel='received',capture=True)
  if i:break
  missing=normal.replace('host = "127.0.0.1", port = port','host = "127.0.0.1", port = blackhole.localPort',1);assert missing!=normal
  await m.program('sacn-missing',prefix+'''val blackhole=java.net.DatagramSocket(0)
val startedAt=System.nanoTime()
val failure=try { runCatching {\n'''+missing+'''\n}.exceptionOrNull() } finally { blackhole.close() }
''','''check(failure is IllegalStateException && failure.message!!.contains("Incomplete sACN frame"))
check(failure.message!!.contains("universe 1=0, universe 2=0"))
check(!receivedCalled)
check(outputs.size==1 && inputs.size==1 && outputs.all { it.isClosed } && inputs.all { it.isClosed })
check((System.nanoTime()-startedAt)/1e9 < 5.0)
println(failure.message.orEmpty())
proof("missing packets fail before received panel; both actual sockets closed")''')
  blocked=normal.replace(f'val port = {port}','val port = blocker.localPort')
  await m.program('sacn-input-bind-failure',prefix+'''val blocker=java.net.DatagramSocket(0,java.net.InetAddress.getByName("127.0.0.1"))
val failure=try { runCatching {\n'''+blocked+'''\n}.exceptionOrNull() } finally { blocker.close() }
''','''check(failure is java.net.BindException) { failure.toString() }
check(outputs.size==1 && outputs.single().isClosed && inputs.isEmpty())
check(!receivedCalled)
proof("input constructor failure closes the already-open output")''')
asyncio.run(main())
