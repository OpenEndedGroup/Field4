"""Keep native acquisitions inside cancellable frames and bracket every acquired resource."""
from pathlib import Path
import json,yaml,re,textwrap,importlib.util
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;rows=[]
s=importlib.util.spec_from_file_location('lit77',H.parent/'box_code_adoption_batch_6/literals.py');lit=importlib.util.module_from_spec(s);s.loader.exec_module(lit)
def pair(code,a,op='{',cl='}'):
 depth=0
 for t in lit.TOKEN.finditer(code,a):
  if t[0]==op:depth+=1
  elif t[0]==cl:
   depth-=1
   if depth==0:return t.end()
 raise AssertionError('Unbalanced source')
def loop_parts(code):
 a=code.index('loop {');left=code.index('{',a);b=pair(code,left);work=textwrap.dedent(code[left+1:b-1]).strip().replace('end()','break')
 # loop supplies one final frame per iteration in addition to explicit waits in the body.
 return code[:a].rstrip(),work+'\nframe()'
def frame(code,kind):
 if 'loop {' not in code:return code
 imports='\n'.join(x for x in code.splitlines() if x.startswith('import '));body='\n'.join(x for x in code.splitlines() if not x.startswith('import '));setup,work=loop_parts(body)
 if kind=='external':
  a=setup.index('val surface =');b=setup.index('val ioSurfaceRef',a);surface=setup[a:b].strip().removeprefix('val surface = ');before=setup[:a];rest=setup[b:]
  a=rest.index('renderer.renderList.others["external-browser-macos-smoke-copy"] = Target');left=rest.index('{',a);b=pair(rest,left);target='Target '+rest[left:b]
  rest=rest[:a]+'renderer.renderList.others["external-browser-macos-smoke-copy"] = copyTarget'+rest[b:]
  inner=before+surface+'.use { surface ->\n'+textwrap.indent('val copyTarget = '+target+'\ntry {\n'+textwrap.indent(rest+'\nwhile(true) {\n'+textwrap.indent(work,'    ')+'\n}','    ')+'''\n} finally {
    renderer.renderList.others.remove("external-browser-macos-smoke-copy", copyTarget)
    renderer.renderList.others.remove("external-browser-macos-smoke-source", source)
}''','    ')+'\n}'
 elif kind=='ring':
  a=setup.index('val ring = streamer.start()');before=setup[:a];rest=setup[a:];rest=rest.replace('var surface: MacExternalBrowserSurface? = null\n','')
  # Lock and retained reference are released even if reading a byte or checking a value fails.
  a=work.index('        MetalIOSurface.lock(ref,');b=work.index('        println("iosurface-ring stream',a)
  original=work[a:b];pixel=original[original.index('        val pixel'):original.index('        MetalIOSurface.unlock')]
  new='''        val (b, g, r) = try {
            MetalIOSurface.lock(ref, MetalIOSurface.LOCK_READ_ONLY)
            try {
'''+textwrap.indent(textwrap.dedent(pixel).rstrip(),'                ')+'''
                [b, g, r]
            } finally {
                MetalIOSurface.unlock(ref, MetalIOSurface.LOCK_READ_ONLY)
            }
        } finally {
            MetalIOSurface.release(ref)
        }
'''
  work=work[:a]+new+work[b:]
  work=work.replace('val port = MetalIOSurface.createMachPort(ref)\n    MetalIOSurface.release(ref)', 'require(ref != 0L) { "IOSurface lookup failed" }\n    val port = try { MetalIOSurface.createMachPort(ref) } finally { MetalIOSurface.release(ref) }')
  # Match formatting-independent second lookup/port release.
  work=re.sub(r'val port = MetalIOSurface.createMachPort\(ref\)\s*MetalIOSurface.release\(ref\)', 'require(ref != 0L) { "IOSurface lookup failed" }\n        val port = try { MetalIOSurface.createMachPort(ref) } finally { MetalIOSurface.release(ref) }',work)
  a=work.index('renderer.renderList.others["iosurface-ring-browser-copy"] = Target');left=work.index('{',a);b=pair(work,left);target=work[left:b]
  work=work[:a]+'copyTarget = Target '+target+'\n        renderer.renderList.others["iosurface-ring-browser-copy"] = requireNotNull(copyTarget)'+work[b:]
  inner=before+'''var surface: MacExternalBrowserSurface? = null
var copyTarget: Target? = null
try {
'''+textwrap.indent(rest+'\nwhile(true) {\n'+textwrap.indent(work,'    ')+'\n}','    ')+'''
} finally {
    renderer.renderList.others.remove("iosurface-ring-browser-copy", copyTarget)
    try { surface?.close() } finally { streamer.stop() }
}'''
 elif kind=='syphon':
  a=setup.index('val inp = syphonClient(');before=setup[:a];rest=setup[a:];rest=rest[rest.index('prop<SyphonTexture>'):]
  a=rest.index('prop<RenderToTexture>(syphonProbeTarget) = RenderToTexture');left=rest.index('{',a);b=pair(rest,left);target='RenderToTexture(renderer) '+rest[left:b]
  rest=rest[:a]+'prop<RenderToTexture>(syphonProbeTarget) = probeTarget'+rest[b:];rest=re.sub(r'\bsyphonProbeTarget\b','probeTarget',rest).replace('prop<RenderToTexture>(probeTarget)','prop<RenderToTexture>(syphonProbeTarget)');rest=rest.replace('Mesh()','Mesh(renderer)')
  inner=before+'''try {
    syphonClient(name, 8000).use { inp ->
        inp.texture().use { tex ->
'''+textwrap.indent('val probeTarget = '+target+'\ntry {\n'+textwrap.indent(rest+'\nwhile(true) {\n'+textwrap.indent(work,'    ')+'\n}','    ')+'''
} finally {
    renderer.renderList.others.remove("syphonProbeTarget", probeTarget)
}''','            ')+'''
        }
    }
} finally {
    proc.destroy()
}'''
 else:raise AssertionError(kind)
 inner=re.sub(r'\brenderer\b','owner',inner)
 return imports+'\n\n// Capture the renderer before the frame coroutine starts.\nval owner = renderer\nframes {\n'+textwrap.indent(inner,'    ')+'\n}\n'
