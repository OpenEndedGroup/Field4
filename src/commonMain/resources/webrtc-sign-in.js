// The "Sign in to aaaframe" control in Field's WebRTC panels (dev_docs/WEBRTC_SOURCES_SINKS.md).
//
// Electron main opens aaaframe's own login page in a Field window and reads the HttpOnly session
// cookie from that window's cookie jar (electron/aaaframe-sign-in.js); this module asks for it and
// hands the result to Field, which keeps it for this user and reconnects. Only Field-page panels
// import this: it needs afield.mjs and the Electron preload.
import { send } from "/afield.mjs";

// One automatic prompt per server per Field window; after that the button waits for a click.
const prompted = (window.__fieldAaaframePrompted ||= new Set());

export function signInControl(container, base, { auto = true } = {}) {
  container.replaceChildren();
  const electron = window.fieldElectron && window.fieldElectron.aaaframeSignIn;
  if (!electron) {
    const note = document.createElement("span");
    note.textContent = "signing in to aaaframe needs the Field Electron app";
    note.style.color = "#e88";
    container.append(note);
    return;
  }
  const button = document.createElement("button");
  button.textContent = "Sign in to aaaframe";
  button.style.cssText = "font:inherit;padding:1px 6px;border-radius:3px;border:1px solid #c96;cursor:pointer;background:transparent;color:inherit";
  const note = document.createElement("span");
  note.style.opacity = ".7";
  const run = async (force) => {
    button.disabled = true;
    note.textContent = " a sign-in window is open";
    const result = await window.fieldElectron.aaaframeSignIn(base, force).catch((e) => ({ ok: false, error: String(e) }));
    button.disabled = false;
    if (result.ok) {
      send("webrtc-aaaframe-signed-in", { network: base, session: result.session, login: result.login });
      note.textContent = " signed in as @" + result.login + "; reconnecting";
    } else {
      note.textContent = " " + result.error;
    }
  };
  // A refused login with a remembered cookie means it expired: the click forces a fresh sign-in.
  button.onclick = () => run(true);
  container.append(button, note);
  if (auto && !prompted.has(base)) {
    prompted.add(base);
    run(false);
  }
}
