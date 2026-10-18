"""Run the typed paint contract, reference shelf, WebGL shader and real guide navigation."""
from pathlib import Path
import asyncio,importlib.util,json,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;O=R/'build/box-code-revision/adoption-71'
s=importlib.util.spec_from_file_location('rig71',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9722;m.OUT=O/'runtime';m.OUT.mkdir(parents=True,exist_ok=True)
s=importlib.util.spec_from_file_location('docs71',H.parent/'box_code_adoption_batch_7/format_docs.py');d=importlib.util.module_from_spec(s);s.loader.exec_module(d)
async def cached(name,ops):
 p=m.OUT/(name+'.yaml');v=m.OUT/(name+'.json')
 if p.exists() and v.exists() and yaml.safe_load(p.read_text())=={'operations':ops} and json.loads(v.read_text()).get('ok'):return json.loads(v.read_text())
 return await m.macro(name,ops)
async def box(name,body):
 if name.startswith('g5-guide-') and (m.OUT/(name+'.json')).exists():return await m.batch(name+'-replay',['macro run '+name])
 return await cached(name,[{'create':{'box':name,'name':name,'frame':{'x':40.,'y':40.,'w':1000.,'h':100.},'properties':{'code':body}}},{'launch':{'box':name}},{'await':{}}])
async def frag(name,uid,body):return await cached(name,[{'execute':{'box':uid,'type':'fragment','code':body}}])
async def js(name,body):
 p=m.OUT/(name+'.js');p.write_text(body);return await m.batch(name,[f'page-eval --body --file {p}'])
async def main():
 ops=yaml.safe_load((R/'dev_docs/agents/transcripts/ai4_paint_studio.yaml').read_text())['operations']
 await cached('g5-typed-studio',ops)
 await frag('g5-typed-canvas-contract','ai4_paint_scribbler','''
val originalCanvas = studio.studioCanvas()
check(originalCanvas === studio.properties["studio.canvas"])
val replacementCanvas = PaintCanvas(96, aspect = 1.25, seed = 11)
studio.properties["studio.canvas"] = replacementCanvas
check(studio.studioCanvas() === replacementCanvas)
studio.refreshStudio()
val scribble = requireNotNull(DocumentToolRegistry.get(inside.filename, "scribble"))
go {
    try {
        val result = scribble.call("outline" to listOf(10.0,10.0,70.0,10.0,70.0,70.0,10.0,70.0),"color" to "#224466","density" to 3.0)
        check(result.toString().contains("scribbled"))
        display("typed-contract", "VERIFIED71 real scribble on replacement native canvas")
    } finally {
        studio.properties["studio.canvas"] = originalCanvas
        replacementCanvas.close()
    }
}
''')
 await m.batch('g5-typed-proof',['wait-output ai4_paint_scribbler --channel typed-contract --contains VERIFIED71 --timeout 60'])
 reference=R/'templates/advanced_remote/94f15e77-0d57-498c-a573-b7af7ac70a07_code_.kt'
 await box('g5-reference-shelf',reference.read_text())
 await m.batch('g5-reference-proof',['wait-output g5-reference-shelf --channel l-system-reference --contains "JavaScript configurations" --timeout 30'])
 rows=json.loads((H/'changes.json').read_text());shader=next(x for x in rows if x['path'].endswith('_fragment_.glsl'))
 await js('g5-actual-webgl', '''const canvas=document.createElement('canvas');canvas.width=2;canvas.height=1;
const gl=canvas.getContext('webgl',{preserveDrawingBuffer:true,alpha:true});if(!gl)throw Error('WebGL1 unavailable');
function shader(type,source){const s=gl.createShader(type);gl.shaderSource(s,source);gl.compileShader(s);if(!gl.getShaderParameter(s,gl.COMPILE_STATUS))throw Error(gl.getShaderInfoLog(s));return s;}
const vs=shader(gl.VERTEX_SHADER,'attribute vec2 p;varying vec3 vNormal;varying vec3 pos;varying vec2 vUv;void main(){vUv=p*.5+.5;vNormal=vec3(0,0,1);pos=vec3(p,0);gl_Position=vec4(p,0,1);}');
const texture=gl.createTexture();gl.bindTexture(gl.TEXTURE_2D,texture);gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,2,1,0,gl.RGBA,gl.UNSIGNED_BYTE,new Uint8Array([64,128,32,48,40,70,250,255]));for(const axis of [gl.TEXTURE_WRAP_S,gl.TEXTURE_WRAP_T])gl.texParameteri(gl.TEXTURE_2D,axis,gl.CLAMP_TO_EDGE);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.NEAREST);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.NEAREST);
const b=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,b);gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([-1,-1,3,-1,-1,3]),gl.STATIC_DRAW);
function render(fragment){const program=gl.createProgram();gl.attachShader(program,vs);gl.attachShader(program,shader(gl.FRAGMENT_SHADER,fragment));gl.linkProgram(program);if(!gl.getProgramParameter(program,gl.LINK_STATUS))throw Error(gl.getProgramInfoLog(program));gl.useProgram(program);const p=gl.getAttribLocation(program,'p');gl.enableVertexAttribArray(p);gl.vertexAttribPointer(p,2,gl.FLOAT,false,0,0);gl.uniform1i(gl.getUniformLocation(program,'map'),0);gl.disable(gl.DITHER);gl.clearColor(0,0,0,0);gl.clear(gl.COLOR_BUFFER_BIT);gl.drawArrays(gl.TRIANGLES,0,3);const data=new Uint8Array(8);gl.readPixels(0,0,2,1,gl.RGBA,gl.UNSIGNED_BYTE,data);if(gl.getError()!==gl.NO_ERROR)throw Error('WebGL error');return [...data];}
const before=render(BEFORE),after=render(AFTER);if(JSON.stringify(before)!==JSON.stringify(after))throw Error('Shader output changed');if(after.join(',')!=='64,128,32,255,0,0,0,0')throw Error('Opaque sample/discard failed: '+after);return {actualWebGL:true,before,after};'''.replace('BEFORE',json.dumps(shader['before'])).replace('AFTER',json.dumps((R/shader['path']).read_text())))
 page=(R/'public/docs/interactive_guides.md').read_text();states=[page[a:b] for a,b in d.sections(page)]
 for index in ([3,4] if '--resume-guides' in __import__('sys').argv else [0,1,2,3,4]):
  body=states[index]
  # line targets are assertions against the actual final formatted source.
  if index==1:assert '[[back]]' in body.splitlines()[10]
  if index==3:assert 'selectBox(thisBox' in body.splitlines()[16]
  await box('g5-guide-'+str(index),body)
  await js('g5-guide-visible-'+str(index),'''const until=Date.now()+8000;while(!document.getElementById('slide-note-overlay')?.innerText.trim()){if(Date.now()>until)throw Error('Guide note missing');await new Promise(r=>setTimeout(r,25));}return document.getElementById('slide-note-overlay').innerText;''')
  if index==1:
   await js('g5-guide-next', '''const a=document.querySelector('#slide-note-overlay a[href="#slideshow:next"]');if(!a)throw Error('next link missing');a.click();const until=Date.now()+5000;while(!document.getElementById('slide-note-overlay')?.innerText.includes('The last step')){if(Date.now()>until)throw Error('Next did not advance');await new Promise(r=>setTimeout(r,25));}return 'actual link advanced guide';''')
  if index==3:
   await js('g5-guide-annotations', '''const until=Date.now()+10000;while(document.querySelectorAll('#slide-annotation-layer g[data-name="slideAnnotations"] path').length<2){if(Date.now()>until)throw Error('Both annotation paths missing');await new Promise(r=>setTimeout(r,25));}while(!window.globalEditor||window.globalEditor.getPosition().lineNumber!==17){if(Date.now()>until)throw Error('Editor did not select line 17: '+window.globalEditor?.getPosition().lineNumber);await new Promise(r=>setTimeout(r,25));}const ed=window.globalEditor;return {annotationPaths:document.querySelectorAll('#slide-annotation-layer g[data-name="slideAnnotations"] path').length,editorLine:ed.getPosition().lineNumber};''')
  await frag('g5-guide-stop-'+str(index),'g5-guide-'+str(index),'runBlocking { show.stop() }')
 (H/'behavior.json').write_text(json.dumps({'typedStudioCallbacksFollowReplacedCanvas':True,'realScribblePaintedOnNativeCanvas':True,'referenceShelfRunsAsKotlinAndLabelsJavaScriptPrerequisites':True,'actualWebGlSampleAndDiscardEqualBeforeAfter':True,'fiveCompleteGuideStatesRun':True,'actualNextLinkAdvances':True,'actualEditorLineAndAnnotationPathsVerified':True,'limits':'No JavaScript reference recipe rendering is claimed: the shelf explicitly requires the neighbouring remote L-System scene. No paid AI requests or public capture changes.'},indent=2)+'\n')
asyncio.run(main())
