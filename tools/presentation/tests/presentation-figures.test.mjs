import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';

const require = createRequire(import.meta.url);
const {figureUrl, validFigure, validateGuideState} = require('../../../electron/presentation-cues.js');
const fieldUrl = 'https://localhost:9771/index.html?file=deck';

test('a live figure opens its box alone, in the presentation document, on the local Field', () => {
  assert.equal(figureUrl(fieldUrl, 'sound-deck', {id: 'figure-1', box: 'sine_tone', channels: ['main', 'controls']}, 'rgb(0, 0, 0)'),
    'https://localhost:9771/index.html?file=sound-deck&figure=sine_tone&channels=main%2Ccontrols&bg=rgb%280%2C+0%2C+0%29');
  assert.equal(figureUrl(fieldUrl, 'sound-deck', {id: 'figure-1', box: 'sine_tone'}, 'url(evil)'),
    'https://localhost:9771/index.html?file=sound-deck&figure=sine_tone', 'an unknown colour is dropped');
  assert.equal(figureUrl(fieldUrl, '../escape', {id: 'figure-1', box: 'sine_tone'}), null);
  assert.equal(figureUrl('https://field.example/index.html', 'deck', {id: 'figure-1', box: 'sine_tone'}), null);
  for (const bad of [{id: 'figure 1', box: 'x'}, {id: 'f', box: '../x'}, {id: 'f', box: 'x', channels: ['a,b']},
    {id: 'f', box: 'x', rect: [0.5, 0.5, 0.6, 0.6]}, null]) assert.equal(validFigure(bad), false);
});

test('guide states carry at most eight uniquely named figures, on HTML slides only', () => {
  const state = figures => ({slideshowId: 's', hostUid: 'h', title: 'T', titles: ['One'], index: 0,
    content: {type: 'html', html: '<div data-field-figure="figure-1"></div>', figures}});
  assert.equal(validateGuideState(state([{id: 'figure-1', box: 'tone'}])).content.figures.length, 1);
  assert.throws(() => validateGuideState(state([{id: 'a', box: 'tone'}, {id: 'a', box: 'wave'}])), /figures/);
  assert.throws(() => validateGuideState(state(Array.from({length: 9}, (_, i) => ({id: `f${i}`, box: 'tone'})))), /figures/);
  const webpage = {...state([{id: 'a', box: 'tone'}])};
  webpage.content = {type: 'webpage', url: 'https://example.org/', figures: [{id: 'a', box: 'tone'}]};
  assert.throws(() => validateGuideState(webpage), /figures/);
});
