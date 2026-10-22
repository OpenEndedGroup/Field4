const bridge = (window as any).fieldPresentation;
const slides = document.getElementById('slides')!;
const statusEl = document.getElementById('status')!;
const error = document.getElementById('error')!;
document.getElementById('previous')!.addEventListener('click', () => bridge.action('previous'));
document.getElementById('next')!.addEventListener('click', () => bridge.action('next'));
document.getElementById('reload')!.addEventListener('click', () => bridge.action('reload'));
document.getElementById('fullscreen')!.addEventListener('click', () => bridge.action('fullscreen'));
document.getElementById('show-audience')!.addEventListener('click', () => bridge.action('show-audience'));
document.getElementById('attach-sources')!.addEventListener('click', () => bridge.action('attach-sources'));
document.getElementById('show-guide')!.addEventListener('click', () => bridge.action('show-guide'));
document.getElementById('run-macro')!.addEventListener('click', () => bridge.action('run-macro'));
for (const action of ['start-guide', 'guide-next', 'guide-previous', 'stop-guide']) {
  document.getElementById(action)!.addEventListener('click', () => bridge.action(action));
}
bridge.onState((info: any) => {
  statusEl.textContent = `${info.index == null ? '—' : info.index + 1} / ${info.count} · ${info.currentSlideId ?? 'loading'}`;
  if (info.currentSlideId) error.textContent = '';
  (document.getElementById('show-guide') as HTMLButtonElement).disabled = !info.field?.guide;
  const macroButton = document.getElementById('run-macro') as HTMLButtonElement;
  macroButton.disabled = !info.field?.macro;
  macroButton.textContent = info.field?.macro ? `Run ${info.field.macro.name}` : 'Run cue macro';
  document.getElementById('interactive-guide')!.hidden = !info.field?.interactiveGuide;
  slides.replaceChildren();
  for (const cue of info.slides ?? []) {
    const button = document.createElement('button');
    button.textContent = cue;
    if (cue === info.currentSlideId) button.classList.add('active');
    button.addEventListener('click', () => bridge.action('goto', cue));
    slides.appendChild(button);
  }
});
bridge.onError((message: string) => error.textContent = message);
window.addEventListener('keydown', event => {
  if (event.key === 'ArrowRight' || event.key === 'PageDown') bridge.action('next');
  if (event.key === 'ArrowLeft' || event.key === 'PageUp') bridge.action('previous');
});
