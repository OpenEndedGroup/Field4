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
        assert.equal(manifest.outputs.length,6);
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
        const images = await Promise.all(children.map(f => f.evaluate(() => [...document.querySelectorAll('img')].map(n=>({alt:n.alt,width:n.naturalWidth,height:n.naturalHeight}))).catch(()=>[])));
        assert.ok(images.flat().some(i => i.alt.startsWith('Three.js') && i.width > 100 && i.height > 100),'Three.js snapshot is empty');
        await page.locator('[data-box-id="published_lines"] .box-name').click();
        await page.waitForSelector('#editor-container.visible');
        assert.ok(await page.evaluate(() => monacoEditor.getValue().includes('lines(curve')),'Published code cannot be browsed');
    }
    await page.locator('#canvas').click({position:{x:5,y:5},force:true});
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
