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
