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
const languages = samples.map((_, i) => i < 6 ? 'fieldml' : i < 10 ? 'kotlin' : i < 12 ? 'petri' : 'fnrewrite');
const nodes = samples.map((textContent, i) => ({textContent,innerHTML:'',dataset:{},classList:{add(){}},closest(){return {dataset:{codeLang:languages[i]}}}}));
const context = {console,document:{readyState:'loading',addEventListener(){},querySelectorAll(selector){return selector.startsWith('[data-code-lang]') ? nodes : []}}};
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
for (let i = 0; i < nodes.length; i++) {
  const plain = nodes[i].innerHTML.replace(/<[^>]+>/g, '').replace(/&lt;/g,'<').replace(/&gt;/g,'>').replace(/&quot;/g,'"').replace(/&#x27;/g,"'").replace(/&amp;/g,'&');
  assert.equal(plain, samples[i], 'highlighting must preserve source text');
}
assert.ok(nodes.every(n => n.dataset.referenceHighlighted === 'true'));
console.log('PASS: keywords, constructors, operators, line comments, nested comments, types, HTML escaping, reference mounting');
})();
