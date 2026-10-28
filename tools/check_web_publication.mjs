#!/usr/bin/env node
/** Open an exported site at a nested URL with every other network origin blocked. */
import {createServer} from 'node:http';
import {readFile, mkdir, stat} from 'node:fs/promises';
import {resolve, sep, extname} from 'node:path';
import {createRequire} from 'node:module';
import assert from 'node:assert/strict';

const require = createRequire(import.meta.url);
const {chromium} = require(process.env.FIELD_PLAYWRIGHT_MODULE || 'playwright');
const root = resolve(process.argv[2] || ''), evidence = resolve(process.argv[3] || 'build/publication-evidence');
const pilot = process.argv.includes('--pilot');
const manifest = JSON.parse(await readFile(resolve(root,'document/outputs.json'),'utf8'));
const prefix = '/nested/publication/';
const types = {'.html':'text/html','.js':'application/javascript','.css':'text/css','.json':'application/json','.woff2':'font/woff2','.edn':'text/plain'};
const server = createServer(async (request,response) => {
    try {
        const url = new URL(request.url,'http://localhost');
        if (!url.pathname.startsWith(prefix)) {response.writeHead(404).end();return;}
        let path = resolve(root,decodeURIComponent(url.pathname.slice(prefix.length)) || 'index.html');
        if (!path.startsWith(root + sep)) {response.writeHead(403).end();return;}
        if ((await stat(path)).isDirectory()) path = resolve(path,'index.html');
        response.setHeader('Content-Type',types[extname(path)] || 'application/octet-stream');
        response.end(await readFile(path));
    } catch (_) {response.writeHead(404).end();}
});
await new Promise(r => server.listen(0,'127.0.0.1',r));
const origin = `http://127.0.0.1:${server.address().port}`;
const browser = await chromium.launch({headless:true,executablePath:process.env.FIELD_CHROMIUM_EXECUTABLE || undefined});
const context = await browser.newContext({viewport:{width:1600,height:1100},deviceScaleFactor:1});
const blocked = [], missing = [], errors = [], sockets = [];
await context.route('**/*',route => {
    if (/^(blob:|data:)/.test(route.request().url())) return route.continue();
    if (route.request().url().startsWith(origin + prefix)) return route.continue();
    blocked.push(route.request().url()); return route.abort();
});
if (context.routeWebSocket) await context.routeWebSocket('**/*',socket => {sockets.push(socket.url()); socket.close();});
context.on('response',r => {if(r.status() >= 400) missing.push([r.status(),r.url()]);});
context.on('page',p => {p.on('pageerror',e => errors.push(e.message));p.on('websocket',s => sockets.push(s.url()));});
try {
    await mkdir(evidence,{recursive:true});
    const page = await context.newPage();
    await page.goto(origin + prefix + 'index.html');
    await page.waitForSelector('.has-published-outputs');
    await page.waitForFunction(() => document.getElementById('loading').style.display === 'none');
    assert.equal(await page.locator('.published-output').count(),manifest.outputs.length);
    assert.equal(await page.locator('#error:visible').count(),0);
    // Open closed channels too, exercising lazy iframes.
    await page.locator('.published-output').evaluateAll(nodes => nodes.forEach(n => n.open=true));
    const checks = [];
    for (const output of manifest.outputs) {
        if (!output.path) continue;
        const url = origin + prefix + output.path;
        const frame = await page.waitForEvent('framenavigated',{predicate:f => f.url() === url,timeout:1000}).catch(() => page.frames().find(f => f.url() === url));
        assert.ok(frame,`Missing output frame: ${output.channel}`);
        await frame.waitForFunction(() => document.documentElement.dataset.captureReady === 'true');
        checks.push({channel:output.channel,ready:true});
    }
    if (pilot) {
        assert.equal(manifest.outputs.length,7);
        const plotFrame = page.frames().find(f => f.url().endsWith('/outputs/' + manifest.outputs.find(o => o.channel==='plot').path.split('/').pop()));
        const plot = await plotFrame.evaluate(async () => {
            const p=document.querySelector('.js-plotly-plot');
            await Plotly.relayout(p,{'xaxis.range':[4,12]});
            return {samples:p.data[0].y.length,first:p.data[0].y[0],range:p.layout.xaxis.range};
        });
        assert.equal(plot.samples,49); assert.equal(plot.first,0); assert.deepEqual(plot.range,[4,12]);
        // The second chart lives inside a captured srcdoc iframe, with its own Plotly runtime.
        let plotFrames = [];
        await page.waitForTimeout(300);
        for (const frame of page.frames()) {
            if (await frame.locator('.js-plotly-plot').count()) plotFrames.push(frame);
        }
        assert.equal(plotFrames.length,2,'Plotly in a nested iframe was not restored');
        const children = page.frames();
        const values = await Promise.all(children.map(f => f.evaluate(() => document.body.innerText).catch(() => '')));
        assert.ok(values.some(t => t.includes('captured in Field')),'Value snapshot lost its contents');
        assert.ok(values.some(t => /amplitude|sample/.test(t) && /64/.test(t)),'Lazy inspector lost its materialized rows');
        let viewers = [];
        const deadline = Date.now() + 20000;
        while (Date.now() < deadline) {
            viewers = [];
            for (const frame of page.frames()) if (await frame.evaluate(() => !!window.FieldViewer).catch(()=>false)) viewers.push(frame);
            if (viewers.length === 2) break;
            await page.waitForTimeout(100);
        }
        assert.equal(viewers.length,2,'Published Three.js viewers were not restored');
        for (const frame of viewers) {
            await frame.waitForFunction(() => window.FieldViewer.captureStatus().ready);
            const restored = await frame.evaluate(() => ({saved:window.__fieldThreePublication.view.camera,current:FieldInspector.snapshot().camera}));
            assert.ok(restored.current.every((v,i) => Math.abs(v-restored.saved[i]) < 1e-5),'Published camera was not preserved');
            assert.equal(await frame.locator('#camera-link').isVisible(),false);
            assert.equal(await frame.locator('#feature-tools').isVisible(),false);
            const before = await frame.evaluate(() => FieldInspector.snapshot().camera);
            const canvas = frame.locator('#canvas-wrap canvas'), rect = await canvas.boundingBox();
            assert.ok(rect && rect.width > 100 && rect.height > 100);
            await page.mouse.move(rect.x + rect.width*.45,rect.y + rect.height*.65);
            await page.mouse.down();
            await page.mouse.move(rect.x + rect.width*.7,rect.y + rect.height*.8,{steps:10});
            await page.mouse.up();
            await frame.waitForFunction(() => {const r=FieldViewer.renderStats();return !r.pending&&!r.dirty;});
            const after = await frame.evaluate(() => FieldInspector.snapshot().camera);
            assert.ok(after.some((v,i)=>Math.abs(v-before[i])>.01),'Mouse drag did not orbit the published scene');
        }
        const partView = (await Promise.all(viewers.map(async frame => ({frame,count:await frame.evaluate(() => FieldInspector.rendered().length)})))).find(v=>v.count >= 2)?.frame;
        assert.ok(partView,'Publication lost the mesh parts');
        await partView.locator('#part-inspector summary').click();
        for (const folder of await partView.locator('.part-toggle[aria-expanded="false"]').all()) await folder.click();
        const baseline = await partView.locator('#canvas-wrap').screenshot();
        await partView.getByLabel('Outer shell',{exact:true}).uncheck();
        const hidden = await partView.evaluate(() => ({hidden:FieldInspector.snapshot().hidden,rendered:FieldInspector.rendered()}));
        assert.ok(hidden.hidden.length > 0 && hidden.rendered.some(p=>!p.visible),'Parts menu did not hide geometry');
        const withoutShell = await partView.locator('#canvas-wrap').screenshot();
        assert.notDeepEqual(withoutShell,baseline,'Hiding a part did not change the rendered picture');
        await partView.getByLabel('Outer shell',{exact:true}).check();
        assert.ok((await partView.evaluate(() => FieldInspector.rendered())).every(p=>p.visible),'Part did not become visible again');
        await partView.locator('#xray-toggle').click();
        await partView.locator('#xray-opacity').evaluate(input => {input.value='35';input.dispatchEvent(new Event('input',{bubbles:true}));});
        const xray = await partView.evaluate(() => FieldInspector.snapshot());
        assert.equal(xray.xray,true); assert.equal(xray.xrayOpacity,.35);
        await partView.waitForFunction(() => {const r=FieldViewer.renderStats();return !r.pending&&!r.dirty;});
        const xrayImage = await partView.locator('#canvas-wrap').screenshot();
        assert.notDeepEqual(xrayImage,baseline,'X-ray did not change the rendered picture');
        await partView.locator('body').screenshot({path:resolve(evidence,'parts-and-xray.png')});
        const downloadEvent = page.waitForEvent('download');
        await partView.locator('#export-glb').click();
        const download = await downloadEvent;
        const glb = await readFile(await download.path());
        assert.equal(glb.readUInt32LE(0),0x46546c67,'Local GLB download is not a GLB');
        await partView.locator('#xray-toggle').click();
        checks.push({threeOrbit:true,partsVisibility:true,xray:true,localGlbDownload:true});
        await page.locator('[data-box-id="published_lines"] .box-name').click();
        await page.waitForSelector('#editor-container.visible');
        assert.ok(await page.evaluate(() => monacoEditor.getValue().includes('lines(curve')),'Published code cannot be browsed');
    }
    await page.keyboard.press('Escape');
    await page.keyboard.press('0');
    await page.screenshot({path:resolve(evidence,'published-document.png')});
    for (const output of manifest.outputs.filter(o=>o.path)) {
        const framePage = await context.newPage();
        await framePage.setViewportSize({width:Math.min(1600,output.width),height:Math.min(1200,output.height)});
        await framePage.goto(origin + prefix + output.path);
        await framePage.waitForFunction(() => document.documentElement.dataset.captureReady === 'true');
        await framePage.screenshot({path:resolve(evidence,output.channel.replace(/[^a-z0-9-]/gi,'_')+'.png')});
        await framePage.close();
    }
    assert.deepEqual(blocked,[],'Publication tried to reach outside its directory');
    assert.deepEqual(missing,[],'Publication has missing assets');
    assert.deepEqual(sockets,[],'Publication attempted a live websocket');
    assert.deepEqual(errors,[],'Publication raised browser errors');
    console.log(JSON.stringify({ok:true,relocatable:true,outputs:checks,externalRequests:blocked,websockets:sockets,evidence},null,2));
} finally {await context.close();await browser.close();await new Promise(r=>server.close(r));}
