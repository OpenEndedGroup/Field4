# Embedded Offline Viewer

This page embeds the offline viewer inside an iframe so we can see how it sits within the docs layout (for testing).

<iframe
  src="/offline.html?file=/recoverGraphics_importme.edn"
  title="Offline Viewer"
  style="width:100%; height:720px; border:1px solid #444; border-radius:8px; background:#111;"
  loading="lazy"
  allowfullscreen>
</iframe>

The iframe points at `offline.html` and preloads the sample EDN file `recoverGraphics_importme.edn` from `public/`.
