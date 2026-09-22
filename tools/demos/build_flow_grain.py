"""Build the self-contained flow starting-point transcript from its editable sources.

Run with uv run --group backdoor python tools/demos/build_flow_grain.py.
The YAML is the distributable installer; these files are its readable authoring source.
"""
from pathlib import Path
import yaml

ROOT = Path(__file__).resolve().parents[2]
SOURCES = ROOT / 'dev_docs/agents/demos/flow_grain'

class Readable(yaml.SafeDumper):
    pass

def text(dumper, value):
    return dumper.represent_scalar('tag:yaml.org,2002:str', value, style='|' if '\n' in value else None)
Readable.add_representer(str, text)

def build():
    boxes = [
        ('renderer', '1. Renderer — launch / stop', {'code':'renderer.kt'}),
        ('resources', '2. Resident tiles and resources', {'code':'resources.kt'}),
        ('inputs', '3. Frame inputs and reset boundary', {'code':'inputs.kt'}),
        ('helpers', 'flow_helpers', {'compute':'kernel.glsl'}),
        ('advect', '4. Advect and inject', {'code':'advect.kt','compute':'advect.comp'}),
        ('divergence', '5. Divergence', {'code':'divergence.kt','compute':'divergence.comp'}),
        ('pressure', '6. Pressure — 24 iterations', {'code':'pressure.kt','compute':'pressure.comp'}),
        ('project', '7. Project', {'code':'project.kt','compute':'project.comp'}),
        ('particles', '8. Advect finite grains', {'code':'particles.kt','compute':'particles.comp'}),
        ('grain', '9. Grain and correspondence RTT', {'code':'grain.kt','vertex':'fullscreen.vert','fragment':'grain.frag'}),
        ('resolve', '10. Present color or motion', {'code':'resolve.kt','vertex':'fullscreen.vert','fragment':'resolve.frag'}),
        ('strokes', '11. Velocity strokes', {'code':'strokes.kt','vertex':'strokes.vert','fragment':'strokes.frag'}),
        ('controls', 'Parameters — edit and execute', {'code':'controls.kt'}),
        ('reset', 'Reset — execute', {'code':'reset.kt'}),
        ('profile', 'Profile — 180 rendered frames', {'code':'profile.kt'}),
    ]
    ops=[]
    for i,(key,name,files) in enumerate(boxes):
        ops.append({'create': {'box':f'flow_{key}', 'name':name,
            'frame': {'x':80+(i//6)*430.,'y':80+(i%6)*150.,'w':370.,'h':100.},
            'properties':{prop:(SOURCES/file).read_text() for prop,file in files.items()}}})
    for key,_,_ in boxes[1:]:
        ops.append({'connect': {'parent':'flow_renderer' if key=='resources' else 'flow_resources','child':f'flow_{key}'}})
    for key,_,files in reversed(boxes):
        if key not in ('renderer','helpers'):
            ops.append({'stop': {'box':f'flow_{key}'}})
    for key,_,files in boxes:
        if key not in ('controls','reset','profile','helpers'):
            ops.append({'launch': {'box':f'flow_{key}', 'property':'code'}})
    ops.append({'wait':{'frames':120}})
    return yaml.dump({'name':'flow_grain', 'description':'Bounded scrolling fluid tiles, world-space grain, velocity strokes and motion correspondence. Normal launch/stop; frame-boundary reset; existing profiling timeline.', 'operations':ops},Dumper=Readable,sort_keys=False,allow_unicode=True)

if __name__=='__main__':
    path=ROOT/'dev_docs/agents/transcripts/flow_grain.yaml'
    path.write_text(build())
    print(path)
