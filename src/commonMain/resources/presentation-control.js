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

  for (const action of ['previous', 'next', 'reload', 'show-audience', 'fullscreen',
    'show-guide', 'run-macro', 'start-guide', 'guide-previous', 'guide-next', 'stop-guide', 'reset-checkpoint', 'run-cue']) {
    document.getElementById(action).addEventListener('click', () => bridge.action(action));
  }

  function filterSlides() {
    const query = search.value.trim().toLocaleLowerCase();
    if (query && !savedOpen) savedOpen = new Map(groups.map(group => [group.key, group.element.open]));
    let count = 0;
    for (const row of rows) {
      row.element.hidden = !!query && !row.search.includes(query);
      if (!row.element.hidden) count++;
    }
    for (const group of [...groups].reverse()) {
      group.element.hidden = !group.rows.some(row => !row.element.hidden);
      if (query) group.element.open = true;
      else if (savedOpen) group.element.open = savedOpen.get(group.key) ?? false;
    }
    if (!query) savedOpen = undefined;
    matches.textContent = query ? `${count} of ${rows.length} slides` : `${rows.length} slides`;
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
      element.dataset.slideTitle = info.titles[index];
      const number = document.createElement('span');
      number.className = 'slide-number';
      number.textContent = String(index + 1).padStart(String(info.count).length, '0');
      const label = document.createElement('span');
      label.textContent = info.titles[index];
      element.append(number, label);
      element.addEventListener('click', () => bridge.action('goto', id));
      (branch.at(-1)?.element || slides).append(element);
      const row = {element, index, ancestors: [...branch], search: `${index + 1} ${path.join(' ')} ${info.titles[index]}`.toLocaleLowerCase()};
      rows.push(row);
      branch.forEach(group => group.rows.push(row));
      lastPath = path;
    });
    groups.forEach(group => {
      group.range.textContent = `${group.rows[0].index + 1}–${group.rows.at(-1).index + 1}`;
    });
  }

  search.addEventListener('input', filterSlides);
  search.addEventListener('keydown', event => {
    if (event.key === 'Escape') { search.value = ''; filterSlides(); }
    if (event.key === 'Enter') {
      const row = rows.find(row => !row.element.hidden);
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
    const nextSignature = JSON.stringify([info.slides, info.titles, info.sectionPaths]);
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
  });
  bridge.onError(message => { error.textContent = message; });
  addEventListener('keydown', event => {
    // Search and the native disclosure controls keep their own editing/navigation keys.
    if (event.target.closest('input, textarea, summary, [contenteditable="true"]')) return;
    if (event.target instanceof HTMLButtonElement && [' ', 'Enter'].includes(event.key)) return;
    const action = ['ArrowRight', 'PageDown', ' '].includes(event.key) ? 'next' :
      ['ArrowLeft', 'PageUp'].includes(event.key) ? 'previous' : null;
    if (action) { event.preventDefault(); bridge.action(action); }
  });
})();
