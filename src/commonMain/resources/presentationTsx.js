let sourcePaths = new Set();
let installed = false;
let retry = null;
let refresh = () => {};

function selectedSource(editor) {
  const box = window.currentSelectedCode?.[0];
  const path = box?.file_proxy_path;
  if (window.currentSelectedCode?.length !== 1 || window.currentSelectedProperty !== 'contents' ||
      !sourcePaths.has(path) || !/\.tsx?$/.test(path) || editor.getModel() == null) return null;
  return {path, model: editor.getModel()};
}

export function attachPresentationTypeScript(paths) {
  sourcePaths = new Set(paths.filter(path => typeof path === 'string' && /\.tsx?$/.test(path)));
  if (installed) { refresh(); return; }
  if (retry) return;
  const installWhenReady = () => {
    const monaco = window.globalMonaco;
    const editor = window.globalEditor;
    const bridge = window.fieldElectron?.presentationTypeScript;
    if (!monaco || !editor || !bridge) return;
    clearInterval(retry);
    retry = null;
    installed = true;

    const defaults = monaco.languages.typescript.typescriptDefaults;
    const previousDiagnostics = defaults.getDiagnosticsOptions();
    defaults.setCompilerOptions({
      ...defaults.getCompilerOptions(),
      jsx: monaco.languages.typescript.JsxEmit.ReactJSX,
      allowNonTsExtensions: true,
    });
    let diagnosticsDisabled = false;
    let diagnosticTimer = null;

    const updateDiagnostics = () => {
      const selected = selectedSource(editor);
      if (!!selected !== diagnosticsDisabled) {
        diagnosticsDisabled = !!selected;
        defaults.setDiagnosticsOptions(selected
          ? {...previousDiagnostics, noSemanticValidation: true, noSyntaxValidation: true,
              noSuggestionDiagnostics: true}
          : previousDiagnostics);
      }
      if (!selected) return;
      const {path, model} = selected;
      const content = model.getValue();
      Promise.resolve(bridge({action: 'diagnostics', path, content})).then(diagnostics => {
        if (editor.getModel() !== model || model.getValue() !== content) return;
        monaco.editor.setModelMarkers(model, 'field-presentation-tsx', diagnostics.map(item => {
          const start = model.getPositionAt(item.start);
          const end = model.getPositionAt(item.start + Math.max(item.length, 1));
          return {
            startLineNumber: start.lineNumber, startColumn: start.column,
            endLineNumber: end.lineNumber, endColumn: end.column,
            message: item.message, code: String(item.code),
            severity: item.category === 1 ? monaco.MarkerSeverity.Error : monaco.MarkerSeverity.Warning,
          };
        }));
      }).catch(error => console.warn('Presentation TSX diagnostics failed', error));
    };
    const scheduleDiagnostics = () => {
      clearTimeout(diagnosticTimer);
      diagnosticTimer = setTimeout(updateDiagnostics, 250);
    };
    refresh = scheduleDiagnostics;
    editor.onDidChangeModel(scheduleDiagnostics);
    editor.onDidChangeModelContent(scheduleDiagnostics);
    scheduleDiagnostics();

    window.fieldPresentationTypeScriptCompletion = {
      supports(candidateEditor, model) {
        return candidateEditor === editor && selectedSource(editor)?.model === model;
      },
      async provide(candidateEditor, model, position) {
        const selected = selectedSource(candidateEditor);
        if (!selected || selected.model !== model) return [];
        let entries;
        try {
          entries = await bridge({
            action: 'completions', path: selected.path,
            content: model.getValue(), offset: model.getOffsetAt(position),
          });
        } catch (error) {
          console.warn('Presentation TSX completion failed', error);
          return [];
        }
        const word = model.getWordUntilPosition(position);
        const defaultRange = {
          startLineNumber: position.lineNumber, startColumn: word.startColumn,
          endLineNumber: position.lineNumber, endColumn: word.endColumn,
        };
        return entries.filter(entry => !entry.source).map(entry => {
          let range = defaultRange;
          if (entry.replacementSpan) {
            const start = model.getPositionAt(entry.replacementSpan.start);
            const end = model.getPositionAt(entry.replacementSpan.start + entry.replacementSpan.length);
            range = {
              startLineNumber: start.lineNumber, startColumn: start.column,
              endLineNumber: end.lineNumber, endColumn: end.column,
            };
          }
          return {
            label: entry.name, data: {icon: entry.kind},
            insertText: entry.insertText || entry.name, sortText: entry.sortText,
            range,
          };
        });
      },
    };
  };
  retry = setInterval(installWhenReady, 100);
  installWhenReady();
}
