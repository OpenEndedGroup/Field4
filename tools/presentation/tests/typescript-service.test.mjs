import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import {fileURLToPath} from 'node:url';
import {createPresentationTypeScriptService} from '../typescript-service.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../example');
const scene = path.join(root, 'src/scenes/intro.tsx');

test('the talk tsconfig supplies TSX diagnostics and Motion Canvas completions', () => {
  const service = createPresentationTypeScriptService(root);
  const content = fs.readFileSync(scene, 'utf8');
  try {
    assert.deepEqual(service.diagnostics({path: scene, content}), []);
    const wrong = content.replace('view.fill', 'view.noSuchField');
    assert.match(service.diagnostics({path: scene, content: wrong})[0].message, /noSuchField/);
    assert.deepEqual(service.diagnostics({path: scene, content}), []);
    const names = service.completions({
      path: scene, content, offset: content.indexOf('view.fill') + 'view.'.length,
    }).map(entry => entry.name);
    assert.ok(names.includes('add'));
    assert.ok(names.includes('fill'));
    assert.throws(() => service.diagnostics({path: fileURLToPath(import.meta.url), content}),
      /outside this presentation project/);
  } finally {
    service.dispose();
  }
});
