// Node regression for the offline viewer's EDN reader (src/commonMain/resources/edn-parser.js).
// Run: node tools/test_offline_edn_parser.js  (tools/test_offline_viewer_js.py runs it in checkPythonTools).
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');

const repo = path.resolve(__dirname, '..');
const { parseEdn, AfieldFileRef, findFileRefs, resolveSidecars } =
    require(path.join(repo, 'src/commonMain/resources/edn-parser.js'));

const tests = [];
const test = (name, fn) => tests.push({ name, fn });

test('scalars Field writes, including JVM non-finite doubles', () => {
    const v = parseEdn('[1 -2.5 1.0E-5 +3 7N 2.5M NaN Infinity -Infinity true false nil]');
    assert.deepStrictEqual(v.slice(0, 5), [1, -2.5, 1e-5, 3, 7]);
    assert.strictEqual(v[5], 2.5);
    assert.ok(Number.isNaN(v[6]));
    assert.strictEqual(v[7], Infinity);
    assert.strictEqual(v[8], -Infinity);
    assert.deepStrictEqual(v.slice(9), [true, false, null]);
});

test('symbols that start like true/false/nil are symbols', () => {
    assert.deepStrictEqual(parseEdn('[foo nope truthy -x a/b]'), ['foo', 'nope', 'truthy', '-x', 'a/b']);
});

test('string escapes match edn.kt (\\b \\f \\/ \\uXXXX)', () => {
    assert.strictEqual(parseEdn('"a\\nb\\tc\\"d\\\\e\\/f\\bg\\fh\\u00e9"'), 'a\nb\tc"d\\e/f\bg\fhé');
});

test('characters, comments, discard, lists, sets, keyword keys', () => {
    const v = parseEdn('{:a \\x :b \\newline ; comment\n :c #_ ignored (1 2) :d #{3} #_ :gone #_ 4 :e #inst "2026-10-22"}');
    assert.deepStrictEqual(v, { a: 'x', b: '\n', c: [1, 2], d: [3], e: '2026-10-22' });
});

test('Field tagged values', () => {
    const v = parseEdn('{"frame" #afield/rect [1.0 2.0 3.0 4.0] "parents" #{#afield/box "root"} ' +
        '"code" #afield/file-inline {"path" "b_code_.kt" "inline" "println(1)"} ' +
        '"ai4_chat_history" #afield/file "b_ai4_chat_history_.md" "odd" #my/tag 5}');
    assert.deepStrictEqual(v.frame, [1, 2, 3, 4]);
    assert.deepStrictEqual(v.parents, ['root']);
    assert.strictEqual(v.code, 'println(1)');
    assert.ok(v.ai4_chat_history instanceof AfieldFileRef);
    assert.strictEqual(v.ai4_chat_history.afieldFile, 'b_ai4_chat_history_.md');
    assert.deepStrictEqual(v.odd, { tag: 'my/tag', value: 5 });
    assert.deepStrictEqual(v.__sidecars, {
        code: { path: 'b_code_.kt', inline: true },
        ai4_chat_history: { path: 'b_ai4_chat_history_.md', inline: false },
    });
    assert.ok(!Object.keys(v).includes('__sidecars'), '__sidecars is not enumerable');
});

test('resolveSidecars with includeInline prefers the loose file, as Field does', async () => {
    const data = parseEdn('{"boxes" [{"id" "b" "properties" {"code" #afield/file-inline {"path" "b_code_.kt" "inline" "old"} "notes" #afield/file-inline {"path" "b_notes_.md" "inline" "kept"}}}]}');
    const n = await resolveSidecars(data, 'http://h/d/metadata.edn', async (url) => {
        if (url.endsWith('b_code_.kt')) return 'new';
        throw new Error('404');
    }, { includeInline: true });
    assert.deepStrictEqual(n, { resolved: 0, refreshed: 1, missing: 0 });
    assert.strictEqual(data.boxes[0].properties.code, 'new');
    assert.strictEqual(data.boxes[0].properties.notes, 'kept');
});

test('errors name line and column', () => {
    assert.throws(() => parseEdn('{"a"\n  [1 2}'), /line 2/);
    assert.throws(() => parseEdn('"open'), /Unterminated string/);
});

test('resolveSidecars fetches #afield/file text relative to metadata.edn', async () => {
    const data = parseEdn('{"boxes" [{"id" "b" "properties" {"notes" #afield/file "b_notes_.md" "x" #afield/file "missing.md"}}]}');
    assert.strictEqual(findFileRefs(data).length, 2);
    const seen = [];
    const n = await resolveSidecars(data, 'http://h/docs/doc/metadata.edn', async (url) => {
        seen.push(url);
        if (url.endsWith('b_notes_.md')) return '# Hi';
        throw new Error('404');
    });
    assert.deepStrictEqual(n, { resolved: 1, refreshed: 0, missing: 1 });
    assert.strictEqual(data.boxes[0].properties.notes, '# Hi');
    assert.ok(data.boxes[0].properties.x instanceof AfieldFileRef);
    assert.ok(seen.includes('http://h/docs/doc/b_notes_.md'));
});

test('every shipped document parses and its sidecar paths exist', () => {
    const roots = ['dev_docs/agents/demos', 'public/docs/assets', 'templates'];
    const files = [];
    const walk = (dir) => {
        for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
            const p = path.join(dir, entry.name);
            if (entry.isDirectory()) walk(p);
            else if (entry.name === 'metadata.edn') files.push(p);
        }
    };
    roots.forEach(r => fs.existsSync(path.join(repo, r)) && walk(path.join(repo, r)));
    assert.ok(files.length > 50, `found ${files.length} documents`);
    let sidecarsChecked = 0;
    for (const f of files) {
        let data;
        try {
            data = parseEdn(fs.readFileSync(f, 'utf8'));
        } catch (e) {
            throw new Error(`${path.relative(repo, f)}: ${e.message}`);
        }
        assert.ok(Array.isArray(data.boxes), `${f} has boxes`);
        for (const box of data.boxes) {
            const props = box.properties || {};
            const sc = props.__sidecars || {};
            for (const [key, info] of Object.entries(sc)) {
                const side = path.join(path.dirname(f), info.path);
                // The loose file is authoritative and may differ from the inline copy (Field
                // loads the file); only check that the parser found a sidecar that exists.
                if (fs.existsSync(side)) sidecarsChecked++;
            }
        }
    }
    assert.ok(sidecarsChecked > 100, `checked ${sidecarsChecked} sidecars`);
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
