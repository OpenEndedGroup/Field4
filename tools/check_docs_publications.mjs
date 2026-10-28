#!/usr/bin/env node
/** Exercise the built docs and every captured channel with no Field or external origin. */
import {createRequire} from 'node:module';
import {mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';
const {chromium}=createRequire(import.meta.url)(process.env.FIELD_PLAYWRIGHT_MODULE || 'playwright');
const base=new URL(process.argv[2]), evidence=resolve(process.argv[3] || 'build/docs-publication-evidence');
if (!base.pathname.endsWith('/')) base.pathname+='/';
const registry=await (await fetch(new URL('offline-publications.json',base))).json();
const browser=await chromium.launch({headless:true,executablePath:process.env.FIELD_CHROMIUM_EXECUTABLE || undefined});
const external=[],missing=[],errors=[],sockets=[];
async function context(viewport={width:1800,height:1000}) {
    const ctx=await browser.newContext({viewport,reducedMotion:'reduce'});
    await ctx.route('**/*',route=>{
        const url=route.request().url();
        if (url.startsWith(base.href) || /^(blob:|data:)/.test(url)) return route.continue();
        external.push(url); return route.abort();
    });
    ctx.on('response',r=>{if(r.status()>=400)missing.push([r.status(),r.url()]);});
    ctx.on('page',p=>{p.on('pageerror',e=>errors.push(e.message));p.on('websocket',s=>sockets.push(s.url()));});
    return ctx;
}
async function ready(page) {
    await page.waitForFunction(()=>document.documentElement.dataset.captureReady==='true',null,{timeout:30000});
    for (const frame of page.frames().filter(f=>f!==page.mainFrame())) {
        await frame.waitForFunction(()=>document.documentElement.dataset.captureReady==='true' ||
            (document.body?.children.length===1 && document.body.firstElementChild.tagName==='IMG' &&
                document.body.firstElementChild.complete && document.body.firstElementChild.naturalWidth>0),null,{timeout:30000});
        assert.equal(await frame.evaluate(()=>document.documentElement.dataset.captureError || ''),'');
    }
}
try {
    await mkdir(evidence,{recursive:true});
    let ctx=await context(), page=await ctx.newPage();
    for (const [slug,source] of [['fline_examples','fline_examples'],['three_output','three_output_gallery']]) {
        await page.goto(new URL(slug+'/',base).href);
        const iframe=page.locator(`iframe[src*="file=assets/${source}/metadata.edn"]`);
        await iframe.scrollIntoViewIfNeeded();
        const frame=await (await iframe.elementHandle()).contentFrame();
        await frame.locator('.published-output').first().waitFor({timeout:30000});
        const entry=registry.documents[`assets/${source}/metadata.edn`];
        assert.ok(entry,'Existing embed has no registered publication');
        const manifest=await (await fetch(new URL(entry.outputs,base))).json();
        assert.equal(await frame.locator('.published-output').count(),manifest.outputs.length);
        if (source==='fline_examples') {
            const tab=await frame.locator('.published-output summary').first().evaluate(n=>({w:n.offsetWidth,panel:n.parentElement.offsetWidth}));
            assert.ok(tab.w<tab.panel,'Output header should be a tab');
        } else {
            await frame.evaluate(()=>{
                const box=currentBoxes.find(b=>b.id==='three-output-scene');
                frameWorldRect(FieldOfflineOutputs.bounds(box),1,20);
            });
            let viewer;
            const deadline=Date.now()+30000;
            while(Date.now()<deadline && !viewer){
                for(const f of page.frames())if(await f.evaluate(()=>!!window.FieldViewer?.captureStatus().ready).catch(()=>false)){viewer=f;break;}
                if(!viewer)await page.waitForTimeout(100);
            }
            assert.ok(viewer,'The Three.js embed has no rendered geometry');
            const before=await viewer.evaluate(()=>FieldInspector.snapshot().camera),r=await viewer.locator('#canvas-wrap canvas').boundingBox();
            await page.mouse.move(r.x+r.width*.45,r.y+r.height*.5);await page.mouse.down();
            await page.mouse.move(r.x+r.width*.7,r.y+r.height*.65,{steps:10});await page.mouse.up();
            await viewer.waitForFunction(()=>{const r=FieldViewer.renderStats();return !r.pending&&!r.dirty});
            const after=await viewer.evaluate(()=>FieldInspector.snapshot().camera);
            assert.ok(after.some((v,i)=>Math.abs(v-before[i])>.01),'Published geometry did not orbit');
            await viewer.locator('#xray-toggle').click();assert.equal(await viewer.evaluate(()=>FieldInspector.snapshot().xray),true);
        }
        await iframe.screenshot({path:resolve(evidence,slug+'.png')});
    }
    await page.goto(new URL('agent_transcripts/',base).href);
    const gallery=Object.keys(registry.documents).filter(p=>p.startsWith('assets/agent_transcripts/'));
    assert.ok(gallery.length>=2);
    const first=page.locator(`a[href*="file=${gallery[0]}"]`).first();await first.click();
    const rail=page.locator('.field-transcript-viewer');await rail.waitFor();
    const shown=await (await rail.locator('iframe').elementHandle()).contentFrame();
    await shown.locator('.published-output').first().waitFor({timeout:30000});
    const old=await rail.elementHandle();
    const placement=await first.evaluate(a=>({text:a.closest('.md-content__inner').getBoundingClientRect().right,
        viewer:document.querySelector('.field-transcript-viewer').getBoundingClientRect().left}));
    assert.ok(placement.viewer>placement.text);
    await page.screenshot({path:resolve(evidence,'gallery.png')});
    await page.locator(`a[href*="file=${gallery[1]}"]`).first().click();
    assert.equal(await rail.count(),1);assert.equal(await old.evaluate(n=>n.isConnected),false);
    await page.setViewportSize({width:700,height:900});
    assert.equal(await page.locator('.field-transcript-viewer--below').count(),1);
    await page.getByRole('button',{name:'Close document',exact:true}).click();assert.equal(await rail.count(),0);
    await ctx.close();
    ctx=null;
    const channels=[];
    for(const [source,entry] of Object.entries(registry.documents)){
        const manifest=await (await fetch(new URL(entry.outputs,base))).json();
        const parent=new URL('../',new URL(entry.outputs,base));
        for(const output of manifest.outputs)if(output.path)channels.push({source,channel:output.channel,url:new URL(output.path,parent).href});
    }
    for(let i=0;i<channels.length;i++){
        if(i%20===0){if(ctx)await ctx.close();ctx=await context({width:1200,height:900});page=await ctx.newPage();}
        const channel=channels[i];
        try {await page.goto(channel.url);await ready(page);}
        catch(error){throw new Error(`${channel.source} / ${channel.channel}: ${error.message}`);}
        if((i+1)%20===0)console.log(`Verified ${i+1}/${channels.length} captured channels`);
    }
    await ctx.close();
    assert.deepEqual(external,[],'External requests');assert.deepEqual(missing,[],'Missing assets');
    assert.deepEqual(errors,[],'Browser errors');assert.deepEqual(sockets,[],'Websocket connections');
    console.log(JSON.stringify({ok:true,documents:Object.keys(registry.documents).length,channels:channels.length,
        orbit:true,xray:true,galleryReplacement:true,mobilePlacement:true,external,missing,errors,sockets,evidence}));
} finally {await browser.close();}
