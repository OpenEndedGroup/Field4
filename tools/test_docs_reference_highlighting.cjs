// Run against the same highlight.js common build used by the documentation:
// node tools/test_docs_reference_highlighting.cjs /path/to/highlight.min.js
// No browser, Field JVM, or additional npm packages are required.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const libraryPath = process.argv[2];
if (!libraryPath) throw new Error('Pass the highlight.js 11.8.0 common-build file as the first argument.');
const samples = [
  'let add a b = a + b',
  'match v with | Some x -> x | None -> 0',
  '// let x = 10',
  '(* outer (* let hidden = 2 *) still comment *) let x = 10',
  '"<script>alert(1)</script>"',
  "('a -> 'b) -> ['a] -> ['b]",
  'renderPatWav(path, pattern, cycles, cps)',
  'RenderedAudioFile.existing(path, generator, provenance)',
  '// renderPatWav(path) "quoted"',
  '"renderPatWav(path)"',
  '!grid 1/16 // tick',
  'kick -[ hit @1/16 : bd ]-> hats',
  'multiply(?x, 1) => ?x',
  '# multiply(?x, 1) is a comment',
  '?x => @{ if (true) { value } else { value } }'
];
const slangStart = samples.length;
samples.push(
  '[[vk::binding(0,0)]] ByteAddressBuffer weights;',
  '[shader("compute")] [numthreads(8,8,1)]',
  'void main(uint3 id : SV_DispatchThreadID) { float3 rgb = float3(.5h, 1e-3f, 0xffu); }',
  'RWStructuredBuffer<float4> pixels; CoopVec<half,16> input;',
  'var h = coopVecMatMulAdd<half,32>(input); return max(h, half(0));',
  '// float3 fake = coopVecMatMulAdd<half,32>(input); [numthreads(8,8,1)]',
  '"<script>float3 fake()</script>"',
  'pixels[id] = values [index];',
  '#if FIELD_COOPERATIVE // float3 is a comment here',
  '/* float3 hidden;\n return coopVecMatMulAdd<half,32>(x); */\nfloat4 visible;',
  fs.readFileSync('src/jvmMain/resources/afield/four/neural/learned.slang','utf8')
);
const languages = samples.map((_, i) => i < 6 ? 'fieldml' : i < 10 ? 'kotlin' : i < 12 ? 'petri' : i < slangStart ? 'fnrewrite' : 'slang');
const nodes = samples.map((textContent, i) => ({textContent,innerHTML:'',dataset:{},classList:{add(){}},closest(){return {dataset:{codeLang:languages[i]}}}}));
const selectors = [];
const context = {console,URL,location:{href:'https://example.test/field4/reference/math/'},document:{currentScript:{src:'https://example.test/field4/assets/code-diff-player/code-diff-player.js'},readyState:'loading',addEventListener(){},querySelectorAll(selector){selectors.push(selector);return selector.startsWith('[data-code-lang]') ? nodes : []}}};
context.window=context;
vm.createContext(context);
vm.runInContext(fs.readFileSync(libraryPath,'utf8'), context);
vm.runInContext(fs.readFileSync('public/docs/assets/code-diff-player/code-diff-player.js','utf8'), context);
(async () => {
context.CodeDiffPlayer.mountAll();
await Promise.resolve();
assert.match(nodes[0].innerHTML,/hljs-keyword">let/);
assert.match(nodes[1].innerHTML,/hljs-type">Some/);
assert.match(nodes[1].innerHTML,/hljs-operator">-&gt;/);
assert.equal((nodes[2].innerHTML.match(/hljs-comment/g)||[]).length,1);
assert.doesNotMatch(nodes[2].innerHTML,/hljs-keyword/);
assert.match(nodes[3].innerHTML,/still comment/);
assert.equal((nodes[3].innerHTML.match(/hljs-keyword/g)||[]).length,1);
assert.doesNotMatch(nodes[4].innerHTML,/<script>/);
assert.match(nodes[4].innerHTML,/&lt;script&gt;/);
assert.match(nodes[5].innerHTML,/hljs-type/);
assert.match(nodes[6].innerHTML,/hljs-title function_">renderPatWav/);
assert.match(nodes[7].innerHTML,/hljs-type">RenderedAudioFile/);
assert.doesNotMatch(nodes[8].innerHTML,/hljs-title/);
assert.doesNotMatch(nodes[9].innerHTML,/hljs-title/);
assert.match(nodes[10].innerHTML,/hljs-meta">!grid/);
assert.match(nodes[11].innerHTML,/hljs-operator/);
assert.match(nodes[12].innerHTML,/hljs-variable">\?x/);
assert.match(nodes[13].innerHTML,/hljs-comment/);
assert.doesNotMatch(nodes[13].innerHTML,/hljs-variable/);
assert.match(nodes[14].innerHTML,/hljs-keyword">if/);
const slang = nodes.slice(slangStart).map(n => n.innerHTML);
assert.match(slang[0], /hljs-meta">\[\[vk::binding/);
assert.match(slang[0], /hljs-type">ByteAddressBuffer/);
assert.match(slang[1], /hljs-meta">\[shader/);
assert.match(slang[1], /hljs-meta">\[numthreads/);
assert.match(slang[1], /hljs-string">&quot;compute&quot;/);
assert.match(slang[2], /hljs-type">uint3/);
assert.match(slang[2], /hljs-built_in">SV_DispatchThreadID/);
assert.match(slang[2], /hljs-number">\.5h/);
assert.match(slang[2], /hljs-number">1e-3f/);
assert.match(slang[2], /hljs-number">0xffu/);
assert.match(slang[3], /hljs-type">RWStructuredBuffer/);
assert.match(slang[3], /hljs-type">CoopVec/);
assert.match(slang[4], /hljs-keyword">var/);
assert.match(slang[4], /hljs-built_in">coopVecMatMulAdd/);
assert.match(slang[4], /hljs-built_in">max/);
assert.match(slang[5], /hljs-comment/);
assert.doesNotMatch(slang[5], /hljs-(?:type|built_in|meta)/);
assert.doesNotMatch(slang[6], /<script>|hljs-(?:type|title)/);
assert.doesNotMatch(slang[7], /hljs-meta/);
assert.match(slang[8], /hljs-meta/);
assert.match(slang[8], /hljs-comment/);
assert.equal((slang[9].match(/hljs-type/g) || []).length, 1);
assert.doesNotMatch(slang[9], /hljs-built_in/);
assert.match(slang[10], /hljs-built_in">coopVecMatMulAdd/);
assert.match(slang[10], /hljs-title function_">learned/);
for (let i = 0; i < nodes.length; i++) {
  const plain = nodes[i].innerHTML.replace(/<[^>]+>/g, '').replace(/&lt;/g,'<').replace(/&gt;/g,'>').replace(/&quot;/g,'"').replace(/&#x27;/g,"'").replace(/&amp;/g,'&');
  assert.equal(plain, samples[i], 'highlighting must preserve source text');
}
assert.ok(nodes.every(n => n.dataset.referenceHighlighted === 'true'));
// Prose inline code: the build marks a span with its own `data-code-lang` (docs_hooks.mark_inline_code_html),
// and the same pass must select it. Nothing unmarked outside a reference table is selected.
const referenceSelector = selectors.find(s => s.startsWith('[data-code-lang]'));
assert.match(referenceSelector, /(^|,\s*)code\[data-code-lang\]:not\(\.highlight\)/);
assert.doesNotMatch(referenceSelector, /(^|,\s*)(p|li) code\b/);
console.log('PASS: reference languages and Slang kernel; types, attributes, semantics, generic calls, comments, HTML escaping and preserved source text');
})();
