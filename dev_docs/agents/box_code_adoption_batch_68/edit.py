from pathlib import Path
import json,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;rows=[]
def save(p,b):
 a=p.read_text()
 if a!=b:p.write_text(b);rows.append({'path':str(p.relative_to(R)),'before':a,'after':b})
folder=R/'public/docs/assets/canonical_graphics'
for p in folder.glob('*_code_.kt'):
 a=p.read_text();b=a.replace('/Users/marc/Desktop/bbc.jpg','public/docs/assets/afield_four_graphics/docs-checker-texture.png').replace('/Users/marc/Desktop/bbc2.jpg','public/docs/assets/rt-uv-gradient.png')
 if a!=b:b='// Bundled textures make this lesson runnable; change these paths to try your own images.\n'+b
 if p.name.startswith('f1d2cbea'):b=b.replace('// var (module, meshes) = gltfModelWithTextureHere("/Users/marc/Desktop/badTexture.gltf")\n','')
 save(p,b)
p=folder/'f1d2cbea-b20d-4794-b9f7-37b847dcdeb6_fragment_.glsl';save(p,'''#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 3) in vec4 vColor;
layout(location = 2) in vec2 vUV;

layout(set = 0, binding = 1) uniform sampler2D uTextures[6];

void main() {
    // Show the asset's sampled texture; edit vUV here when inspecting its mapping.
    uFragColor = texture(uTextures[0], vUV);
}
''')
p=R/'dev_docs/agents/transcripts/audio_ai_underfit_mancini.yaml';a=p.read_text();edits=[]
def visit(n):
 if isinstance(n,yaml.MappingNode):
  for k,v in n.value:
   if k.value=='code' and isinstance(v,yaml.ScalarNode):
    code=v.value
    if 'val sourcePath = Path.of("/home/marc/' in code:
     code=code.replace('import java.nio.file.Path','import java.nio.file.Path\nimport java.nio.file.Files')
     code=code.replace('val client = FieldAudioClient()\n','')
     code=code.replace('val sourcePath = Path.of("/home/marc/temp/music/audio-project/underfit-proof/source-mjq-skating.wav")','''// Supply a WAV with at least 12 seconds of source audio for this request.
val sourcePath = Path.of(requireNotNull(System.getenv("FIELD_AUDIO_UNDERFIT_SOURCE_WAV")) {
    "Set FIELD_AUDIO_UNDERFIT_SOURCE_WAV to your source WAV (at least 12 seconds)."
})
require(Files.isRegularFile(sourcePath)) { "Source WAV not found: $sourcePath" }''')
     code=code.replace('val loraRoot = Path.of(requireNotNull(System.getenv("FIELD_AUDIO_UNDERFIT_LORA_ROOT")))','''val loraRoot = Path.of(requireNotNull(System.getenv("FIELD_AUDIO_UNDERFIT_LORA_ROOT")) {
    "Set FIELD_AUDIO_UNDERFIT_LORA_ROOT to the worker's checkpoint directory."
})''')
     code=code.replace('val source = client.importWav(sourcePath).get()','val client = FieldAudioClient()\nval source = client.importWav(sourcePath).get()')
    else:code=code.replace('Set <code>FIELD_AUDIO_UNDERFIT_LORA_ROOT</code> and start','Set <code>FIELD_AUDIO_UNDERFIT_SOURCE_WAV</code> to your source WAV (at least 12 seconds), set <code>FIELD_AUDIO_UNDERFIT_LORA_ROOT</code>, and start')
    if code!=v.value:
     indent=k.start_mark.column+2;edits.append((v.start_mark.index,v.end_mark.index,'|\n'+''.join(' '*indent+x+'\n' if x else '\n' for x in code.splitlines())))
   else:visit(v)
 elif isinstance(n,yaml.SequenceNode):
  for v in n.value:visit(v)
visit(yaml.compose(a));b=a
for x,y,c in sorted(edits,reverse=True):b=b[:x]+c+b[y:]
save(p,b)
p=R/'public/docs/audio_ai_underfit_mancini.md';save(p,p.read_text().replace('export FIELD_AUDIO_UNDERFIT_LORA_ROOT=','export FIELD_AUDIO_UNDERFIT_SOURCE_WAV=/path/to/your-source.wav  # at least 12 seconds\nexport FIELD_AUDIO_UNDERFIT_LORA_ROOT=',1))
p=R/'public/docs/afield_four_graphics.md';save(p,p.read_text().replace('Neither carries a figure: both need a glTF file on disk, and the repository ships none.','Neither carries a figure: these saved lessons use an external glTF file on disk.'))
(H/'changes.json').write_text(json.dumps(rows,indent=2)+'\n')
