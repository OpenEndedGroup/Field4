/*
  Sticky section headings for the generated source pages.

  A source page is a deep outline — a file heading, banner-comment sections under it, a declaration
  under those, its members under that — and scrolling a long one loses the reader's place entirely.
  The page title stays visible because the theme puts it in the header; nothing else does.

  Markdown emits headings as *siblings*, so `position: sticky` alone cannot express "stick while I am
  the current section": a sticky sibling never stops sticking until something at the same offset
  displaces it, which leaves a stale h3 from two declarations ago pinned under the current h2. So the
  flat list is first wrapped into nested <section>s, one per heading, each ending where a heading of
  the same or shallower level begins. Then stickiness means what it should, and the stack under the
  header reads as the path to wherever you are.
*/
(function () {
  const CONTENT = ".md-content .md-typeset";
  const HEADINGS = "h1, h2, h3, h4";
  const HEADING_TAGS = /^H[1-4]$/;

  function headingOf(section) {
    const first = section.firstElementChild;
    return first && HEADING_TAGS.test(first.tagName) ? first : null;
  }

  function levelOf(el) {
    return parseInt(el.tagName.slice(1), 10);
  }

  function wrapSections(root) {
    // One left-to-right pass with a stack of open sections. Nodes are appended to the innermost open
    // section, which is what makes the nesting fall out of the heading levels.
    const nodes = Array.from(root.childNodes);
    const stack = [];
    root.textContent = "";

    for (const node of nodes) {
      const isHeading = node.nodeType === 1 && node.matches(HEADINGS);
      if (isHeading) {
        const level = levelOf(node);
        while (stack.length && stack[stack.length - 1].level >= level) stack.pop();
        const section = document.createElement("section");
        section.className = "field-sticky-section";
        section.dataset.level = String(level);
        (stack.length ? stack[stack.length - 1].el : root).appendChild(section);
        section.appendChild(node);
        stack.push({ level, el: section });
        continue;
      }
      (stack.length ? stack[stack.length - 1].el : root).appendChild(node);
    }
  }

  /*
    Where each heading pins: below the header, plus the heights of its own ancestor headings.

    Ancestors rather than a per-level constant, because a heading's height is not a property of its
    level. `Linear algebra helpers (self-contained on purpose)` wraps to two lines, and an offset
    derived from some other one-line h1 pinned the h2 *inside* it — the reader saw a two-line title
    with a clipped band of signature under it. Sections are nested, so the stack above a heading when
    it pins is exactly its ancestor chain, which is knowable here and right whatever those headings
    wrap to. Re-run on resize, since what wraps depends on the width.
  */
  function assignStickyTops(root) {
    const header = document.querySelector(".md-header");
    const base = header ? header.getBoundingClientRect().height : 48;
    root.querySelectorAll(".field-sticky-section").forEach((section) => {
      const heading = headingOf(section);
      if (!heading) return;
      let offset = base;
      let ancestor = section.parentElement && section.parentElement.closest(".field-sticky-section");
      while (ancestor) {
        const above = headingOf(ancestor);
        if (above) offset += above.getBoundingClientRect().height;
        ancestor = ancestor.parentElement && ancestor.parentElement.closest(".field-sticky-section");
      }
      heading.style.top = offset.toFixed(1) + "px";
    });
  }

  /*
    Which headings are wearing the pinned treatment, and which is the last of them.

    "Has reached its offset and is still on screen" rather than "is exactly at its offset". The exact
    test looked right and behaved badly: a sticky heading releases by travelling *up* past its offset
    as its section scrolls out, so equality goes false at the first pixel of that journey — the
    backdrop vanished and the rule came back while the heading was still over the code, and the code
    read straight through the title. Keeping the treatment until the heading is genuinely gone makes
    releasing look like leaving rather than like breaking.
  */
  function markPinned(root) {
    const header = document.querySelector(".md-header");
    const headerBottom = header ? header.getBoundingClientRect().bottom : 48;
    let last = null;
    root
      .querySelectorAll(
        ".field-sticky-section > h1, .field-sticky-section > h2, " +
          ".field-sticky-section > h3, .field-sticky-section > h4"
      )
      .forEach((heading) => {
        const stickyTop = parseFloat(getComputedStyle(heading).top);
        const box = heading.getBoundingClientRect();
        const pinned =
          Number.isFinite(stickyTop) && box.top <= stickyTop + 1.5 && box.bottom > headerBottom;
        heading.classList.toggle("is-pinned", pinned);
        heading.classList.remove("is-pinned-last");
        if (pinned) last = heading;
      });
    if (last) last.classList.add("is-pinned-last");
  }

  function apply() {
    if (!document.querySelector("h1.field-source-page")) return;
    const root = document.querySelector(CONTENT);
    if (!root || root.dataset.stickyHeadings === "true") return;
    root.dataset.stickyHeadings = "true";
    wrapSections(root);
    assignStickyTops(root);

    let queued = false;
    const update = () => {
      queued = false;
      markPinned(root);
    };
    const schedule = () => {
      if (queued) return;
      queued = true;
      requestAnimationFrame(update);
    };
    window.addEventListener("scroll", schedule, { passive: true });
    window.addEventListener("resize", () => {
      assignStickyTops(root);
      schedule();
    });
    // requestAnimationFrame does not run in a hidden tab, so a scroll from before the tab was
    // backgrounded can leave the classes describing the wrong stack. Re-check on the way back rather
    // than waiting for the reader's next scroll.
    document.addEventListener("visibilitychange", () => {
      if (!document.hidden) schedule();
    });
    update();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", apply, { once: true });
  } else {
    apply();
  }
})();