def splat(c):
 if '__splat_feed__' not in c:return c
 c='import afield.runtime.lifecycle.RunScope\n'+c
 c=c.replace('val sequenceDir =','val runScope = requireNotNull(RunScope.current())\nval sequenceDir =',1)
 pos=c.index('    // Depth-map convention:')
 c=c[:pos]+'''    // Remove only this run's presentation and defer GPU destruction until in-flight work retires.
    runScope.own {
        renderer.renderList.modules.remove("splatStream", cloud.renderModule)
        renderer.cleanup += Runnable { cloud.cleanup() }
    }
'''+c[pos:]
 for key,var in [('__splat_feed__','feedTarget'),('__splat_report__','reportTarget')]:
  a=c.index('renderer.renderList.preTargets["'+key+'"] = Target');left=c.index('{',a);b=pair(c,left);expr='Target '+c[left:b]
  c=c[:a]+'val '+var+' = '+expr+'\n    renderer.renderList.preTargets["'+key+'"] = '+var+'\n    runScope.own { renderer.renderList.preTargets.remove("'+key+'", '+var+') }'+c[b:]
 return c
def patch(name,kind):
 p=R/f'dev_docs/agents/transcripts/{name}.yaml';a=p.read_text();ed=[]
 def walk(n):
  if isinstance(n,yaml.MappingNode):
   for k,v in n.value:
    if k.value=='code' and isinstance(v,yaml.ScalarNode):
     code=splat(v.value) if kind=='splat' else frame(v.value,kind)
     if code!=v.value:ed.append((v.start_mark.index,v.end_mark.index,'|\n'+''.join(' '*(k.start_mark.column+2)+x+'\n' if x else '\n' for x in code.splitlines())))
    else:walk(v)
  elif isinstance(n,yaml.SequenceNode):
   for x in n.value:walk(x)
 walk(yaml.compose(a));b=a
 for x,y,code in reversed(ed):b=b[:x]+code+b[y:]
 if b!=a:p.write_text(b);rows.append({'path':str(p.relative_to(R)),'before':a,'after':b})
for name,kind in [('external_browser_surface_macos_smoke','external'),('iosurface_ring_roundtrip_smoke','ring'),('syphon_zero_copy_receive_smoke','syphon'),('splat_sequence_streaming','splat')]:patch(name,kind)
(H/'changes.json').write_text(json.dumps(rows,indent=2)+'\n')
