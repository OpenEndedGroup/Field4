// Welcome's kit library. A package is registered here; its runtime takes effect only on restart.
(() => {
  const root = document.getElementById('kit-library');
  if (!root) return;
  let library = {kits: [], bundled: [], restartRequired: false};
  let instances = [];
  let busy = false;
  const status = root.querySelector('[data-kit-status]');
  const list = root.querySelector('[data-kit-list]');
  const search = root.querySelector('[data-kit-search]');
  const path = root.querySelector('[data-kit-path]');
  const restart = root.querySelector('[data-kit-restart]');
  const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  async function request(url, body) {
    const response = await fetch(url, body === undefined ? {cache:'no-store'} : {
      method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(body),
    });
    const result = await response.json();
    if (!response.ok) throw Error(result.error || `HTTP ${response.status}`);
    return result;
  }
  function render() {
    const open = new Set([...list.querySelectorAll('details[open][data-kit]')].map(e => e.dataset.kit));
    const query = search.value.trim().toLocaleLowerCase();
    const kits = library.kits.filter(k => `${k.manifest?.title} ${k.manifest?.description} ${k.installed.id}`.toLocaleLowerCase().includes(query));
    restart.hidden = !library.restartRequired;
    root.querySelector('[data-kit-restart-summary]').hidden = !library.restartRequired;
    root.querySelector('[data-kit-count]').textContent = `${library.kits.length}`;
    list.innerHTML = kits.map(kit => {
      const m = kit.manifest;
      const id = kit.installed.id;
      const active = kit.state === 'active';
      const owned = instances.filter(i => i.record.kit === id);
      const runtime = m?.runtime || {};
      const requirements = [m?.host ? 'Dedicated project host' : '',runtime.extensionDirs?.length ? 'Kotlin / resources' : '', runtime.jars?.length ? `${runtime.jars.length} JARs` : '',
        runtime.dependencies?.length ? `${runtime.dependencies.length} libraries` : '', Object.keys(runtime.nativeDirectories || {}).length ? 'Native libraries' : '', runtime.lifecycle ? 'Lifecycle hooks' : ''].filter(Boolean);
      return `<details class="kit-card" data-kit="${escape(id)}" ${open.has(id) || library.kits.length === 1 ? 'open' : ''}>
        <summary><span>${escape(m?.title || id)}</span><span class="kit-meta">${escape(m?.version || '')} · ${escape(kit.state.replaceAll('-', ' '))}</span></summary>
        <p>${escape(m?.description || kit.message)}</p>
        <p class="kit-meta">${escape(requirements.join(' · ') || 'Document starting points')} · revision ${escape(kit.installed.revision.slice(0,8))}</p>
        ${kit.message ? `<p role="alert">${escape(kit.message)}</p>` : ''}
        <div class="kit-actions">${m?.host ? `<button data-kit-host="${escape(id)}">Open kit host</button>` : ''}<button data-kit-guide="${escape(id)}">Read guide</button><button data-kit-update="${escape(id)}">Check source for updates</button><button data-kit-remove="${escape(id)}">Remove from this host</button></div>
        <div class="kit-points">${Object.entries(m?.startingPoints || {}).map(([point,p]) => `<div><div><b>${escape(p.title)}</b><p>${escape(p.description)}</p></div><button data-kit-create="${escape(id)}" data-point="${escape(point)}" ${active || m?.host ? '' : 'disabled'}>${m?.host ? 'Create in kit host' : 'Create document'}</button></div>`).join('')}</div>
        ${owned.length ? `<details><summary>Your documents · ${owned.length}</summary>${owned.map(i => `<div class="kit-instance"><a href="/index.html?file=${encodeURIComponent(i.document)}">${escape(i.document)}</a><span class="kit-meta">${escape(i.record.revision.slice(0,8))}${i.record.revision !== i.availableRevision ? ' · update available' : ''}</span><button data-kit-run="${escape(i.document)}">Run</button><button data-kit-inspect="${escape(i.document)}">Inspect update</button></div>`).join('')}</details>` : ''}
      </details>`;
    }).join('') || '<p class="kit-meta">Add a kit folder to make its starting points and runtime available here.</p>';
    const bundled = root.querySelector('[data-kit-bundled]');
    bundled.innerHTML = library.bundled.filter(p => !library.kits.some(k => k.installed.source === p)).map(p => `<button data-kit-bundle="${escape(p)}">Add ${escape(p.split(/[\\/]/).at(-1))} starter kit</button>`).join('');
    root.querySelectorAll('[data-kit-add],[data-kit-browse],[data-kit-restart],[data-kit-close]').forEach(button => { button.disabled = busy; });
    if (busy) list.querySelectorAll('button').forEach(button => { button.disabled = true; });
  }
  async function load() {
    library = await request('/api/kits');
    instances = await request('/api/kits/instances');
    render();
  }
  async function operation(action) {
    if (busy) return;
    busy = true; render();
    try { await action(); await load(); }
    catch (error) { status.textContent = error.message; }
    finally { busy = false; render(); }
  }
  async function install(source) {
    if (!source.trim()) throw Error('Choose a kit folder containing field-kit.json.');
    status.textContent = 'Checking and copying the kit…';
    library = await request('/api/kits/install', {path:source.trim()});
    status.textContent = library.restartRequired ? 'Kit registered. Restart Field to load its classes, libraries and lifecycle.' : 'This exact revision is already loaded.';
    path.value = '';
  }
  root.querySelector('[data-kit-add]').addEventListener('click', () => operation(() => install(path.value)));
  path.addEventListener('keydown', event => { if (event.key === 'Enter') operation(() => install(path.value)); });
  search.addEventListener('input', render);
  const browse = root.querySelector('[data-kit-browse]');
  browse.hidden = !window.fieldElectron?.chooseKitFolder;
  browse.addEventListener('click', async () => {
    const selected = await window.fieldElectron.chooseKitFolder();
    if (selected) path.value = selected;
  });
  root.addEventListener('dragover', event => { if (window.fieldElectron?.filePath && event.dataTransfer.types.includes('Files')) event.preventDefault(); });
  root.addEventListener('drop', event => {
    if (!window.fieldElectron?.filePath || !event.dataTransfer.files.length) return;
    event.preventDefault(); path.value = window.fieldElectron.filePath(event.dataTransfer.files[0]);
  });
  restart.addEventListener('click', () => operation(async () => {
    await request('/api/kits/restart', {});
    status.textContent = 'Preparing kit runtimes while Field remains running. This page will reconnect after restart.';
    const end = Date.now()+15*60*1000;
    while (Date.now()<end) {
      await new Promise(resolve => setTimeout(resolve,1500));
      try {
        const progress = await request('/api/kits/restart-status');
        if (progress.phase === 'failed') throw Object.assign(Error(progress.message), {kitBuildFailed:true});
        if (progress.message) status.textContent = progress.message;
        const next = await request('/api/kits'); if (!next.restartRequired) { location.reload(); return; }
      } catch (error) { if (error.kitBuildFailed) throw error; }
    }
    throw Error('Field has not reconnected. Check its launch terminal or workspace-restart.log, then reload this page.');
  }));
  list.addEventListener('click', event => {
    const button = event.target.closest('button'); if (!button) return;
    if (button.dataset.kitGuide) {
      const dialog = root.querySelector('dialog');
      dialog.querySelector('iframe').src = `/api/kits/guide?id=${encodeURIComponent(button.dataset.kitGuide)}`;
      dialog.showModal(); return;
    }
    operation(async () => {
      if (button.dataset.kitHost) {
        status.textContent = 'Opening the kit’s existing project host…';
        const result = await request('/api/kits/open-host', {id:button.dataset.kitHost}); location.href = result.url;
      } else if (button.dataset.kitCreate) {
        const kit = library.kits.find(k => k.installed.id === button.dataset.kitCreate);
        if (kit.manifest?.host) {
          status.textContent = 'Preparing the kit’s dedicated host…';
          const result = await request('/api/kits/open-host', {id:button.dataset.kitCreate, startingPoint:button.dataset.point});
          location.href = result.url; return;
        }
        const result = await request('/api/kits/create', {id:button.dataset.kitCreate, startingPoint:button.dataset.point});
        location.href = `/index.html?file=${encodeURIComponent(result.filename)}`;
      } else if (button.dataset.kitUpdate) {
        const kit = library.kits.find(k => k.installed.id === button.dataset.kitUpdate);
        await install(kit.installed.source);
      } else if (button.dataset.kitRemove) {
        library = await request('/api/kits/remove', {id:button.dataset.kitRemove});
        status.textContent = 'Removed from the next runtime profile. Documents and saved package revisions are retained. Restart Field to finish unloading.';
      } else if (button.dataset.kitRun) {
        const result = await request('/api/kits/activate', {document:button.dataset.kitRun});
        if (result.url) location.href = result.url; else status.textContent = result.message;
      } else if (button.dataset.kitInspect) {
        const name = button.dataset.kitInspect;
        const result = await request('/api/kits/inspect', {document:name});
        const dialog = root.querySelector('[data-kit-update-dialog]');
        dialog.querySelector('pre').textContent = `${name}: ${result.record.revision.slice(0,8)} → ${result.availableRevision.slice(0,8)}\n` +
          (result.changes.map(c => `${c.state}: ${c.path}`).join('\n') || 'No file changes.');
        const apply = dialog.querySelector('[data-kit-apply]'); apply.disabled = result.conflicts.length > 0 || library.restartRequired;
        apply.onclick = () => operation(async () => { await request('/api/kits/migrate', {document:name}); dialog.close(); status.textContent = 'Document updated. Its local-only files and edits were preserved.'; });
        dialog.showModal();
      }
    });
  });
  root.querySelector('[data-kit-bundled]').addEventListener('click', event => {
    const source = event.target.closest('[data-kit-bundle]')?.dataset.kitBundle;
    if (source) operation(() => install(source));
  });
  root.querySelectorAll('[data-kit-close]').forEach(b => b.addEventListener('click', () => b.closest('dialog').close()));
  load().catch(error => { status.textContent = error.message; });
})();
