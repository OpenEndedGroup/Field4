// Node regression for the offline viewer's renderers (src/commonMain/resources/offline-viewer.js):
// Markdown notes, the Excalidraw drawing layer, bookmarks, layers and sidecar tabs.
// Run: node tools/test_offline_viewer_render.js  (tools/test_offline_viewer_js.py runs it in checkPythonTools).
'use strict';
const assert = require('assert');
const path = require('path');

const repo = path.resolve(__dirname, '..');
const edn = require(path.join(repo, 'src/commonMain/resources/edn-parser.js'));
global.AfieldFileRef = edn.AfieldFileRef; // a browser global in offline.html
const viewer = require(path.join(repo, 'src/commonMain/resources/offline-viewer.js'));

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

test('markdown: headings, emphasis, code, links, lists, fences', () => {
    const html = viewer.renderMarkdown([
        '# Hawks', '', 'Two hawks `chase` the **nearest** starling, *leading* it.', 'Same paragraph.', '',
        '- one', '- two', '  - nested', '1. first', '', '```kotlin', 'val x = 1 < 2', '```', '',
        '> quoted', '', '---', '[docs](page.md) ![shot](shot.png)',
    ].join('\n'), { baseUrl: 'http://h/doc/metadata.edn' });
    assert.ok(html.includes('<h1>Hawks</h1>'));
    assert.ok(html.includes('<code>chase</code>'));
    assert.ok(html.includes('<strong>nearest</strong>') && html.includes('<em>leading</em>'));
    assert.ok(/<p>Two hawks[\s\S]*Same paragraph\.<\/p>/.test(html), 'soft-wrapped lines stay one paragraph');
    assert.ok(/<ul><li>one<\/li><li>two<ul><li>nested<\/li><\/ul><\/li><\/ul>/.test(html), html);
    assert.ok(html.includes('<ol><li>first</li></ol>'));
    assert.ok(html.includes('data-lang="kotlin"><code>val x = 1 &lt; 2</code>'));
    assert.ok(html.includes('<blockquote><p>quoted</p></blockquote>'));
    assert.ok(html.includes('<hr>'));
    assert.ok(html.includes('href="http://h/doc/page.md"') && html.includes('src="http://h/doc/shot.png"'));
});

test('markdown: pipe tables with code containing pipes and an empty header', () => {
    const html = viewer.renderMarkdown('| | |\n| --- | --- |\n| neighbours | `a | b`, `align(r)` |\n| other | x |');
    assert.ok(html.includes('<table>') && !html.includes('<thead>'));
    assert.ok(html.includes('<td>neighbours</td><td><code>a | b</code>, <code>align(r)</code></td>'), html);
});

test('markdown: escapes HTML and neutralises javascript: links', () => {
    const html = viewer.renderMarkdown('<script>alert(1)</script> [x](javascript:alert(1))');
    assert.ok(!html.includes('<script>'));
    assert.ok(html.includes('&lt;script&gt;'));
    assert.ok(html.includes('href="#"'));
});

const scene = JSON.stringify({
    type: 'excalidraw', version: 2, elements: [
        { id: 'r', type: 'rectangle', x: 10, y: 20, width: 100, height: 50, strokeColor: '#1e1e1e', backgroundColor: 'transparent', strokeWidth: 2, opacity: 100, angle: 0, roundness: { type: 3 } },
        { id: 'e', type: 'ellipse', x: 200, y: 0, width: 40, height: 20, strokeColor: '#e03131', backgroundColor: '#ffc9c9', fillStyle: 'hachure', strokeWidth: 1, opacity: 50 },
        { id: 'a', type: 'arrow', x: 0, y: 0, width: 100, height: 0, points: [[0, 0], [100, 0]], strokeColor: '#1971c2', endArrowhead: 'arrow', startArrowhead: null },
        { id: 'f', type: 'freedraw', x: 5, y: 5, points: [[0, 0], [10, 10], [20, 5], [30, 15]], strokeColor: '#1e1e1e', strokeWidth: 2 },
        { id: 't', type: 'text', x: 50, y: 300, width: 80, height: 25, text: 'flock <here>\nline 2', fontSize: 20, fontFamily: 5, textAlign: 'left' },
        { id: 'gone', type: 'rectangle', x: 9999, y: 9999, width: 1, height: 1, isDeleted: true },
        { id: 'd', type: 'diamond', x: -50, y: -40, width: 20, height: 20 },
    ],
});

