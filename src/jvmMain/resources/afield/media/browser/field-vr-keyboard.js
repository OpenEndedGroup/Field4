(() => {
    if (document.getElementById('field-vr-tools')) return true;
    const host = document.createElement('div');
    host.id = 'field-vr-tools';
    host.style.cssText = 'position:fixed;bottom:8px;left:50%;transform:translateX(-50%);z-index:2147483647';
    const root = host.attachShadow({mode: 'open'});
    root.innerHTML = `<style>
        :host{font:18px system-ui;color:#eee;user-select:none}
        .tools{background:#151c28ee;border:1px solid #789;border-radius:12px;padding:8px;box-shadow:0 4px 20px #0009}
        .row{display:flex;justify-content:center;gap:5px;margin:4px 0}
        button{font:inherit;color:#fff;background:#304158;border:1px solid #678;border-radius:6px;min-width:45px;height:42px;padding:0 12px;cursor:pointer}
        button:hover{background:#476b90}button:active,button.on{background:#267ab5}
        #keys[hidden]{display:none}#status{font:13px system-ui;text-align:center;min-height:17px;color:#bce}
    </style><div class="tools"><div class="row" id="toolbar"></div><div id="keys" hidden></div><div id="status">Trigger / pinch: click · grip: move panel · stick: scroll</div></div>`;
    let shift = false;
    const request = payload => window.fieldPuppet.request('field-vr-input', payload)
        .catch(error => { root.getElementById('status').textContent = error.message; });
    const key = (name, extra = {}) => request({action:'key', key:name, ...extra});
    const button = (row, label, action) => {
        const b = document.createElement('button');
        b.type = 'button'; b.textContent = label; b.setAttribute('aria-label', label);
        b.tabIndex = -1;
        b.addEventListener('click', event => { event.stopPropagation(); action(); });
        row.appendChild(b); return b;
    };
    // Keep Monaco, palette search, or the active iframe focused while pressing virtual keys.
    for (const type of ['pointerdown', 'mousedown']) {
        root.addEventListener(type, event => { event.preventDefault(); event.stopPropagation(); });
    }
    const toolbar = root.getElementById('toolbar');
    const keys = root.getElementById('keys');
    button(toolbar, 'Keyboard', () => { keys.hidden = !keys.hidden; });
    button(toolbar, 'Commands', () => key('F1'));
    button(toolbar, 'Run box', () => key('ArrowUp', {alt:true}));
    button(toolbar, 'Run line', () => key('Enter', {alt:true}));
    button(toolbar, 'Esc', () => key('Escape'));
    button(toolbar, 'Recenter', () => request({action:'recenter'}));
    button(toolbar, 'Real keyboard', () => request({action:'keyboard-focus'}));
    const rows = [
        ['1','2','3','4','5','6','7','8','9','0','-','='],
        ['q','w','e','r','t','y','u','i','o','p','[',']'],
        ['a','s','d','f','g','h','j','k','l',';',"'",'\\'],
        ['z','x','c','v','b','n','m',',','.','/','`'],
        ['!','@','#','$','%','^','&','*','(',')','_','+'],
        ['{','}','<','>',':','"','?','|','~']
    ];
    for (const chars of rows) {
        const row = document.createElement('div'); row.className = 'row'; keys.appendChild(row);
        for (const ch of chars) button(row, ch, () => request({action:'text', text:shift ? ch.toUpperCase() : ch}));
    }
    const row = document.createElement('div'); row.className = 'row'; keys.appendChild(row);
    const shiftButton = button(row, 'Shift', () => { shift = !shift; shiftButton.classList.toggle('on', shift); });
    button(row, 'Tab', () => key('Tab'));
    button(row, 'Space', () => request({action:'text', text:' '}));
    button(row, '⌫', () => key('Backspace'));
    button(row, 'Enter', () => key('Enter'));
    const editing = document.createElement('div'); editing.className = 'row'; keys.appendChild(editing);
    for (const [label, name] of [['←','ArrowLeft'],['↑','ArrowUp'],['↓','ArrowDown'],['→','ArrowRight']])
        button(editing, label, () => key(name, {shift}));
    for (const [label, name] of [['Select all','a'],['Cut','x'],['Copy','c'],['Paste','v'],['Undo','z']])
        button(editing, label, () => key(name, {control:true}));
    document.body.appendChild(host);
    return true;
})()
