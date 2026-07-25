/* ── Header brand weights ─────────────────────────────────────
   `site_name` ("OpenEndedGroup | Field·4") reaches the header as a
   single text node, so CSS alone can't weight its two halves apart.
   Split it into spans and let extra.css set "OpenEndedGroup |" in the
   lightest weight we load, leaving "Field·4" at the header's own weight.

   In the Material build this would have been an `overrides/main.html`
   block; Zensical does not honour that, hence `extra_javascript`.    */

;(function () {
  "use strict";

  var SEPARATOR = "|";

  /* The first `.md-header__topic` is the site name; the second is the
     page title, which the theme cross-fades in on scroll. */
  var TOPIC = ".md-header__title .md-header__topic:first-child .md-ellipsis";

  function decorate() {
    var host = document.querySelector(TOPIC);
    if (!host || host.querySelector(".field-brand__org")) return;

    var text = host.textContent.trim();
    var cut = text.indexOf(SEPARATOR);
    if (cut < 0) return;                     // site_name isn't the two-part form

    var org = document.createElement("span");
    org.className = "field-brand__org";
    org.textContent = text.slice(0, cut + SEPARATOR.length);

    var product = document.createElement("span");
    product.className = "field-brand__product";
    product.textContent = text.slice(cut + SEPARATOR.length).trim();

    host.textContent = "";
    host.appendChild(org);
    host.appendChild(document.createTextNode(" "));
    host.appendChild(product);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", decorate);
  } else {
    decorate();
  }

  /* Re-apply if instant navigation ever swaps the header in place. */
  if (window.document$ && typeof window.document$.subscribe === "function") {
    window.document$.subscribe(decorate);
  }
})();