test('excalidraw: live elements only, every kind drawn, bounds in world coordinates', () => {
    const out = viewer.excalidrawSceneSvg(scene);
    assert.strictEqual(out.count, 6);
    assert.ok(!out.markup.includes('gone'));
    for (const type of ['rectangle', 'ellipse', 'arrow', 'freedraw', 'text', 'diamond']) {
        assert.ok(out.markup.includes(`data-type="${type}"`), type);
    }
    assert.deepStrictEqual(out.bounds, { x: -50, y: -40, w: 290, h: 365 });
    assert.ok(out.markup.includes('<rect x="10" y="20" width="100" height="50" rx="12.5"'));
    assert.ok(out.markup.includes('opacity="0.5"') && out.markup.includes('fill-opacity="0.45"'));
    assert.ok(out.markup.includes('flock &lt;here&gt;'), 'text is escaped');
    assert.ok(out.markup.includes('<tspan x="50" y="319">flock'));
    assert.ok(/data-type="arrow"[^]*<path d="M 85\.?\d* /.test(out.markup) || out.markup.includes('L 100 0'), 'arrowhead at the end');
});

test('excalidraw: unreadable or empty scenes draw nothing', () => {
    assert.strictEqual(viewer.excalidrawSceneSvg('').count, 0);
    assert.strictEqual(viewer.excalidrawSceneSvg('not json').count, 0);
    assert.strictEqual(viewer.excalidrawSceneSvg(null).count, 0);
    assert.strictEqual(viewer.excalidrawSceneSvg({ elements: [] }).bounds, null);
});

test('bookmarks: read from the root property, ordered by slot', () => {
    const data = { boxes: [{ id: 'root', properties: { bookmarks: JSON.stringify({ version: 1, bookmarks: [
        { id: 'bookmark_3', name: 'later', timestamp: 1, scrollPosition: { x: 1, y: 2, zoom: 0.5 } },
        { id: 'bookmark_1', name: 'start', timestamp: 1, scrollPosition: { x: 0, y: 0 } },
        { id: 'bookmark_2', name: 'no position', timestamp: 1 },
    ] }) } }] };
    const marks = viewer.readBookmarks(data);
    assert.deepStrictEqual(marks.map(b => [b.slot, b.name]), [[1, 'start'], [3, 'later']]);
    assert.deepStrictEqual(viewer.readBookmarks({ boxes: [{ id: 'root', properties: { bookmarks: '{oops' } }] }), []);
});

test('layers and sidecar tabs', () => {
    assert.deepStrictEqual(viewer.boxLayers({}), ['default']);
    assert.deepStrictEqual(viewer.boxLayers({ layers: 'a, b' }), ['a', 'b']);
    const props = edn.parseEdn('{"code" #afield/file-inline {"path" "b_code_.kt" "inline" "x"} ' +
        '"notes" #afield/file-inline {"path" "b_notes_.md" "inline" "n"} ' +
        '"wgsl" #afield/file-inline {"path" "b_wgsl_.wgsl" "inline" "w"} ' +
        '"ai4_chat_history" #afield/file "b_ai4_chat_history_.md" "name" "b"}');
    assert.deepStrictEqual(viewer.contentProperties(props), ['code', 'ai4_chat_history', 'wgsl']);
});

(async () => {
    let failed = 0;
    for (const { name, fn } of tests) {
        try {
            await fn();
            console.log(`ok - ${name}`);
        } catch (e) {
            failed++;
            console.log(`not ok - ${name}\n  ${e.stack || e}`);
        }
    }
    console.log(`${tests.length - failed}/${tests.length} passed`);
    process.exit(failed ? 1 : 0);
})();
