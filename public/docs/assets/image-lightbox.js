(function() {
  const ROOT_SELECTOR = ".md-content .md-typeset";
  const LIGHTBOX_ATTR = "data-field-docs-image-lightbox";
  const ENHANCED_ATTR = "data-field-docs-image-enhanced";
  const SKIP_ATTR = "data-no-lightbox";
  const NATIVE_SIZED_CLASS = "field-docs-image-native-sized";
  const SMALL_IMAGE_MAX_WIDTH = 560;
  let lightbox = null;
  let overlay = null;
  let image = null;
  let caption = null;
  let lastFocusedElement = null;

  function ensureLightbox() {
    if (lightbox) return lightbox;

    lightbox = document.createElement("div");
    lightbox.className = "field-docs-image-lightbox";
    lightbox.setAttribute(LIGHTBOX_ATTR, "true");
    lightbox.setAttribute("aria-hidden", "true");

    overlay = document.createElement("button");
    overlay.className = "field-docs-image-lightbox__overlay";
    overlay.type = "button";
    overlay.setAttribute("aria-label", "Close enlarged image");

    const frame = document.createElement("div");
    frame.className = "field-docs-image-lightbox__frame";
    frame.setAttribute("role", "dialog");
    frame.setAttribute("aria-modal", "true");
    frame.setAttribute("aria-label", "Enlarged documentation image");
    frame.tabIndex = -1;

    const closeButton = document.createElement("button");
    closeButton.className = "field-docs-image-lightbox__close";
    closeButton.type = "button";
    closeButton.setAttribute("aria-label", "Close enlarged image");
    closeButton.textContent = "Close";

    image = document.createElement("img");
    image.className = "field-docs-image-lightbox__image";
    image.alt = "";

    caption = document.createElement("p");
    caption.className = "field-docs-image-lightbox__caption";
    caption.hidden = true;

    frame.addEventListener("click", (event) => {
      event.stopPropagation();
    });

    overlay.addEventListener("click", closeLightbox);
    closeButton.addEventListener("click", closeLightbox);

    frame.appendChild(closeButton);
    frame.appendChild(image);
    frame.appendChild(caption);

    lightbox.appendChild(overlay);
    lightbox.appendChild(frame);
    document.body.appendChild(lightbox);

    return lightbox;
  }

  function isEnhanceableImage(node) {
    if (!(node instanceof HTMLImageElement)) return false;
    if (node.closest("[" + LIGHTBOX_ATTR + "]")) return false;
    if (node.closest("[" + SKIP_ATTR + "]")) return false;
    if (!node.closest(ROOT_SELECTOR)) return false;
    if (!node.currentSrc && !node.getAttribute("src")) return false;
    return true;
  }

  function describeImage(node) {
    const alt = (node.getAttribute("alt") || "").trim();
    const title = (node.getAttribute("title") || "").trim();
    const figure = node.closest("figure");
    const figureCaption = figure ? figure.querySelector("figcaption") : null;
    const text = (figureCaption ? figureCaption.textContent : alt || title || "").trim();
    return {
      alt: alt || title || "Documentation image",
      caption: text,
    };
  }

  function openLightboxFromImage(node) {
    const host = ensureLightbox();
    const info = describeImage(node);
    const source = node.currentSrc || node.src || node.getAttribute("src");

    if (!source) return;

    lastFocusedElement = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    image.src = source;
    image.alt = info.alt;
    caption.textContent = info.caption;
    caption.hidden = !info.caption;

    host.classList.add("is-open");
    host.setAttribute("aria-hidden", "false");
    document.body.classList.add("field-docs-image-lightbox-open");

    const frame = host.querySelector(".field-docs-image-lightbox__frame");
    if (frame instanceof HTMLElement) {
      frame.focus({ preventScroll: true });
    }
  }

  function closeLightbox() {
    if (!lightbox || !lightbox.classList.contains("is-open")) return;
    lightbox.classList.remove("is-open");
    lightbox.setAttribute("aria-hidden", "true");
    document.body.classList.remove("field-docs-image-lightbox-open");
    image.removeAttribute("src");
    caption.textContent = "";
    caption.hidden = true;
    if (lastFocusedElement) {
      lastFocusedElement.focus({ preventScroll: true });
      lastFocusedElement = null;
    }
  }

  function syncImageDisplaySize(node) {
    if (!(node instanceof HTMLImageElement)) return;
    if (node.hasAttribute("width")) return;
    if (!node.complete || !node.naturalWidth) return;

    const deviceScale = Math.max(window.devicePixelRatio || 1, 1);
    const preferredWidth = Math.round(node.naturalWidth / deviceScale);

    if (preferredWidth <= 0 || preferredWidth > SMALL_IMAGE_MAX_WIDTH) {
      node.classList.remove(NATIVE_SIZED_CLASS);
      node.style.removeProperty("--field-docs-native-max-width");
      return;
    }

    node.classList.add(NATIVE_SIZED_CLASS);
    node.style.setProperty("--field-docs-native-max-width", preferredWidth + "px");
  }

  function enhanceImage(node) {
    if (!isEnhanceableImage(node) || node.dataset.fieldDocsImageEnhanced === "true") return;

    node.dataset.fieldDocsImageEnhanced = "true";
    if (!node.hasAttribute("tabindex")) {
      node.tabIndex = 0;
    }
    if (!node.hasAttribute("role")) {
      node.setAttribute("role", "button");
    }
    if (!node.hasAttribute("aria-label")) {
      node.setAttribute("aria-label", "Open image full size");
    }
    node.classList.add("field-docs-image-zoomable");

    node.addEventListener("click", (event) => {
      const anchor = node.closest("a[href]");
      if (anchor && anchor.getAttribute("href") && anchor.getAttribute("href") !== node.getAttribute("src")) {
        return;
      }
      event.preventDefault();
      openLightboxFromImage(node);
    });

    node.addEventListener("keydown", (event) => {
      if (event.key !== "Enter" && event.key !== " ") return;
      event.preventDefault();
      openLightboxFromImage(node);
    });

    if (node.complete) {
      syncImageDisplaySize(node);
    } else {
      node.addEventListener("load", () => syncImageDisplaySize(node), { once: true });
    }
  }

  function mountAll() {
    const roots = Array.from(document.querySelectorAll(ROOT_SELECTOR));
    for (const root of roots) {
      const images = root.querySelectorAll("img");
      images.forEach((node) => enhanceImage(node));
    }
  }

  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") {
      closeLightbox();
    }
  });

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", () => setTimeout(mountAll, 0), { once: true });
  } else {
    setTimeout(mountAll, 0);
  }

  window.addEventListener("resize", () => {
    const roots = Array.from(document.querySelectorAll(ROOT_SELECTOR));
    for (const root of roots) {
      const images = root.querySelectorAll("img");
      images.forEach((node) => syncImageDisplaySize(node));
    }
  }, { passive: true });

  if (window.document$ && typeof window.document$.subscribe === "function") {
    window.document$.subscribe(() => {
      setTimeout(mountAll, 0);
    });
  }
})();
