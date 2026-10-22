import fs from 'node:fs';
import path from 'node:path';
import {createRequire} from 'node:module';

// Field's editor has an in-memory model for each box. Use the talk's real tsconfig and
// node_modules for TSX diagnostics and completion instead of asking Monaco's isolated worker to
// guess whether Motion Canvas imports exist.
export function createPresentationTypeScriptService(projectRoot) {
  const root = fs.realpathSync(projectRoot);
  let ts;
  try { ts = createRequire(path.join(root, 'package.json'))('typescript'); }
  catch { throw new Error(`Install this talk's dependencies first: run npm ci in ${root}`); }
  const configPath = ts.findConfigFile(root, ts.sys.fileExists, 'tsconfig.json');
  if (!configPath) throw new Error('Presentation needs a tsconfig.json for TSX editing');
  const config = ts.readConfigFile(configPath, ts.sys.readFile);
  if (config.error) throw new Error(ts.flattenDiagnosticMessageText(config.error.messageText, '\n'));
  const parsed = ts.parseJsonConfigFileContent(config.config, ts.sys, root);
  if (parsed.errors.length) throw new Error(parsed.errors.map(error =>
    ts.flattenDiagnosticMessageText(error.messageText, '\n')).join('\n'));
  const files = new Set(parsed.fileNames.map(file => fs.realpathSync(file)));
  const overrides = new Map();
  let version = 0;

  const host = {
    getScriptFileNames: () => [...files],
    getScriptVersion(file) {
      const override = overrides.get(file);
      if (override) return String(override.version);
      try {
        const stat = fs.statSync(file);
        return `${stat.mtimeMs}:${stat.size}`;
      } catch { return '0'; }
    },
    getScriptSnapshot(file) {
      const content = overrides.get(file)?.content ?? ts.sys.readFile(file);
      return content === undefined ? undefined : ts.ScriptSnapshot.fromString(content);
    },
    getCompilationSettings: () => parsed.options,
    getCurrentDirectory: () => root,
    getDefaultLibFileName: options => ts.getDefaultLibFilePath(options),
    fileExists: ts.sys.fileExists,
    readFile: ts.sys.readFile,
    readDirectory: ts.sys.readDirectory,
    directoryExists: ts.sys.directoryExists,
    getDirectories: ts.sys.getDirectories,
    useCaseSensitiveFileNames: () => ts.sys.useCaseSensitiveFileNames,
    getNewLine: () => ts.sys.newLine,
  };
  const service = ts.createLanguageService(host, ts.createDocumentRegistry());

  function sourceFor(request) {
    if (!request || typeof request.path !== 'string' || typeof request.content !== 'string') {
      throw new Error('Missing TSX source');
    }
    if (Buffer.byteLength(request.content, 'utf8') > 1_000_000) throw new Error('TSX source is too large');
    const file = fs.realpathSync(request.path);
    if (!file.startsWith(`${root}${path.sep}`) || !/\.(ts|tsx)$/.test(file) || !files.has(file)) {
      throw new Error('Source is outside this presentation project');
    }
    if (overrides.get(file)?.content !== request.content) {
      overrides.set(file, {content: request.content, version: ++version});
    }
    return file;
  }

  function diagnostics(request) {
    const file = sourceFor(request);
    return [...service.getSyntacticDiagnostics(file), ...service.getSemanticDiagnostics(file)]
      .slice(0, 100)
      .map(diagnostic => ({
        start: diagnostic.start ?? 0,
        length: diagnostic.length ?? 1,
        code: diagnostic.code,
        category: diagnostic.category,
        message: ts.flattenDiagnosticMessageText(diagnostic.messageText, '\n'),
      }));
  }

  function completions(request) {
    const file = sourceFor(request);
    const offset = request.offset;
    if (!Number.isInteger(offset) || offset < 0 || offset > request.content.length) {
      throw new Error('Invalid completion position');
    }
    const result = service.getCompletionsAtPosition(file, offset, {
      includeCompletionsForModuleExports: true,
      includeCompletionsWithInsertText: true,
    });
    return (result?.entries ?? []).slice(0, 250).map(entry => ({
      name: entry.name,
      kind: entry.kind,
      sortText: entry.sortText,
      insertText: entry.insertText,
      replacementSpan: entry.replacementSpan,
    }));
  }

  return {diagnostics, completions, dispose: () => service.dispose()};
}
