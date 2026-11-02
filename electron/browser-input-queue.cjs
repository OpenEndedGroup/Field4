// Chromium acknowledges continuous pointer input at its paint rate. Keep one batch waiting
// behind the in-flight batch, rather than letting a 120 Hz controller queue seconds of input.
function createBrowserInputQueue(dispatch, onError) {
  let tail = Promise.resolve();
  let pending = null;
  function append(work) {
    tail = tail.then(work).catch(onError);
  }
  function queue(method, params) {
    const continuous = method === "Input.dispatchMouseEvent" &&
      (params.type === "mouseMoved" || params.type === "mouseWheel");
    if (!continuous) {
      pending = null; // Seal the preceding batch: keys/buttons must remain ordered barriers.
      append(() => dispatch(method, params));
      return;
    }
    if (pending && (pending.modifiers !== params.modifiers || pending.buttons !== params.buttons)) {
      pending = null;
    }
    if (!pending) {
      const batch = { modifiers: params.modifiers, buttons: params.buttons, move: null, wheel: null };
      pending = batch;
      append(async () => {
        if (pending === batch) pending = null;
        // Snapshot before awaiting: arrivals during dispatch belong to the next batch.
        const { move, wheel } = batch;
        if (move) await dispatch(method, move);
        if (wheel) await dispatch(method, wheel);
      });
    }
    if (params.type === "mouseMoved") pending.move = params;
    else {
      const previous = pending.wheel;
      // One continuous gesture follows its latest position, retaining its total displacement.
      pending.wheel = { ...params,
        deltaX: (previous?.deltaX || 0) + (params.deltaX || 0),
        deltaY: (previous?.deltaY || 0) + (params.deltaY || 0) };
    }
  }
  function flush() {
    pending = null;
    return tail;
  }
  return { queue, flush };
}

module.exports = { createBrowserInputQueue };
