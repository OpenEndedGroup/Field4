from pathlib import Path
import json,re,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;rows=[]
def save(p,b):
 a=p.read_text()
 if a!=b:p.write_text(b);rows.append({'path':str(p.relative_to(R)),'before':a,'after':b})
def transform(c):
 match=re.search(r'prop<(LearnedShader|ReconstructionDemo|OpenDlssNrImage|ClusterGeometryDemo|GpuShaderLearningView|ShaderLearningView|OpticalFlowDemo|PathTracedReconstruction)>\((lab|study)\)',c)
 if not match:return c
 typ,export=match.groups();owned='ownedStudy' if export=='study' else 'ownedLab';scope='runScope'
 c=re.sub(r'fromContextWithNameOrNull<'+typ+r'>\("'+export+r'"\)\?\.close\(\)\n','',c)
 # A previously revised demo already retains its own exact instance; keep that ownership and its cleanup.
 already=('val '+owned+' =') in c
 if already:return c
 c='import afield.runtime.lifecycle.RunScope\n'+c
 c=c.replace(match[0],match[0]+'\nval runScope = requireNotNull(RunScope.current()) { "Run this example in a Field box." }')
 # Replace internal reads before publishing the new per-run instance below.
 c=re.sub(r'\b'+export+r'\.',owned+'.',c)
 if typ=='LearnedShader':
  c=c.replace('lab = LearnedShader(renderer, cooperative = cooperative)','var ownedLab = LearnedShader(renderer, cooperative = cooperative)\nlab = ownedLab\nrunScope.own { ownedLab.close() }',1)
  c=c.replace('ownedLab.close()\n                lab = next.attach()', '''try { next.attach() } catch (failure: Throwable) { next.close(); throw failure }
                val previous = ownedLab
                ownedLab = next
                lab = next
                previous.close()''')
 elif typ=='ReconstructionDemo':
  factory='''fun createView(mode: ReconstructionMode?): ReconstructionDemo {
    val next = ReconstructionDemo(renderer, mode, attach = false)
    try {
        // Each mode starts from the edited properties, including after a successful reload.
        next.reload(requireNotNull(vertex) { "Missing vertex shader" }, requireNotNull(fragment) { "Missing fragment shader" }).getOrThrow()
        return next.attach()
    } catch (failure: Throwable) {
        next.close()
        throw failure
    }
}
var ownedLab = createView(modes[active])
lab = ownedLab
runScope.own { ownedLab.close() }'''
  c=c.replace('lab = ReconstructionDemo(renderer, modes[active])',factory,1).replace('val next = ReconstructionDemo(renderer, modes[requested], attach = false)','val next = createView(modes[requested])')
  c=c.replace('ownedLab.close()\n                lab = next.attach()', 'val previous = ownedLab\n                ownedLab = next\n                lab = next\n                previous.close()')
  # The old loop had no cleanup at all; resource ownership is also established before setup above.
  c=c.rstrip()+ '\n    .end { ownedLab.close() }\n'
 else:
  # All remaining view creations are on one source line, including the GPU state callback.
  pattern=r'(?m)^(\s*)'+export+r' = ([^\n]+)$'
  m=re.search(pattern,c);assert m,(typ,'creation not found')
  indent=m[1];expr=m[2];replacement=indent+'val '+owned+' = runScope.own('+expr+')\n'+indent+export+' = '+owned
  c=c[:m.start()]+replacement+c[m.end():]
 return c
names=['learned_shader','neural_reconstruction','opendlssnr','computed_ray_geometry','gpu_shader_learning','learned_from_module','optical_flow','path_traced_reconstruction']
for name in names:
 p=R/f'dev_docs/agents/demos/{name}/example.kt'
 if p.exists():save(p,transform(p.read_text()))
 p=R/f'dev_docs/agents/transcripts/{name}.yaml'
 if not p.exists():continue
 a=p.read_text();ed=[]
 def walk(n):
  if isinstance(n,yaml.MappingNode):
   for k,v in n.value:
    if k.value=='code' and isinstance(v,yaml.ScalarNode):
     c=transform(v.value)
     if c!=v.value:ed.append((v.start_mark.index,v.end_mark.index,'|\n'+''.join(' '*(k.start_mark.column+2)+x+'\n' if x else '\n' for x in c.splitlines())))
    else:walk(v)
  elif isinstance(n,yaml.SequenceNode):
   for x in n.value:walk(x)
 walk(yaml.compose(a));b=a
 for x,y,c in reversed(ed):b=b[:x]+c+b[y:]
 save(p,b)
p=R/'public/docs/assets/opendlssnr/example.kt';save(p,transform(p.read_text()))
# Late old cleanup must not detach a replacement's same-name presentation module.
for relative in ['graphics/shader/learning/LearnedShader.kt','graphics/shader/learning/ShaderLearningView.kt','graphics/shader/learning/GpuShaderLearningView.kt','graphics/render/ClusterGeometryDemo.kt','media/reconstruction/ReconstructionDemo.kt']:
 p=R/'src/jvmMain/kotlin/afield'/relative;s=p.read_text();s=s.replace('renderer.renderList.preTargets.remove(name,this);renderer.renderList.modules.remove(name)', 'if(renderer.renderList.preTargets.remove(name,this))renderer.renderList.modules.remove(name)')
 if relative.endswith('ReconstructionDemo.kt'):
  s=s.replace('        if(attach)attach()','        afield.runtime.lifecycle.RunScope.current()?.own(this)\n        if(attach)attach()')
 save(p,s)
p=R/'src/jvmMain/kotlin/afield/graphics/render/neural/OpenDlssNrImage.kt';s=p.read_text().replace('renderer.renderList.modules.remove(name)','renderer.renderList.modules.remove(name,blit)');save(p,s)
p=R/'src/jvmMain/kotlin/afield/media/vision/OpticalFlowDemo.kt';s=p.read_text().replace('init { output.blitToScreen(name=name) }','private val blit = output.blitToScreen(name=name)').replace('renderer.renderList.modules.remove(name)','renderer.renderList.modules.remove(name,blit)');save(p,s)
p=R/'public/docs/neural_reconstruction.md';s=p.read_text().replace('// experiment.close() when replacing it','// The constructing box run owns this view; Stop or rerun closes it.').replace('`renderer` comes from the parent renderer box.', '`ReconstructionDemo` registers with the constructing box run immediately. Stop or rerun\ndetaches its exact view and releases its resources. `renderer` comes from the parent renderer box.');save(p,s)
(H/'changes.json').write_text(json.dumps(rows,indent=2)+'\n')
