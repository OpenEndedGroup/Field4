(() => {
  const bridge = window.fieldPresentation;
  const error = document.getElementById('error');
  const slides = document.getElementById('slides');
  const search = document.getElementById('slide-search');
  const matches = document.getElementById('slide-matches');
  let info;
  let signature;
  let previousIndex = -1;
  let groups = [];
  let rows = [];
  let savedOpen;
  let choice = -1;
  let filtered = [];
  const textSearch = window.FieldPresentationSearch;

  // A press shows for at least PRESS_MS, however quick the click: :active alone lasts only as long
  // as the button is held, which on a trackpad tap is too short to see.
  const PRESS_MS = 120;
  const PENDING_FAIL_MS = 5000;
  const pendingNote = document.getElementById('pending-note');
  const progress = document.getElementById('progress');
  let pending = null;

  function press(element) {
    if (!element || element.disabled) return;
    element.classList.add('pressed');
    element.pressedAt = performance.now();
    clearTimeout(element.pressTimer);
  }
  function release(element, now = performance.now()) {
    clearTimeout(element.pressTimer);
    element.pressTimer = setTimeout(() => element.classList.remove('pressed'), Math.max(0, PRESS_MS - (now - (element.pressedAt || 0))));
  }
  const pressable = target => target.closest?.('button, summary, input');
  addEventListener('pointerdown', event => { if (event.button === 0) press(pressable(event.target)); }, true);
  for (const type of ['pointerup', 'pointercancel']) {
    addEventListener(type, () => document.querySelectorAll('.pressed').forEach(element => release(element)), true);
  }
  /** A key that moves the talk presses its button as a click would. */
  function pressBriefly(element) { press(element); release(element); }

  /**
   * Where [action] takes the talk from the step it is on: {index, cueIndex}, cueIndex null when the
   * step decides; null when it goes nowhere. electron/presentation-cues.js has the same rule for the
   * move Electron makes, so what is shown pending here is what Electron goes to.
   */
  function targetFor(action, id) {
    if (!info) return null;
    const cues = info.cueTitles?.length || 0;
    const cue = info.cueIndex || 0;
    let target = null;
    if (action === 'next') target = cue < cues ? {index: info.index, cueIndex: cue + 1} : {index: info.index + 1, cueIndex: 0};
    if (action === 'previous') target = cue > 0 ? {index: info.index, cueIndex: cue - 1} : {index: info.index - 1, cueIndex: null};
    if (action === 'goto') target = {index: Number(id), cueIndex: 0};
    if (!target || !Number.isInteger(target.index) || target.index < 0 || target.index >= info.count) return null;
    return target.index === info.index && target.cueIndex === cue ? null : target;
  }

  function slideLabel(target) {
    const number = String(target.index + 1).padStart(String(info.count).length, '0');
    const cue = target.index === info.index && target.cueIndex ? ` · cue ${target.cueIndex}` : '';
    return `${number} · ${info.titles[target.index]}${cue}`;
  }

  /** Marks [target] as where the talk is going, until a state confirms it or [PENDING_FAIL_MS] pass. */
  function showPending(target, action) {
    if (!target) return;
    clearTimeout(pending?.timer);
    pending = {...target, action, failed: false, timer: setTimeout(failPending, PENDING_FAIL_MS)};
    renderPending();
  }
  function failPending() {
    if (!pending) return;
    pending.failed = true;
    renderPending();
  }
  function clearPending() {
    clearTimeout(pending?.timer);
    pending = null;
    renderPending();
  }
  function renderPending() {
    const going = !!pending && !pending.failed;
    rows.forEach(row => row.element.classList.toggle('pending', going && row.index === pending.index));
    document.getElementById('next').classList.toggle('pending', going && pending.action === 'next');
    document.getElementById('previous').classList.toggle('pending', going && pending.action === 'previous');
    progress.hidden = !going;
    pendingNote.hidden = !pending;
    pendingNote.classList.toggle('failed', !!pending?.failed);
    pendingNote.textContent = !pending ? '' : pending.failed
      ? `Did not reach ${slideLabel(pending)}: the talk has not answered`
      : `Going to ${slideLabel(pending)}…`;
  }

  /** Moves the talk, showing where to at once rather than when the audience changes. */
  function move(action, id) {
    // With a linked page open, Next, Previous and the current slide only go back to the slide.
    const returning = link && (action !== 'goto' || Number(id) === info?.index);
    if (!returning) showPending(targetFor(action, id), action);
    bridge.action(action, id);
  }

  // A page opened from a link on the slide covers the audience; the slide waits underneath.
  let link = null;
  const linkBar = document.getElementById('link-bar');
  const linkTitle = document.getElementById('link-title');
  const back = document.getElementById('close-link');
  back.addEventListener('click', () => bridge.action('close-link'));
  function renderLink() {
    linkBar.hidden = !link;
    document.body.classList.toggle('showing-link', !!link);
    if (!link || !info) return;
    let where = link.url;
    try { const url = new URL(link.url); where = url.protocol === 'file:' ? decodeURIComponent(url.pathname.split('/').pop()) : url.host; } catch {}
    linkTitle.textContent = link.title && link.title !== link.url ? `${link.title} · ${where}` : where;
    back.textContent = `← Back to ${String(info.index + 1).padStart(String(info.count).length, '0')} · ${info.titles[info.index]}`;
  }
  bridge.onLink?.(next => { link = next; renderLink(); });

  for (const action of ['previous', 'next']) {
    document.getElementById(action).addEventListener('click', () => move(action));
  }
  for (const action of ['reload', 'show-audience', 'fullscreen',
    'show-guide', 'run-macro', 'start-guide', 'guide-previous', 'guide-next', 'stop-guide', 'reset-checkpoint', 'run-cue']) {
    document.getElementById(action).addEventListener('click', () => bridge.action(action));
  }

  function selectChoice(index, scroll = false) {
    choice = index;
    const selected = rows.find(row => row.index === choice);
    rows.forEach(row => row.element.classList.toggle('search-choice', !!search.value.trim() && row.index === choice));
    if (selected && search.value.trim()) search.setAttribute('aria-activedescendant', selected.element.id);
    else search.removeAttribute('aria-activedescendant');
    if (scroll) selected?.element.scrollIntoView({block: 'nearest'});
  }

  function filterSlides(resetChoice = false) {
    const query = search.value.trim();
    const terms = textSearch.terms(query);
    if (query && !savedOpen) savedOpen = new Map(groups.map(group => [group.key, group.element.open]));
    for (const row of rows) {
      row.element.hidden = !textSearch.matches(row.search, terms);
      row.excerpt.textContent = query && !row.element.hidden ? textSearch.excerpt(row.body, terms) : '';
      row.excerpt.hidden = !row.excerpt.textContent;
    }
    for (const group of [...groups].reverse()) {
      group.element.hidden = !group.rows.some(row => !row.element.hidden);
      if (query) group.element.open = true;
      else if (savedOpen) group.element.open = savedOpen.get(group.key) ?? false;
    }
    if (!query) savedOpen = undefined;
    filtered = rows.filter(row => !row.element.hidden);
    selectChoice(!resetChoice && filtered.some(row => row.index === choice) ? choice :
      (!query && filtered.some(row => row.index === info?.index) ? info.index : filtered[0]?.index ?? -1));
    matches.textContent = query ? `${filtered.length} of ${rows.length} slides` : `${rows.length} slides`;
  }

  function buildOutline() {
    const open = new Map(groups.map(group => [group.key, savedOpen?.get(group.key) ?? group.element.open]));
    slides.replaceChildren();
    groups = [];
    rows = [];
    savedOpen = undefined;
    // Only contiguous sections share a heading, so interleaved/root steps retain source order.
    let lastPath = [];
    let branch = [];
    info.slides.forEach((id, index) => {
      const path = info.sectionPaths?.[index] || [];
      let shared = 0;
      while (shared < path.length && path[shared] === lastPath[shared]) shared++;
      branch = branch.slice(0, shared);
      for (let depth = shared; depth < path.length; depth++) {
        const element = document.createElement('details');
        const summary = document.createElement('summary');
        const label = document.createElement('span');
        label.textContent = path[depth];
        summary.append(label);
        const range = document.createElement('span');
        range.className = 'section-range';
        summary.append(range);
        element.append(summary);
        const key = JSON.stringify([index, path.slice(0, depth + 1)]);
        element.open = open.get(key) ?? false;
        const group = {key, element, summary, range, rows: []};
        (branch.at(-1)?.element || slides).append(element);
        groups.push(group);
        branch.push(group);
      }
      const element = document.createElement('button');
      element.className = 'slide';
      element.id = `slide-result-${index}`;
      element.dataset.slideTitle = info.titles[index];
      const number = document.createElement('span');
      number.className = 'slide-number';
      number.textContent = String(index + 1).padStart(String(info.count).length, '0');
      const label = document.createElement('span');
      label.textContent = info.titles[index];
      const description = document.createElement('span');
      description.className = 'slide-description';
      const excerpt = document.createElement('span');
      excerpt.className = 'slide-excerpt';
      excerpt.hidden = true;
      description.append(label, excerpt);
      element.append(number, description);
      element.addEventListener('click', () => move('goto', id));
      (branch.at(-1)?.element || slides).append(element);
      const body = info.searchTexts?.[index] || '';
      const row = {element, index, excerpt, body, ancestors: [...branch], search: textSearch.fold(`${index + 1} ${path.join(' ')} ${info.titles[index]} ${body}`)};
      rows.push(row);
      branch.forEach(group => group.rows.push(row));
      lastPath = path;
    });
    groups.forEach(group => {
      group.range.textContent = `${group.rows[0].index + 1}–${group.rows.at(-1).index + 1}`;
    });
  }

  search.addEventListener('input', () => filterSlides(true));
  search.addEventListener('keydown', event => {
    if (event.isComposing) return;
    if (['Escape', 'Enter', 'ArrowDown', 'ArrowUp'].includes(event.key)) { event.preventDefault(); event.stopPropagation(); }
    if (event.key === 'Escape') {
      search.value = ''; filterSlides(true);
      const current = rows[info?.index];
      current?.ancestors.forEach(group => { group.element.open = true; });
      current?.element.focus();
    }
    if (['ArrowDown', 'ArrowUp'].includes(event.key) && filtered.length) {
      const index = filtered.findIndex(row => row.index === choice);
      const delta = event.key === 'ArrowDown' ? 1 : -1;
      selectChoice(filtered[(index + delta + filtered.length) % filtered.length].index, true);
    }
    if (event.key === 'Enter') {
      const row = filtered.find(row => row.index === choice);
      if (row) row.element.click();
    }
  });
  bridge.onState(state => {
    info = state;
    document.getElementById('title').textContent = info.title;
    const cueIndex = info.cueIndex || 0;
    const cues = info.cueTitles || [];
    document.getElementById('status').textContent = `${info.index + 1} / ${info.count} · ${info.titles[info.index]}` +
      (cues.length ? ` · cue ${cueIndex} / ${cues.length}${cueIndex ? ': ' + cues[cueIndex - 1] : ''}` : '');
    document.getElementById('previous').disabled = info.index === 0 && cueIndex === 0;
    document.getElementById('next').disabled = info.index === info.count - 1 && cueIndex === cues.length;
    document.getElementById('lesson-actions').hidden = !info.checkpoint && !info.cueAction;
    document.getElementById('run-cue').hidden = !info.cueAction;
    const reset = document.getElementById('reset-checkpoint');
    reset.hidden = !info.checkpoint;
    reset.textContent = info.checkpoint ? `Reset: ${info.checkpoint}` : 'Reset experiment';
    document.getElementById('field-actions').hidden = !info.field?.guide;
    document.getElementById('interactive-guide').hidden = !info.field?.interactiveGuide;
    const macro = document.getElementById('run-macro');
    macro.disabled = !info.field?.macro;
    macro.textContent = info.field?.macro ? `Run ${info.field.macro.name}` : 'Run cue macro';
    const nextSignature = JSON.stringify([info.slides, info.titles, info.sectionPaths, info.searchTexts]);
    const rebuilt = signature !== nextSignature;
    if (rebuilt) { buildOutline(); signature = nextSignature; }
    rows.forEach(row => {
      row.element.classList.toggle('active', row.index === info.index);
      if (row.index === info.index) row.element.setAttribute('aria-current', 'step');
      else row.element.removeAttribute('aria-current');
    });
    groups.forEach(group => group.summary.classList.toggle('contains-current', group.rows.some(row => row.index === info.index)));
    if (rebuilt || previousIndex !== info.index) {
      rows[info.index]?.ancestors.forEach(group => { group.element.open = true; });
      if (savedOpen) rows[info.index]?.ancestors.forEach(group => savedOpen.set(group.key, true));
    }
    filterSlides();
    if (!search.value && (rebuilt || previousIndex !== info.index)) rows[info.index]?.element.scrollIntoView({block: 'nearest'});
    previousIndex = info.index;
    error.textContent = '';
    renderLink();
    if (pending && info.index === pending.index && (pending.cueIndex == null || cueIndex === pending.cueIndex)) clearPending();
    else if (pending && !pending.failed) {
      // A step landed on the way to a retargeted one: keep waiting for the rest.
      clearTimeout(pending.timer);
      pending.timer = setTimeout(failPending, PENDING_FAIL_MS);
      renderPending();
    } else if (pending) clearPending();
  });
  // Electron reports every move, including ones from the audience window's keys and clickers.
  bridge.onPending?.(next => {
    if (next.action === 'next' || next.action === 'previous') pressBriefly(document.getElementById(next.action));
    if (!pending || pending.index !== next.index || pending.cueIndex !== next.cueIndex || pending.failed) {
      showPending({index: next.index, cueIndex: next.cueIndex}, next.action);
    }
  });
  bridge.onError(message => { error.textContent = message; failPending(); });
  addEventListener('keydown', event => {
    if (event.isComposing || event.defaultPrevented) return;
    const editing = event.target.closest('input, textarea, [contenteditable="true"]');
    if (((event.ctrlKey || event.metaKey) && !event.altKey && event.key.toLowerCase() === 'f') ||
        (event.key === '/' && !editing && !event.ctrlKey && !event.metaKey && !event.altKey)) {
      event.preventDefault(); search.focus(); search.select(); filterSlides(); return;
    }
    if (event.key === 'Escape' && link && !editing) { event.preventDefault(); bridge.action('close-link'); return; }
    // Search and the native disclosure controls keep their own editing/navigation keys.
    if (event.target.closest('input, textarea, summary, [contenteditable="true"]')) return;
    if (event.target instanceof HTMLButtonElement && [' ', 'Enter'].includes(event.key)) return;
    const action = ['ArrowRight', 'PageDown', ' '].includes(event.key) ? 'next' :
      ['ArrowLeft', 'PageUp'].includes(event.key) ? 'previous' : null;
    if (action) {
      event.preventDefault();
      pressBriefly(document.getElementById(action));
      move(action);
    }
  });
})();
