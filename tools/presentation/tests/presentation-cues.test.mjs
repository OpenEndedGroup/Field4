import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {createRequire} from 'node:module';
import {createTalk} from '../new-talk.mjs';
import {validateProject} from '../server.mjs';

const require = createRequire(import.meta.url);
const {fieldDocumentUrl, fieldActions, guestUrl, validRect} = require('../../../electron/presentation-cues.js');

test('Field cues stay on the current local Field server and reject path-like names', () => {
  const fieldUrl = 'https://localhost:9488/index.html?file=old-document';
  assert.equal(fieldDocumentUrl(fieldUrl, 'field demo'),
    'https://localhost:9488/index.html?file=field+demo');
  for (const name of ['../escape', '/tmp/escape', '..', '', 'a\\b']) {
    assert.equal(fieldDocumentUrl(fieldUrl, name), null);
  }
  assert.equal(fieldDocumentUrl('https://field.example/index.html', 'demo'), null);
  assert.equal(guestUrl({type: 'field-document', document: 'field-demo'}, 'http://127.0.0.1:4444', fieldUrl),
    'https://localhost:9488/index.html?file=field-demo');
  assert.deepEqual(validRect([0.04, 0.16, 0.92, 0.8]), [0.04, 0.16, 0.92, 0.8]);
  assert.equal(validRect([0.5, 0.5, 0.6, 0.6]), null);
});

test('only a Field document cue exposes explicit guide and macro actions', () => {
  assert.deepEqual(fieldActions({type: 'webpage', macro: {name: 'do_it'}}), {guide: false, interactiveGuide: false, macro: null});
  assert.deepEqual(fieldActions({type: 'field-document', macro: {name: 'reveal_wave'}}),
    {guide: true, interactiveGuide: false, macro: {name: 'reveal_wave', scope: 'document', project: ''}});
  assert.deepEqual(fieldActions({type: 'field-document', macro: {name: '../escape'}}),
    {guide: true, interactiveGuide: false, macro: null});
  assert.deepEqual(fieldActions({type: 'field-document', interactiveGuide: true}),
    {guide: true, interactiveGuide: true, macro: null});
});

test('new talk contains the editable project, Field document, guide and macro', t => {
  const parent = fs.mkdtempSync(path.join(os.tmpdir(), 'field-talk-'));
  t.after(() => fs.rmSync(parent, {recursive: true, force: true}));
  const destination = createTalk(path.join(parent, 'my-talk'));
  for (const relative of ['package-lock.json', 'src/project.ts', 'src/scenes/field.tsx',
    'presentation.json', 'field-demo/metadata.edn', 'field-demo/guides/teaching.md',
    'field-demo/macros/reveal_wave.yaml', 'field-demo/present_teaching_guide_code_.kt']) {
    assert.ok(fs.statSync(path.join(destination, relative)).isFile(), relative);
  }
  assert.equal(fs.existsSync(path.join(destination, 'node_modules')), false);
  assert.throws(() => createTalk(destination), /already exists/);
  assert.equal(validateProject(destination).manifest.slides['field:field'].document, 'field-demo');
  const manifest = path.join(destination, 'presentation.json');
  fs.writeFileSync(manifest, JSON.stringify({slides: null}));
  assert.throws(() => validateProject(destination), /slides object/);
  fs.writeFileSync(manifest, JSON.stringify({slides: {'field:field': {type: 'field-document', document: '../escape'}}}));
  assert.throws(() => validateProject(destination), /valid webpage/);
});

