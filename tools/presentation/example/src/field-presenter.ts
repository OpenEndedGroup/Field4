import {Presenter, Vector2} from '@motion-canvas/core';
import project from './project.ts?project';

const presentation = (window as any).fieldPresentation;
const errorEl = document.getElementById('error')!;
function showError(error: unknown) {
  errorEl.style.display = 'block';
  errorEl.textContent = String(error instanceof Error ? error.stack || error.message : error);
  presentation?.error(errorEl.textContent);
}

let presenter: Presenter | null = null;
let currentId: string | null = null;
let slideIds: string[] = [];
const savedCue = sessionStorage.getItem('field-presenter-cue');
const retriedStartup = sessionStorage.getItem('field-presenter-start-retry') === '1';
function reloadAtCurrentCue() {
  if (currentId) sessionStorage.setItem('field-presenter-cue', currentId);
  location.reload();
}
async function start(slide: string | null = null) {
  presenter?.abort();
  presenter = new Presenter(project);
  document.getElementById('stage')!.replaceChildren(presenter.stage.finalBuffer);
  presenter.onSlidesChanged.subscribe(slides => { slideIds = slides.map(slide => slide.id); });
  presenter.onInfoChanged.subscribe(info => {
    currentId = info.currentSlideId;
    if (currentId) {
      sessionStorage.setItem('field-presenter-cue', currentId);
      sessionStorage.removeItem('field-presenter-start-retry');
      errorEl.textContent = '';
      errorEl.style.display = 'none';
    }
    presentation?.state({...info, slides: slideIds});
  });
  try {
    await presenter.present({
      name: project.name,
      fps: 30,
      slide,
      size: new Vector2(1920, 1080),
      resolutionScale: 1,
      colorSpace: 'srgb',
      background: '#050505',
    });
  } catch (error) { showError(error); }
}

// A first Vite dependency scan can reload modules while the presenter is starting. Recover
// once if that leaves the initial animation waiting indefinitely, then surface a real error.
setTimeout(() => {
  if (currentId || errorEl.textContent) return;
  if (retriedStartup) showError('The first presentation cue did not start. Reload the source after checking the scene.');
  else {
    sessionStorage.setItem('field-presenter-start-retry', '1');
    reloadAtCurrentCue();
  }
}, retriedStartup ? 30000 : 15000);

presentation?.onAction((action: string, id?: string) => {
  if (action === 'next') presenter?.requestNextSlide();
  else if (action === 'previous') presenter?.requestPreviousSlide();
  else if (action === 'first') presenter?.requestFirstSlide();
  else if (action === 'last') presenter?.requestLastSlide();
  else if (action === 'goto' && id) presenter?.requestSlide(id);
  else if (action === 'reload') reloadAtCurrentCue();
});
window.addEventListener('keydown', event => {
  if (['ArrowRight', 'PageDown', ' '].includes(event.key)) { event.preventDefault(); presenter?.requestNextSlide(); }
  if (['ArrowLeft', 'PageUp'].includes(event.key)) { event.preventDefault(); presenter?.requestPreviousSlide(); }
});
window.addEventListener('error', event => showError(event.error || event.message));
window.addEventListener('unhandledrejection', event => showError(event.reason));
import.meta.hot?.on('vite:beforeUpdate', payload => {
  if (payload.updates.some(update => /\/src\/(?:project\.ts|scenes\/[^/]+\.tsx)/.test(update.path))) {
    setTimeout(reloadAtCurrentCue, 200);
  }
});
void start(savedCue);
