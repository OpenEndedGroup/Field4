(() => {
  const bridge = window.fieldPresentation;
  const error = document.getElementById('error');
  for (const action of ['previous', 'next', 'reload', 'show-audience', 'fullscreen',
    'show-guide', 'run-macro', 'start-guide', 'guide-previous', 'guide-next', 'stop-guide']) {
    document.getElementById(action).addEventListener('click', () => bridge.action(action));
  }
  bridge.onState(info => {
    document.getElementById('title').textContent = info.title;
    document.getElementById('status').textContent = `${info.index + 1} / ${info.count} · ${info.titles[info.index]}`;
    document.getElementById('previous').disabled = info.index === 0;
    document.getElementById('next').disabled = info.index === info.count - 1;
    document.getElementById('field-actions').hidden = !info.field?.guide;
    document.getElementById('interactive-guide').hidden = !info.field?.interactiveGuide;
    const macro = document.getElementById('run-macro');
    macro.disabled = !info.field?.macro;
    macro.textContent = info.field?.macro ? `Run ${info.field.macro.name}` : 'Run cue macro';
    const slides = document.getElementById('slides');
    slides.replaceChildren();
    info.slides.forEach((id, index) => {
      const button = document.createElement('button');
      button.textContent = info.titles[index];
      button.classList.toggle('active', index === info.index);
      button.addEventListener('click', () => bridge.action('goto', id));
      slides.appendChild(button);
    });
    error.textContent = '';
  });
  bridge.onError(message => { error.textContent = message; });
  addEventListener('keydown', event => {
    if (event.target instanceof HTMLButtonElement && [' ', 'Enter'].includes(event.key)) return;
    const action = ['ArrowRight', 'PageDown', ' '].includes(event.key) ? 'next' :
      ['ArrowLeft', 'PageUp'].includes(event.key) ? 'previous' : null;
    if (action) { event.preventDefault(); bridge.action(action); }
  });
})();
