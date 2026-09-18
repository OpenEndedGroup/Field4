# Minimal repro: Electron OSR shared texture is empty on Linux

Two files and no dependencies beyond Electron. It asks Electron to import its *own* offscreen
shared texture and read the pixels back through WebCodecs, so nothing of Field's is in the path.

```bash
electron/node_modules/electron/dist/electron dev_docs/upstream/electron-osr-repro
```

Prints `=> CONTENT` on a platform where this works, and `=> BLANK` here. The page under test is a
solid animating background, so a correct buffer cannot be zero.

The full report and evidence table are in
[`../electron-osr-linux-shared-texture.md`](../electron-osr-linux-shared-texture.md); the Field-side
context is in [`../../external_browser_surfaces.md`](../../external_browser_surfaces.md).