test('native guide protocol accepts Kotlin defaults and rejects invalid audience states', () => {
  const {validateGuideState} = require('../../../electron/presentation-cues.js');
  const state = {slideshowId: 'presentation_host', hostUid: 'host', title: 'My talk',
    index: 0, titles: ['Hello'], content: {html: '<h1>Hello</h1>'}};
  assert.deepEqual(validateGuideState(state).content, {type: 'html', html: '<h1>Hello</h1>'});
  assert.equal(validateGuideState(state).transitionMs, 450);
  assert.equal(validateGuideState({...state, index: -1, content: null}).content, null);
  for (const content of [{type: 'field-document', document: '../private'},
    {type: 'webpage', url: 'file:///tmp/private'}, {type: 'youtube', videoId: 'invalid'},
    {type: 'html', html: 'x'.repeat(1000001)}]) {
    assert.throws(() => validateGuideState({...state, content}), /audience content/);
  }
  assert.throws(() => validateGuideState({...state, index: 1}), /presentation state/);
});

test('native starter is a portable Field document with no per-talk JS toolchain', t => {
  const {createPresentationDocument} = require('../../../electron/presentation-starter.js');
  const parent = fs.mkdtempSync(path.join(os.tmpdir(), 'field-native-talk-'));
  t.after(() => fs.rmSync(parent, {recursive: true, force: true}));
  const destination = path.join(parent, 'my-talk');
  assert.equal(createPresentationDocument(parent, destination), 'my-talk');
  for (const relative of ['metadata.edn', 'present_talk_code_.kt', 'kit.json',
    'files/kit.json', 'files/presentation.css', 'roles/teaching-guide/code.kt', 'macros/reveal_wave.yaml']) {
    assert.ok(fs.statSync(path.join(destination, relative)).isFile(), relative);
  }
  assert.equal(fs.existsSync(path.join(destination, 'package.json')), false);
  assert.equal(fs.existsSync(path.join(destination, 'node_modules')), false);
  assert.equal(fs.existsSync(path.join(destination, 'src/project.ts')), false);
  assert.throws(() => createPresentationDocument(parent, destination), /already exists/);
  assert.throws(() => createPresentationDocument(parent, path.join(os.tmpdir(), 'outside-talk')), /inside/);
  const record = JSON.parse(fs.readFileSync(path.join(destination, 'files/kit.json'), 'utf8'));
  assert.equal(record.directory, undefined);
  assert.equal(record.roles['teaching-guide'].name, 'Teaching guide');
});


test('native outline paths are optional, aligned and bounded', () => {
  const {validateGuideState} = require('../../../electron/presentation-cues.js');
  const state = {slideshowId: 'presentation_host', hostUid: 'host', title: 'Talk',
    index: 0, titles: ['One', 'Two'], content: {html: '<h1>One</h1>'}};
  const paths = [['Opening'], ['Experiments', 'Waves']];
  assert.deepEqual(validateGuideState({...state, sectionPaths: paths}).sectionPaths, paths);
  assert.doesNotThrow(() => validateGuideState(state));
  for (const sectionPaths of [[['One']], [null, []], [[''], []],
    [[42], []], [Array(17).fill('Deep'), []], 'bad']) {
    assert.throws(() => validateGuideState({...state, sectionPaths}), /presentation sections/);
  }
});

test('native cue state accepts defaults and rejects impossible positions', () => {
  const {validateGuideState} = require('../../../electron/presentation-cues.js');
  const state = {slideshowId: 'lesson', hostUid: 'host', title: 'Lesson', index: 0,
    titles: ['Predict'], content: {html: '<h1>Predict</h1>'}};
  assert.equal(validateGuideState(state).cueIndex, 0);
  assert.deepEqual(validateGuideState(state).cueTitles, []);
  const cue = {...state, cueIndex: 1, cueTitles: ['Explain'], checkpoint: 'Baseline', cueAction: true};
  assert.equal(validateGuideState(cue).cueIndex, 1);
  for (const invalid of [{cueIndex: -1}, {cueIndex: 2, cueTitles: ['Explain']},
    {cueTitles: [null]}, {cueAction: 'yes'}, {checkpoint: 12}]) {
    assert.throws(() => validateGuideState({...state, ...invalid}), /cue state/);
  }
});
