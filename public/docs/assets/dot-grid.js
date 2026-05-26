/* ── Dot-grid graph-paper background ──────────────────────────
   Infinite tiling dot pattern (every 5th dot larger in both axes).
   Fades in while scrolling, fades to barely-visible when idle.
   Hidden on the homepage (data-field-homepage="true").               */

;(function () {
  "use strict";

  /* ── tuning ──────────────────────────────────────────────── */
  var SPACING    = 28;        // px between dots
  var TILE       = SPACING*5; // one repeat unit
  var DOT_R      = 0.8;       // small dot radius
  var BIG_R      = 2.4;       // every-5th dot radius
  var DOT_ALPHA  = 0.35;      // dot fill opacity (within the tile)
  var BIG_ALPHA  = 0.60;
  var IDLE_OP    = 0.12;      // overall layer opacity at rest
  var SCROLL_OP  = 0.45;      // overall layer opacity while scrolling
  var FADE_IN    = 120;       // ms to reach scroll opacity
  var FADE_OUT   = 900;       // ms to settle back to idle

  /* ── bail on homepage ───────────────────────────────────── */
  if (document.documentElement.getAttribute("data-field-homepage") === "true") return;

  /* ── generate tile on a tiny canvas ─────────────────────── */
  var can = document.createElement("canvas");
  can.width = TILE;  can.height = TILE;
  var ctx = can.getContext("2d");

  for (var gy = 0; gy < 5; gy++) {
    for (var gx = 0; gx < 5; gx++) {
      var big = (gx === 0 && gy === 0);
      ctx.beginPath();
      ctx.arc(gx * SPACING, gy * SPACING, big ? BIG_R : DOT_R, 0, Math.PI * 2);
      ctx.fillStyle = "rgba(255,255,255," + (big ? BIG_ALPHA : DOT_ALPHA) + ")";
      ctx.fill();
    }
  }

  /* ── fixed overlay element ──────────────────────────────── */
  var el = document.createElement("div");
  el.className = "field-dot-grid";
  el.style.cssText =
    "position:fixed;inset:0;z-index:-1;pointer-events:none;" +
    "background:url(" + can.toDataURL() + ") repeat;" +
    "opacity:" + IDLE_OP + ";" +
    "transition:opacity " + FADE_OUT + "ms ease-out;";
  document.body.appendChild(el);

  /* ── scroll reactivity ──────────────────────────────────── */
  var timer = 0;
  window.addEventListener("scroll", function () {
    /* move dots with page content */
    el.style.backgroundPositionY = -window.scrollY + "px";

    el.style.transition = "opacity " + FADE_IN + "ms ease-out";
    el.style.opacity    = SCROLL_OP;
    clearTimeout(timer);
    timer = setTimeout(function () {
      el.style.transition = "opacity " + FADE_OUT + "ms ease-out";
      el.style.opacity    = IDLE_OP;
    }, 200);
  }, { passive: true });
})();
