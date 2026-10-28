'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const source = fs.readFileSync(path.join(__dirname,'../src/commonMain/resources/offline-outputs.js'),'utf8');
function viewer(data) {
    const window = {};
    vm.runInNewContext(source,{window,URL,location:new URL('https://example.test/nested/site/offline.html'),
        fetch:async () => ({ok:true,json:async()=>data})});
    return window.FieldOfflineOutputs;
}
(async () => {
    const outputs = [
        {uid:'a',channel:'lines',path:'outputs/0.html',open:true,width:480,height:200,warnings:[]},
        {uid:'a',channel:'failed',warnings:['Frame unavailable']},
        {uid:'b',channel:'plot',path:'outputs/1.html',open:false,width:700,height:400,warnings:[]}
    ];
    const api = viewer({version:1,outputs});
    await api.load(new URLSearchParams('outputs=document/outputs.json'));
    assert.equal(api.forBox('a').length,2);
    assert.equal(api.forBox('unknown').length,0);
    assert.equal(api.bounds({id:'a',properties:{frame:[10,20,300,80]}}).h,340);
    assert.equal(api.bounds({id:'b',properties:{frame:[0,0,300,80]}}).h,110);
    assert.equal(api.info(),'2 captured outputs · 1 capture warnings');
    await assert.rejects(viewer({version:2,outputs:[]}).load(new URLSearchParams('outputs=document/outputs.json')),/Unsupported/);
    for (const entry of [null,{uid:'x',channel:'bad',path:'../../index.html'},
        {uid:'x',channel:'bad',path:'https://other.test/evil.html'},
        {uid:'x',channel:'bad',warnings:'not an array'}]) {
        await assert.rejects(viewer({version:1,outputs:[entry]}).load(new URLSearchParams('outputs=document/outputs.json')),/Invalid/);
    }
    await assert.rejects(api.load(new URLSearchParams('outputs=https://other.test/outputs.json')),/beside/);
    const plain = viewer(null);
    await plain.load(new URLSearchParams('file=document/metadata.edn'));
    assert.equal(plain.info(),'');
    const entry = {file:'assets/web_publications/example/document/metadata.edn',outputs:'assets/web_publications/example/document/outputs.json'};
    const published = viewer({version:1,documents:{'assets/example/metadata.edn':entry}});
    const params = new URLSearchParams('file=assets/example/metadata.edn&select=box');
    await published.resolvePublication(params);
    assert.equal(params.get('file'),entry.file);
    assert.equal(params.get('outputs'),entry.outputs);
    assert.equal(params.get('select'),'box');
    const missing = new URLSearchParams('file=assets/other/metadata.edn');
    await published.resolvePublication(missing);
    assert.equal(missing.get('file'),'assets/other/metadata.edn');
    const explicit = new URLSearchParams('file=assets/example/metadata.edn&outputs=document/outputs.json');
    await published.resolvePublication(explicit);
    assert.equal(explicit.get('file'),'assets/example/metadata.edn');
    const invalid = viewer({version:1,documents:{'assets/example/metadata.edn':{...entry,file:'../escape'}}});
    await assert.rejects(invalid.resolvePublication(new URLSearchParams('file=assets/example/metadata.edn')),/Invalid publication location/);
    console.log('offline output manifest and bounds checks passed');
})().catch(error => {console.error(error); process.exitCode=1;});
