(function () {
  const DESKTOP_QUERY = "(min-width: 76.25em)";
  const HEADER_SELECTOR = ".md-header";
  const CONTENT_SELECTOR = ".md-content .md-content__inner";
  const TYPESET_SELECTOR = ".md-content .md-typeset";

  let railRoot = null;
  let rowsContainer = null;
  let progressFill = null;
  let progressViewport = null;
  let headingItems = [];
  let articleBounds = { top: 0, height: 1 };

  function clamp(value, min, max) {
    return Math.min(max, Math.max(min, value));
  }

  function slugify(text) {
    return text
      .trim()
      .toLowerCase()
      .replace(/[^a-z0-9\s-]/g, "")
      .replace(/\s+/g, "-")
      .replace(/-+/g, "-");
  }

  function ensureHeadingIds(headings) {
    const usedIds = new Set();
    headings.forEach((heading, index) => {
      if (!heading.id) {
        const base = slugify(heading.textContent || heading.innerText || "section") || "section";
        let candidate = base;
        let suffix = 2;
        while (document.getElementById(candidate) || usedIds.has(candidate)) {
          candidate = `${base}-${suffix}`;
          suffix += 1;
        }
        heading.id = candidate;
      }
      usedIds.add(heading.id);
      heading.dataset.fieldHeadingIndex = String(index);
    });
  }

  function getHeaderHeight() {
    const header = document.querySelector(HEADER_SELECTOR);
    return header ? header.getBoundingClientRect().height : 0;
  }

  function getDocumentY(element) {
    return window.scrollY + element.getBoundingClientRect().top;
  }

  function updateArticleBounds() {
    const contentInner = document.querySelector(CONTENT_SELECTOR);
    if (!contentInner) return;

    const top = getDocumentY(contentInner);
    const height = Math.max(contentInner.scrollHeight, contentInner.getBoundingClientRect().height);
    articleBounds = { top, height: Math.max(1, height) };
  }

  function createRail() {
    if (railRoot) return;

    railRoot = document.createElement("aside");
    railRoot.className = "field-reading-rail";
    railRoot.setAttribute("aria-label", "Page outline");

    const progress = document.createElement("div");
    progress.className = "field-reading-rail__progress";

    const track = document.createElement("div");
    track.className = "field-reading-rail__progress-track";

    progressFill = document.createElement("div");
    progressFill.className = "field-reading-rail__progress-fill";

    progressViewport = document.createElement("div");
    progressViewport.className = "field-reading-rail__progress-viewport";

    track.appendChild(progressFill);
    track.appendChild(progressViewport);
    progress.appendChild(track);

    rowsContainer = document.createElement("nav");
    rowsContainer.className = "field-reading-rail__rows";

    railRoot.appendChild(progress);
    railRoot.appendChild(rowsContainer);
    document.body.appendChild(railRoot);
  }

  function clearRailRows() {
    headingItems = [];
    if (!rowsContainer) return;
    while (rowsContainer.firstChild) {
      rowsContainer.removeChild(rowsContainer.firstChild);
    }
  }

  function buildRows(headings) {
    if (!rowsContainer) return;

    clearRailRows();

    const positions = headings.map((heading) => getDocumentY(heading));
    const contentBottom = articleBounds.top + articleBounds.height;
    const segmentEnds = positions.slice(1).concat(contentBottom);

    const minSegment = 24;
    const segments = segmentEnds.map((end, i) => Math.max(minSegment, end - positions[i]));

    headings.forEach((heading, index) => {
      const row = document.createElement("div");
      row.className = "field-reading-rail__row";
      row.style.flexGrow = String(segments[index]);

      const isH1 = heading.tagName.toLowerCase() === "h1";
      const dot = document.createElement("span");
      dot.className = isH1
        ? "field-reading-rail__dot field-reading-rail__dot--title"
        : "field-reading-rail__dot";
      dot.setAttribute("aria-hidden", "true");

      const link = document.createElement("a");
      link.className = "field-reading-rail__link";
      link.href = `#${heading.id}`;
      link.textContent = heading.textContent || "Untitled";
      link.addEventListener("click", (event) => {
        event.preventDefault();
        const headerOffset = getHeaderHeight() + 12;
        const targetY = getDocumentY(heading) - headerOffset;
        window.history.replaceState(null, "", `#${heading.id}`);
        window.scrollTo({ top: targetY, behavior: "smooth" });
      });

      row.appendChild(dot);
      row.appendChild(link);

      rowsContainer.appendChild(row);
      headingItems.push({ heading, row, top: positions[index] });
    });
  }

  function refreshHeadingPositions() {
    headingItems.forEach((item) => {
      item.top = getDocumentY(item.heading);
    });
  }

  /* The rows are laid out by flexbox, so each heading's dot sits where its row starts, not at its
     linear position in the article: rows have a content height, and short sections are padded to
     `minSegment`. On a page with many headings that drift is large, so the fill and the viewport
     window are drawn through the same mapping the dots use — piecewise linear between each
     heading's document Y and its row's offset in the rail. */
  function documentYToRailRatio(y) {
    const railHeight = rowsContainer ? rowsContainer.clientHeight : 0;
    const articleTop = articleBounds.top;
    const articleBottom = articleBounds.top + articleBounds.height;
    if (!railHeight || !headingItems.length) {
      return clamp((y - articleTop) / articleBounds.height, 0, 1);
    }

    const anchors = [{ doc: articleTop, rail: 0 }];
    headingItems.forEach((item) => {
      const last = anchors[anchors.length - 1];
      const doc = clamp(item.top, last.doc, articleBottom);
      const rail = clamp(item.row.offsetTop / railHeight, last.rail, 1);
      anchors.push({ doc, rail });
    });
    anchors.push({ doc: articleBottom, rail: 1 });

    if (y <= articleTop) return 0;
    if (y >= articleBottom) return 1;
    for (let i = 1; i < anchors.length; i += 1) {
      const a = anchors[i - 1];
      const b = anchors[i];
      if (y <= b.doc) {
        const span = b.doc - a.doc;
        const t = span > 0 ? (y - a.doc) / span : 1;
        return a.rail + t * (b.rail - a.rail);
      }
    }
    return 1;
  }

  function updateProgress() {
    if (!railRoot || !progressFill || !progressViewport) return;

    const headerHeight = getHeaderHeight();
    const visibleTop = window.scrollY + headerHeight;
    const visibleBottom = window.scrollY + window.innerHeight;

    /* Fill tracks how far the bottom of the visible viewport has scrolled through the article */
    const fillRatio = documentYToRailRatio(visibleBottom);
    const topRatio = documentYToRailRatio(visibleTop);
    const minViewport = 0.04;
    const viewportRatio = Math.max(minViewport, fillRatio - topRatio);

    progressFill.style.height = `${fillRatio * 100}%`;
    progressViewport.style.height = `${viewportRatio * 100}%`;
    progressViewport.style.top = `${clamp(topRatio, 0, 1 - viewportRatio) * 100}%`;
  }

  function updateActiveHeading() {
    if (!headingItems.length) return;

    /* Activate a heading when it crosses the bottom third of the viewport */
    const triggerY = window.scrollY + window.innerHeight * 0.65;
    let activeIndex = 0;

    for (let i = 0; i < headingItems.length; i += 1) {
      if (headingItems[i].top <= triggerY) activeIndex = i;
    }

    headingItems.forEach((item, index) => {
      item.row.classList.toggle("is-active", index === activeIndex);
    });
  }

  function updateRailClearance() {
    const content = document.querySelector(CONTENT_SELECTOR);
    if (!railRoot || !content) return;
    const rect = railRoot.getBoundingClientRect();
    const hitWidth = parseFloat(getComputedStyle(railRoot, "::before").width) || 32;
    let right = rect.left + hitWidth;
    railRoot.querySelectorAll(".field-reading-rail__link").forEach(link => {
      if (parseFloat(getComputedStyle(link).opacity) > 0) {
        right = Math.max(right, link.getBoundingClientRect().right);
      }
    });
    // Finish fading with room left between the article and the visible navigation.
    const opacity = clamp((content.getBoundingClientRect().left - right - 24) / 64, 0, 1);
    railRoot.style.opacity = String(opacity);
    railRoot.inert = opacity === 0;
    railRoot.classList.toggle("is-obscured", opacity === 0);
    if (opacity === 0) railRoot.setAttribute("aria-hidden", "true");
    else railRoot.removeAttribute("aria-hidden");
  }

  function refreshState() {
    updateArticleBounds();
    refreshHeadingPositions();
    updateProgress();
    updateActiveHeading();
    updateRailClearance();
  }

  function buildRail() {
    const desktopOnly = window.matchMedia(DESKTOP_QUERY).matches;
    if (!desktopOnly) {
      if (railRoot) railRoot.classList.add("is-hidden");
      return;
    }

    const headings = Array.from(document.querySelectorAll(".md-content .md-typeset h1, .md-content .md-typeset h2"));
    if (!headings.length) {
      if (railRoot) railRoot.classList.add("is-hidden");
      return;
    }

    ensureHeadingIds(headings);
    createRail();
    railRoot.classList.remove("is-hidden");
    updateArticleBounds();
    buildRows(headings);
    refreshState();
  }

  let ticking = false;
  function onScrollOrResize() {
    if (ticking) return;
    ticking = true;
    window.requestAnimationFrame(() => {
      refreshState();
      ticking = false;
    });
  }

  function setup() {
    buildRail();
    window.addEventListener("scroll", onScrollOrResize, { passive: true });
    window.addEventListener("resize", () => {
      buildRail();
      onScrollOrResize();
    });
    window.addEventListener("hashchange", onScrollOrResize);

    document.addEventListener("click", (event) => {
      const target = event.target;
      if (!(target instanceof HTMLElement)) return;
      if (!target.closest("a[href^='#']")) return;
      window.setTimeout(onScrollOrResize, 30);
    });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", setup, { once: true });
  } else {
    setup();
  }
})();
