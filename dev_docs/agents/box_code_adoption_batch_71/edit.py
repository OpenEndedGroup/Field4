from pathlib import Path
import json,yaml,re
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;rows=[]
def save(p,b):
 a=p.read_text()
 if a!=b:p.write_text(b);rows.append({'path':str(p.relative_to(R)),'before':a,'after':b})
p=R/'public/docs/ai4_experiments.md';s=p.read_text();s=s.replace('```diffstatic {data-lang=kotlin}\naiTool', '```diffstatic {data-lang=kotlin}\nprop<() -> PaintCanvas>(studioCanvas)\nprop<() -> Unit>(refreshStudio)\n\naiTool',1).replace('studio.properties["studio.canvas"] as PaintCanvas','studio.studioCanvas()').replace('(studio.properties["studio.refresh"] as () -> Unit)()','studio.refreshStudio()');s=s.replace('it with a paint brush, and asks the Studio to show the result:', 'it with a paint brush, and asks the Studio to show the result. Studio publishes typed\n`studioCanvas` and `refreshStudio` callbacks; the canvas callback reads the current easel even\nafter a conversation rewind replaces it:');save(p,s)
p=R/'dev_docs/agents/transcripts/ai4_paint_studio.yaml';a=p.read_text();ed=[]
def walk(n):
 if isinstance(n,yaml.MappingNode):
  for k,v in n.value:
   if k.value=='code' and isinstance(v,yaml.ScalarNode):
    s=v.value
    if 'thisBox.properties["studio.refresh"] = { show() }' in s:
     s=s.replace('thisBox.properties["studio.refresh"] = { show() }','''prop<() -> PaintCanvas>(studioCanvas)
prop<() -> Unit>(refreshStudio)
studioCanvas = { canvas() }
refreshStudio = { show() }''')
    if 'val canvas = studio.properties["studio.canvas"] as PaintCanvas' in s:
     s=s.replace('import kotlin.random.Random','import kotlin.random.Random\n\nprop<() -> PaintCanvas>(studioCanvas)\nprop<() -> Unit>(refreshStudio)').replace('studio.properties["studio.canvas"] as PaintCanvas','studio.studioCanvas()').replace('(studio.properties["studio.refresh"] as () -> Unit)()','studio.refreshStudio()')
    if s!=v.value:ed.append((v.start_mark.index,v.end_mark.index,'|\n'+''.join(' '*(k.start_mark.column+2)+x+'\n' if x else '\n' for x in s.splitlines())))
   else:walk(v)
 elif isinstance(n,yaml.SequenceNode):
  for x in n.value:walk(x)
walk(yaml.compose(a));b=a
for x,y,s in reversed(ed):b=b[:x]+s+b[y:]
save(p,b)
p=R/'templates/advanced_remote/8b78e419-cd1e-4111-96ab-899eb3ed47d1_fragment_.glsl';save(p,'''precision highp float;
precision highp int;

// The remote JavaScript scene supplies UVs and the video texture.
varying vec3 vNormal;
varying vec3 pos;
varying vec2 vUv;
uniform sampler2D map;

void main() {
    // Keep the sampled RGB opaque; remove pixels whose blue channel exceeds the key.
    gl_FragColor = vec4(texture2D(map, vUv).rgb, 1.0);
    if (gl_FragColor.b > 0.9) discard;
}
''')
p=R/'templates/advanced_remote/94f15e77-0d57-498c-a573-b7af7ac70a07_code_.kt';s=p.read_text();assert '"""' not in s and '$' not in s
save(p,'''// Reference shelf: this Kotlin box displays JavaScript recipes; it does not run them.
markdown(
    "l-system-reference",
    """
# L-system recipe reference

These are JavaScript configurations for the neighbouring **L-System** box.
Run that box first, then paste one configuration and the final four lines into its
JavaScript editor. Its `LSystem` and `drawLSystem` definitions and remote scene are required.
The recipes below do not execute in this Kotlin box.

```javascript
'''+s+'''```
    """.trimIndent(),
)
''')
p=R/'public/docs/interactive_guides.md';s=p.read_text();s=s.replace('val guide = thisBox\n\n','').replace('val host = thisBox\n','').replace('selectBox(guide','selectBox(thisBox').replace('selectBox(host','selectBox(thisBox').replace('targetBox = host','targetBox = thisBox').replace('host.name =','name =')
# Source line targets move with the removed setup, including the note text and alt descriptions.
s=s.replace('line = 13, highlight = 13','line = 11, highlight = 11').replace('line = 5, highlight = 5','line = 3, highlight = 3').replace('line = 17, highlight = 17','line = 16, highlight = 16').replace('line = 17,','line = 16,').replace('line 13','line 11').replace('line 17','line 16')
save(p,s)
(H/'changes.json').write_text(json.dumps(rows,indent=2)+'\n')
