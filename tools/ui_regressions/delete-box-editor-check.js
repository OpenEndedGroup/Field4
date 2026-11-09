const editor = window.globalEditor;
if (window.__boxDeleteHits !== 0) throw new Error('Ctrl+Backspace reached box deletion from Monaco');
if (editor.getValue() !== 'alpha ') throw new Error(`word deletion failed: ${JSON.stringify(editor.getValue())}`);
if (document.activeElement && document.activeElement !== document.body) document.activeElement.blur();
return {ok: true, text: editor.getValue()};
