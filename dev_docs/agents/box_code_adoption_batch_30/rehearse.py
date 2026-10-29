"""Run the actual SolidSL examples and assert native closure on success/exception."""
import asyncio,importlib.util,json,sys,re
from pathlib import Path
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig30',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m
m.OUT=R/'build/box-code-revision/adoption-30/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
async def main():
 for name in ['refusals','cross_check']:
  code=(R/f'public/docs/assets/solidsl_gallery/{name}_code_.kt').read_text()
  imports='\n'.join(l for l in code.splitlines() if l.startswith('import '))
  body='\n'.join(l for l in code.splitlines() if not l.startswith('import '))
  # Retain the real native handles outside the authored scope solely to inspect closure.
  if name=='refusals':
   marker='val naive ='
   at=body.index(marker)
   body=body[:at]+'''for (x in listOf(-1.4,-0.2,0.7,1.5)) for (y in listOf(-1.0,0.0,1.2)) for (z in listOf(-0.1,0.3,1.0)) {
    check(field.at(x,y,z) == part.toLevelSetField().at(x,y,z))
}
'''+body[at:]
   marker='println("hybrid  '
   at=body.index(marker);body=body[:at]+'owned += build.solid\nowned += naive\n'+body[at:]
   failure_at=body.index(marker)
  else:
   marker='val unmoved =';at=body.index(marker);body=body[:at]+'owned += coarse.solid\nowned += fine.solid\n'+body[at:]
   failure_at=body.index('val reference = fine.solid') + len('val reference = fine.solid')
  for failed in [False,True]:
   actual=body
   if failed:actual=actual[:failure_at]+'\nthrow IllegalStateException("injected viewer failure")\n'+actual[failure_at:]
   full=imports+'\nval owned=mutableListOf<afield.geometry.solid.manifold.Solid>()\ncheck(ManifoldLibrary.isAvailable) { ManifoldLibrary.describe() }\nvar sawFailure=false\ntry {\n'+actual+'\n} catch(t:IllegalStateException) { check(t.message == "injected viewer failure");sawFailure=true }'
   probe='check(sawFailure == '+str(failed).lower()+');check(owned.size == '+'2'+');check(owned.all { it.isClosed });proof(owned.map { it.isClosed })'
   await m.program(name+('-failure' if failed else '-success'),full,probe,channel='hybrid' if name=='refusals' else 'marched',capture=not failed)
 fallback=(R/'public/docs/assets/solidsl_gallery/cross_check_code_.kt').read_text()
 a=fallback.index('val part =');b=fallback.index('// ---',a)
 fallback=fallback[:a]+'val part = smoothUnion(cuboid(1.0,1.0,1.0),sphere(0.6),k=0.2)\n'+fallback[b:]
 await m.program('cross_check-fallback',fallback,'check(part.exactRefusal() != null);proof("full authored fallback comparison displayed")',channel='marched',capture=True)
 inverse=(R/'dev_docs/agents/demos/solidsl_inverse/inverse_code_.kt').read_text()
 await m.program('inverse',inverse,'check(fit.loss < fit.initialLoss);proof(fit.values.toList())',channel='target',capture=True)
 # Same caption selector, with the exact and smooth-tree cases actually built.
 await m.program('exactness-labels','''
import afield.lang.solidsl.*
import afield.geometry.solid.manifold.Solid
Solid.scope {
    for ((shape, expected) in listOf((cuboid(1.0,1.0,1.0) + sphere(0.6)) to true, smoothUnion(cuboid(1.0,1.0,1.0),sphere(0.6),k=0.2) to false)) {
        val coarse=shape.buildSolid(SolidQuality(edgeLengthFraction=1.0/15.0))
        val fine=shape.buildSolid(SolidQuality(edgeLengthFraction=1.0/20.0))
        check((coarse.isExact && fine.isExact) == expected)
    }
    Unit
}
''', 'proof("exact and field fallback labels follow isExact")')
asyncio.run(main())
