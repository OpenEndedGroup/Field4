const editor = window.globalEditor;
if (!editor?.getModel()) throw new Error('Monaco editor is not open');
const actions = Object.values(window.allActions).flat();
const action = actions.find(a => a.id === 'delete-box');
if (!action || action.keyScope !== 'canvas' || action.keybindings.length !== 0)
    throw new Error('box deletion must not register an editor keybinding');
window.__boxDeleteHits = 0;
action.run = () => { window.__boxDeleteHits++; };
window.updateActions();
editor.getModel().setValue('alpha beta');
editor.setPosition({lineNumber: 1, column: 11});
editor.focus();
return {ok: true};
