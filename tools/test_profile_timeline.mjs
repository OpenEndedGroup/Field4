import test from 'node:test';
import assert from 'node:assert/strict';
import { selectedSpans, profileSummary, mergeProfileSpans, profileHotspots, mappedProfileLine, profileBoxHeatmap, profileHeatLines } from '../src/commonMain/resources/profileTimelineModel.js';

const spans=[
    {sequence:0,lane:1,startMs:0,wallMs:10,cpuMs:2,allocatedBytes:100},
    {sequence:1,lane:2,startMs:20,wallMs:1,cpuMs:1,allocatedBytes:50},
    {sequence:2,lane:1,startMs:40,wallMs:5,cpuMs:3,allocatedBytes:200}
];
test('box line mapping ignores trailing newlines but rejects rewritten or shifted lines',()=>{
    const source={code:'loop {\n work()\n}',boxCode:'loop {\n work()\n}\n'};
    assert.equal(mappedProfileLine(source,2),2);
    assert.equal(mappedProfileLine({...source,code:'loop {\n rewrite()\n}'},2),null);
    assert.equal(mappedProfileLine({...source,code:' work()\n}'},1),null);
    assert.equal(mappedProfileLine(source,-1),null);
});
test('brush selects complete resumes by start, without inventing fractional allocation',()=>{
    assert.deepEqual(selectedSpans(spans,[5,25]).map(s=>s.sequence),[1]);
    assert.deepEqual(selectedSpans(spans,[0,50],1).map(s=>s.sequence),[0,2]);
    assert.equal(profileSummary(selectedSpans(spans,[0,50],1)).allocatedBytes,300);
});
test('incremental snapshots deduplicate and evict overwritten history',()=>{
    assert.deepEqual(mergeProfileSpans(spans.slice(0,2),spans.slice(1),1).map(s=>s.sequence),[1,2]);
});
test('time gaps are excluded and unsupported counters remain unknown',()=>{
    const s=profileSummary(spans);assert.equal(s.wallMs,16);assert.equal(s.cpuMs,6);assert.equal(s.p95Ms,10);
    assert.equal(profileSummary([{...spans[0],cpuMs:-1,allocatedBytes:-1}]).allocatedBytes,null);
    assert.equal(profileSummary([]).p95Ms,0);
});

test('stack brushing keeps background work and deduplicates recursive inclusive classes',()=>{
    const frames=[{id:0,className:'light.World',method:'tick',line:5},{id:1,className:'light.World',method:'trace',line:9},{id:2,className:'light.Worker',method:'generate',line:15}];
    const samples=[{timeMs:5,threadId:7,kind:'cpu',weight:1,stack:[1,0,1]},{timeMs:25,threadId:8,kind:'cpu',weight:1,stack:[2]},{timeMs:60,threadId:8,kind:'allocation',weight:1000,stack:[2]}];
    const all=profileHotspots(samples,frames,[0,40],{group:'class'});
    assert.equal(all.count,2);assert.equal(all.rows[0].weight,1);
    const owned=profileHotspots(samples,frames,[0,40],{lane:3,spans:[{lane:3,threadId:7,startMs:0,wallMs:10}]});
    assert.equal(owned.count,1);assert.equal(owned.rows.length,2);
    assert.equal(profileHotspots(samples,frames,[0,40],{self:true}).rows.length,2);
    assert.equal(profileHotspots(samples,frames,[50,70],{kind:'allocation'}).total,1000);
    assert.equal(profileHotspots(samples,frames,[0,40],{search:'Worker'}).rows.length,1);
});

test('box heat sums source lines once per sample and follows the brush and accounting mode',()=>{
    const sources=[{id:0,boxId:'box-a',property:'code',code:'work()\nallocate()\ngenerated()',boxCode:'work()\nallocate()\noriginal()'}];
    const frames=[
        {id:0,sourceId:0,line:1,className:'Script',method:'work'},
        {id:1,sourceId:0,line:1,className:'Script$Nested',method:'work'},
        {id:2,sourceId:0,line:2,className:'Script',method:'allocate'},
        {id:3,sourceId:0,line:3,className:'Script',method:'generated'}
    ];
    const samples=[
        {timeMs:5,threadId:7,kind:'cpu',weight:1,stack:[2,0,1,0,3]},
        {timeMs:15,threadId:7,kind:'cpu',weight:1,stack:[0]},
        {timeMs:20,threadId:8,kind:'cpu',weight:1,stack:[0]},
        {timeMs:25,threadId:7,kind:'allocation',weight:4096,stack:[2,0]}
    ];
    const context={uid:'box-a',property:'code',text:sources[0].boxCode};
    const heat=(range,options={})=>profileHeatLines(profileBoxHeatmap(profileHotspots(samples,frames,range,{...options,group:'sourceLine'}).rows,sources),context);
    assert.deepEqual(heat([0,30]).map(r=>[r.line,r.weight,r.level]),[[1,3,12],[2,1,4]]);
    assert.deepEqual(heat([0,10]).map(r=>[r.line,r.weight]),[[1,1],[2,1]]);
    assert.deepEqual(heat([0,10],{self:true}).map(r=>r.line),[2]);
    assert.equal(heat([0,30],{kind:'allocation'})[1].weight,4096);
    assert.equal(heat([0,30],{thread:7})[0].weight,2);
    assert.deepEqual(heat([0,30],{lane:2,spans:[{threadId:7,lane:2,startMs:10,wallMs:8}]}).map(r=>[r.line,r.weight]),[[1,1]]);
    assert.deepEqual(heat([40,50]),[]);
});

test('gutter mapping requires the matching box, property and unchanged code',()=>{
    const source={boxId:'a',property:'code',boxCode:'work()',lines:[{line:1,weight:4}]};
    const context={uid:'a',property:'code',text:'work()'};
    assert.equal(profileHeatLines([source],context)[0].level,12);
    for(const changed of [{uid:'identical-copy'},{property:'notes'},{text:'// edited\nwork()'}]) {
        assert.deepEqual(profileHeatLines([source],{...context,...changed}),[]);
    }
    assert.deepEqual(profileHeatLines([source],null),[]);
    assert.deepEqual(profileHeatLines([],context),[]);
});
