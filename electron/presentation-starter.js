const fs = require('node:fs');
const path = require('node:path');

/** Create one ordinary Field document; the bundled kit and authored talk travel together. */
function createPresentationDocument(workspace, destination) {
  const root = fs.realpathSync(workspace);
  const target = path.resolve(destination);
  if (fs.realpathSync(path.dirname(target)) !== root) {
    throw new Error('Choose a new document folder directly inside the current Field workspace.');
  }
  if (fs.existsSync(target)) throw new Error('That folder already exists. Choose a new document name.');
  fs.cpSync(path.resolve(__dirname, '../templates/presentation'), target, {recursive: true});
  return path.basename(target);
}

module.exports = {createPresentationDocument};
