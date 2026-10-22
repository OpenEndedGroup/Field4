import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const example = path.join(path.dirname(fileURLToPath(import.meta.url)), 'example');

export function createTalk(target) {
  if (!target) throw new Error('Choose a new directory for the presentation');
  const destination = path.resolve(target);
  if (fs.existsSync(destination)) throw new Error(`Directory already exists: ${destination}`);
  fs.cpSync(example, destination, {
    recursive: true,
    filter: source => !['node_modules', '.git', 'dist'].includes(path.basename(source)),
  });
  return destination;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const created = createTalk(process.argv[2]);
    console.log(`Created ${created}\nNext: cd ${JSON.stringify(created)} && npm ci && git init`);
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
