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

  for (const action of ['previous', 'next', 'reload', 'show-audience', 'fullscreen',
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
      element.addEventListener('click', () => bridge.action('goto', id));
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
  });
  bridge.onError(message => { error.textContent = message; });
  addEventListener('keydown', event => {
    if (event.isComposing || event.defaultPrevented) return;
    const editing = event.target.closest('input, textarea, [contenteditable="true"]');
    if (((event.ctrlKey || event.metaKey) && !event.altKey && event.key.toLowerCase() === 'f') ||
        (event.key === '/' && !editing && !event.ctrlKey && !event.metaKey && !event.altKey)) {
      event.preventDefault(); search.focus(); search.select(); filterSlides(); return;
    }
    // Search and the native disclosure controls keep their own editing/navigation keys.
    if (event.target.closest('input, textarea, summary, [contenteditable="true"]')) return;
    if (event.target instanceof HTMLButtonElement && [' ', 'Enter'].includes(event.key)) return;
    const action = ['ArrowRight', 'PageDown', ' '].includes(event.key) ? 'next' :
      ['ArrowLeft', 'PageUp'].includes(event.key) ? 'previous' : null;
    if (action) { event.preventDefault(); bridge.action(action); }
  });
})();
