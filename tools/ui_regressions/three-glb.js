const delay = ms => new Promise(resolve => setTimeout(resolve, ms)), checks = [];
const expect = (value, label) => { checks.push({label, passed:!!value}); if (!value) throw new Error(label); };
let frame;
for (let i=0; i<100; i++) {
    frame = [...document.querySelectorAll('iframe')].find(f => { try { return f.src.includes('_xray') && f.contentWindow.FieldInspector?.snapshot().partIds.length; } catch { return false; } });
    if (frame) break; await delay(100);
}
if (!frame) throw new Error('Missing GLB export fixture');
const w=frame.contentWindow, d=w.document, api=w.FieldInspector, original=api.snapshot();
function decode(data) {
    const view=new DataView(data);
    expect(view.getUint32(0,true)===0x46546c67 && view.getUint32(4,true)===2 && view.getUint32(8,true)===data.byteLength, 'Valid binary glTF header and length');
    const jsonLength=view.getUint32(12,true), json=JSON.parse(new TextDecoder().decode(new Uint8Array(data,20,jsonLength)));
    const binStart=28+jsonLength;
    function accessor(index) {
        const a=json.accessors[index], b=json.bufferViews[a.bufferView], width={SCALAR:1,VEC3:3,VEC4:4}[a.type];
        expect(a.componentType===5126, 'Exported attribute is a Float accessor');
        return new Float32Array(data,binStart+(b.byteOffset||0)+(a.byteOffset||0),a.count*width);
    }
    return {json, accessor};
}
const primitive = (g,id) => { const node=g.json.nodes.find(n=>n.extras?.field?.id===id); return g.json.meshes[node.mesh].primitives[0]; };
try {
    expect(typeof w.FieldViewer.exportGLB==='function' && d.getElementById('export-glb'), 'Standard viewer exposes the export button and agent API');
    const front=api.sources().find(s=>s.source.id==='front').id, rear=api.sources().find(s=>s.source.id==='rear').id;
    const normal=decode(await w.FieldViewer.exportGLB({download:false}));
    const n=primitive(normal,'front');
    expect(normal.json.accessors[n.attributes.POSITION].count===6, 'Indexed two-triangle surface is exported completely');
    expect(!normal.json.materials[n.material].alphaMode, 'Opaque authored surface remains opaque in normal export');
    const modes=normal.json.meshes.flatMap(m=>m.primitives.map(p=>p.mode ?? 4));
    expect(modes.includes(4) && modes.includes(1) && modes.includes(0), 'Surfaces, lines and points all survive export');
    api.xray(true,.18);
    // Let the initial iframe/chrome resize settle before testing export isolation.
    await d.fonts.ready;await delay(200);
    const before=JSON.stringify(api.snapshot());
    const ghost=decode(await w.FieldViewer.exportGLB({download:false})), p=primitive(ghost,'front'), m=ghost.json.materials[p.material];
    expect(m.alphaMode==='BLEND' && Math.abs(m.pbrMetallicRoughness.baseColorFactor[3]-.18)<1e-7, 'X-ray opacity is encoded in portable BLEND materials');
    expect(p.attributes.COLOR_0===undefined && m.pbrMetallicRoughness.baseColorFactor[0]>.59, 'Uniform vertex colors fold losslessly into the material');
    const strokes=ghost.json.meshes.flatMap(m=>m.primitives).filter(p=>p.mode===1);
    expect(strokes.some(p=>{const c=ghost.accessor(p.attributes.COLOR_0);return Array.from(c).some((v,i)=>i%4===3 && Math.abs(v-.56)<1e-5);}), 'Translucent line alpha includes the X-ray edge boost');
    expect(JSON.stringify(api.snapshot())===before, 'Export does not mutate the live view or annotation selection: '+JSON.stringify(Object.fromEntries(Object.entries(api.snapshot()).filter(([k,v])=>JSON.stringify(v)!==JSON.stringify(JSON.parse(before)[k])).map(([k,v])=>[k,{before:JSON.parse(before)[k],after:v}]))));
    api.hide(front);
    const hidden=decode(await w.FieldViewer.exportGLB({download:false}));
    expect(!hidden.json.nodes.some(n=>n.extras?.field?.id==='front') && hidden.json.nodes.some(n=>n.extras?.field?.id==='rear'), 'Hidden objects are excluded from GLB');
    api.restore({...original,xray:true});api.section([1,0,0],.5);
    const section=decode(await w.FieldViewer.exportGLB({download:false}));
    const positions=section.accessor(primitive(section,'front').attributes.POSITION);
    expect(Array.from(positions).filter((_,i)=>i%3===0).every(v=>v>=-1e-5), 'Section is baked into actual clipped triangles');
    api.restore(original);
    let download;
    const click=w.HTMLAnchorElement.prototype.click;
    w.HTMLAnchorElement.prototype.click=function(){download={name:this.download,url:this.href};};
    try {
        d.getElementById('export-glb').click();
        for(let i=0;i<100&&!download;i++) await delay(50);
        expect(download?.name.endsWith('.glb') && download.url.startsWith('blob:'), 'Button prepares a named GLB download');
        expect((await (await w.fetch(download.url)).arrayBuffer()).byteLength>100, 'Download contains a real binary asset');
    } finally { w.HTMLAnchorElement.prototype.click=click; }
    api.hide(front);api.hide(rear);
    // Also verify exporter behavior beyond the authored fixture: active buffer capacity, transforms,
    // authored alpha, millimetres, wire child visibility and no mutation of source resources.
    const direct=await w.eval(`(async()=>{
        const THREE=await import('three'); const {exportViewerGLB}=await import('/threejs_export.js');
        const root=new THREE.Group(), geometry=new THREE.BufferGeometry();
        geometry.setAttribute('position',new THREE.Float32BufferAttribute([0,0,0,100,0,0,0,100,0,999,999,999],3));
        geometry.setAttribute('alpha',new THREE.Float32BufferAttribute([.25,.5,.75,1],1));
        geometry.setDrawRange(0,3);
        const material=new THREE.MeshStandardMaterial({opacity:.5,transparent:true});
        const mesh=new THREE.Mesh(geometry,material);mesh.position.set(200,0,0);root.add(mesh);
        const data=await exportViewerGLB(root,{xray:true,opacity:.2,units:'mm'});
        const unchanged=material.opacity===.5&&geometry.getAttribute('position').count===4&&mesh.position.x===200;
        root.visible=false;let empty=false;try{await exportViewerGLB(root)}catch(e){empty=e.message.includes('no visible geometry')}
        geometry.dispose();material.dispose();return {data,unchanged,empty};
    })()`);
    const fixture=decode(direct.data), fp=fixture.json.meshes[0].primitives[0], xyz=fixture.accessor(fp.attributes.POSITION);
    expect(xyz.length===9 && xyz[0]===200 && xyz[3]===300, 'Only active draw range is exported and object transform is baked');
    expect(fixture.json.nodes.some(n=>n.scale?.[0]===.001 || n.matrix?.[0]===.001), 'Millimetres are converted to glTF metres');
    expect(fixture.json.accessors[fp.attributes.COLOR_0].type==='VEC4' && fixture.accessor(fp.attributes.COLOR_0)[3]===.25 && Math.abs(fixture.json.materials[fp.material].pbrMetallicRoughness.baseColorFactor[3]-.1)<1e-6, 'Varying vertex opacity uses standard RGBA and composes with authored material opacity and X-ray');
    expect(direct.unchanged && direct.empty, 'Detached export preserves source resources and reports empty scenes');
    api.restore(original);await delay(1200);const count=w.FieldViewer.renderStats().frames;await delay(600);
    expect(count===w.FieldViewer.renderStats().frames, 'Export does not start idle rendering');
    return {ok:true,checks};
} catch(e) {return {ok:false,reason:String(e),checks};}
finally {api.restore(original);}
