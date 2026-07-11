/* ── Homepage full-bleed video background ─────────────────────
   Restores the front-page video loop lost in the Zensical port.

   In the mkdocs-material build this markup (and the
   `data-field-homepage` flag that dot-grid.js reads) was injected
   by `overrides/main.html`. Zensical does not honour that per-page
   template override, so we reproduce it here via `extra_javascript`.

   MUST be listed before dot-grid.js in `extra_javascript`: the grid
   reads `data-field-homepage` synchronously and bows out on the
   homepage, so the flag has to be set first.                       */

;(function () {
  "use strict";

  var script = document.currentScript;
  if (!script) return;

  /* The assets/ directory resolves to the same absolute URL on every
     page (…/field4/assets/…), so derive the site root and the banner
     location from our own <script src> — no hard-coded base path. */
  var assetsBase = new URL(".", script.src);          // …/field4/assets/
  var siteBase   = new URL("..", assetsBase);         // …/field4/
  var bannerUrl  = new URL("banner.mp4", assetsBase); // …/field4/assets/banner.mp4

  function normalize(pathname) {
    return pathname.replace(/index\.html$/, "").replace(/\/*$/, "/");
  }

  /* Homepage == the page whose path is the site root itself. */
  if (normalize(location.pathname) !== normalize(siteBase.pathname)) return;

  /* Flag the homepage so the dot-grid background bows out. */
  document.documentElement.setAttribute("data-field-homepage", "true");

  function inject() {
    if (document.querySelector(".field-homepage-video")) return;

    var wrap = document.createElement("div");
    wrap.className = "field-homepage-video";
    wrap.setAttribute("aria-hidden", "true");

    var video = document.createElement("video");
    video.autoplay = true;
    video.loop = true;
    video.playsInline = true;
    video.preload = "auto";
    /* The `muted` property does NOT reflect to the content attribute, and
       browsers gate muted-autoplay on the attribute being present — set
       both so the loop autoplays without a user gesture (as it does in
       the server-rendered production markup). */
    video.muted = true;
    video.defaultMuted = true;
    video.setAttribute("muted", "");

    var source = document.createElement("source");
    source.src = bannerUrl.href;
    source.type = "video/mp4";
    video.appendChild(source);

    var veil = document.createElement("div");
    veil.className = "field-homepage-video__veil";

    wrap.appendChild(video);
    wrap.appendChild(veil);
    document.body.appendChild(wrap);

    document.documentElement.setAttribute("data-field-homepage-loaded", "true");
    wrap.animate(
      [{ opacity: 0 }, { opacity: 1 }],
      { duration: 2900, easing: "ease", fill: "forwards" }
    );

    /* Some browsers ignore the `autoplay` attribute even for a muted
       loop, and won't re-fire load events for an already-cached video,
       so nudge playback explicitly: attempt now, retry briefly while it
       stays paused, and fall back to the first user interaction. Errors
       are non-fatal — the current frame still shows behind the veil. */
    var tries = 0;
    function play() {
      if (!video.paused) return;
      var p = video.play();
      if (p && typeof p.catch === "function") p.catch(function () {});
      if (video.paused && ++tries < 20) setTimeout(play, 150);
    }
    play();

    var events = ["pointerdown", "keydown", "touchstart"];
    function onInteract() {
      play();
      events.forEach(function (e) { document.removeEventListener(e, onInteract); });
    }
    events.forEach(function (e) {
      document.addEventListener(e, onInteract, { once: false, passive: true });
    });
  }

  if (document.body) inject();
  else document.addEventListener("DOMContentLoaded", inject, { once: true });
})();
