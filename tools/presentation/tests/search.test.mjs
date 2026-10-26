import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
const {fold, terms, matches, excerpt} = createRequire(import.meta.url)('../../../src/commonMain/resources/presentation-search.js');

test('incremental search matches all words across title, section, number and audience text', () => {
  const row = fold('103 Waves Explain A constant propagation speed gives a shorter wavelength');
  for (const query of ['wave', 'WAVE', 'wavelen', 'shorter speed', '103 propagation', 'Explain Waves', '  ']) {
    assert.ok(matches(row, terms(query)), query);
  }
  assert.equal(matches(row, terms('wavelength missing')), false);
});

test('accent-insensitive Unicode search keeps the original text in excerpts', () => {
  const row = 'Café — naïve predictions, λ = c / f';
  assert.ok(matches(fold(row), terms('CAFE naive λ')));
  assert.equal(excerpt(row, terms('cafe')), row);
});

test('body excerpts locate the match in long text and leave literal markup as plain data', () => {
  const row = 'Introduction '.repeat(40) + 'propagation <script> is text here ' + 'closing '.repeat(40);
  const snippet = excerpt(row, terms('propagation'));
  assert.ok(snippet.startsWith('…') && snippet.endsWith('…'));
  assert.ok(snippet.includes('propagation <script>'));
  assert.ok(snippet.length <= 182);
  assert.equal(excerpt(row, terms('not-in-body')), '');
});
