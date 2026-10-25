import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createRequire} from 'node:module';
import cues from '../../electron/presentation-cues.js';

const runtimeRoot = path.dirname(fileURLToPath(import.meta.url));

export function validateProject(directory) {
  const root = fs.realpathSync(directory);
  if (!fs.statSync(root).isDirectory()) throw new Error('Presentation path is not a directory');
  for (const required of ['package.json', 'src/project.ts', 'presentation.json', 'field-presenter.html', 'field-control.html']) {
    if (!fs.statSync(path.join(root, required), {throwIfNoEntry: false})?.isFile()) {
      throw new Error(`Presentation is missing ${required}`);
    }
  }
  if (!fs.statSync(path.join(root, 'src/scenes'), {throwIfNoEntry: false})?.isDirectory()) {
    throw new Error('Presentation is missing src/scenes');
  }
  if (!fs.statSync(path.join(root, 'tsconfig.json'), {throwIfNoEntry: false})?.isFile()) {
    throw new Error('Presentation is missing tsconfig.json');
  }
  const manifest = JSON.parse(fs.readFileSync(path.join(root, 'presentation.json'), 'utf8'));
  if (!manifest || typeof manifest !== 'object' || !manifest.slides ||
      typeof manifest.slides !== 'object' || Array.isArray(manifest.slides)) {
    throw new Error('presentation.json needs a slides object');
  }
  if (manifest.transitionMs != null && (!Number.isFinite(manifest.transitionMs) || manifest.transitionMs < 0 || manifest.transitionMs > 2000)) {
    throw new Error('transitionMs must be a number between 0 and 2000');
  }
  for (const [cue, slide] of Object.entries(manifest.slides)) {
    if (!slide || !['webpage', 'video', 'youtube', 'field-document'].includes(slide.type) ||
        !cues.guestUrl(slide, 'http://127.0.0.1', 'https://localhost/index.html')) {
      throw new Error(`Cue “${cue}” needs a valid webpage, YouTube video, or Field document`);
    }
    if (slide.rect != null && !cues.validRect(slide.rect)) throw new Error(`Cue “${cue}” has an invalid rect`);
  }
  return {root, manifest};
}

export async function startPresentationServer(directory, {port = 0} = {}) {
  const {root} = validateProject(directory);
  const projectRequire = createRequire(path.join(root, 'package.json'));
  let createServer, motionCanvas;
  try {
    ({createServer} = projectRequire('vite'));
    motionCanvas = projectRequire('@motion-canvas/vite-plugin').default;
  } catch {
    throw new Error(`Install this talk's dependencies first: run npm ci in ${root}`);
  }
  const server = await createServer({
    root,
    configFile: false,
    plugins: motionCanvas({project: path.join(root, 'src/project.ts')}),
    server: {host: '127.0.0.1', port, strictPort: port !== 0, fs: {allow: [root, runtimeRoot]}},
    clearScreen: false,
  });
  await server.listen();
  const address = server.httpServer.address();
  return {server, url: `http://127.0.0.1:${address.port}`, root};
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const target = process.argv[2] ?? path.join(runtimeRoot, 'example');
  try {
    const started = await startPresentationServer(target, {port: Number(process.env.FIELD_PRESENTATION_PORT || 0)});
    console.log(`Field presentation preview: ${started.url}/field-presenter.html`);
  } catch (error) {
    console.error(error);
    process.exitCode = 1;
  }
}
