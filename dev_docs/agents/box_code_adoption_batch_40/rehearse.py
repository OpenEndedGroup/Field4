"""Exercise the actual WFC report and two locally advertised OSCQuery services."""
from pathlib import Path
import asyncio,importlib.util,json,re,sys
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig40',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9707;m.OUT=R/'build/box-code-revision/adoption-40/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
sys.path.insert(0,str(H.parent/'box_code_adoption_batch_7'));from format_docs import sections
from docs_hooks import extract_static_code

def body(page,needle):
 t=(R/'public/docs'/page).read_text();found=[t[a:b] for a,b in sections(t) if needle in t[a:b]];assert len(found)==1,(page,len(found));return extract_static_code(lines=found[0].splitlines())

async def main():
 await m.program('wfc-report',body('wfc_examples.md','val stuckOutcome ='),'''check(report.lines().size==6)
check("<br>" !in report)
check(stuckOutcome.status == WfcStatus.Contradiction)
check(relaxedOutcome.status == WfcStatus.Solved)
println(report)
proof(report)''',channel='deadlock',capture=True)
 discovery=body('oscquery.md','val services =')
 discovery=discovery[:discovery.index('val chataigne')]
 # Real services bind two ephemeral HTTP/OSC ports and advertise through the actual mDNS responder.
 code='''import afield.io.osc.query.*
val tag="Readability40-"+System.nanoTime()
OscQueryServer(tag+"-A").use { first ->
    OscQueryServer(tag+"-B").use { second ->
'''+discovery+'''
        val local=services.filter { it.instance.startsWith(tag) }
        check(local.map { it.instance }.toSet()==setOf(tag+"-A",tag+"-B")) { services }
        check(local.map { it.port }.toSet()==setOf(first.httpPort,second.httpPort))
        check(local.all { it.address.isNotBlank() })
        println(local)
        proof(local.map { it.instance+":"+it.address+":"+it.port })
    }
}
'''
 await m.program('oscquery-discovery',code.replace('import afield.io.osc.query.*', 'import afield.io.osc.query.*'+m.PROOF),channel='output',capture=True)
asyncio.run(main())
