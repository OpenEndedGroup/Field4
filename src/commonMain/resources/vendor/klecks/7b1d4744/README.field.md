Klecks embed assets built from https://github.com/bitbof/klecks at commit
7b1d4744b648de6133b0d45073af4efd2f998148 (MIT license in LICENSE).

Build commands: `npm ci`, `npm run lang:build`, `npm run icon:build`,
`npm run build:embed`. The runtime files from `dist/` are copied here without
source edits; source maps are omitted. The Field
adapter is `/klecks_field.js`; it uses Klecks' public embed API and Field's
existing output-request bridge.
