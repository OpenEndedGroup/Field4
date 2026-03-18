(() => {
  const ROOT_SELECTOR = ".md-content__inner";
  const PLAYER_SELECTOR = ".code-diff-player";
  const CONTAINER_CLASS = "field-doc-export";
  const BOUND_ATTR = "data-field-doc-export-bound";

  function normalizeCode(text) {
    if (!text) return "";
    const unix = text.replace(/\r\n?/g, "\n");
    return unix.replace(/^\n+/, "").replace(/\n+$/, "");
  }

  function makeSafeId(raw, index) {
    const stem = (raw || "box")
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, "-")
      .replace(/^-+|-+$/g, "")
      .slice(0, 36);
    const n = String(index + 1).padStart(2, "0");
    return `${stem || "box"}-${n}`;
  }

  function escapeEdnString(value) {
    return `"${String(value)
      .replace(/\\/g, "\\\\")
      .replace(/"/g, "\\\"")
      .replace(/\n/g, "\\n")
      .replace(/\t/g, "\\t")}"`;
  }

  function headingForNode(node, headings) {
    let chosen = null;
    for (const heading of headings) {
      const pos = heading.compareDocumentPosition(node);
      if (pos & Node.DOCUMENT_POSITION_FOLLOWING) {
        chosen = heading;
      } else {
        break;
      }
    }
    return chosen ? headingLabel(chosen.textContent || "") : null;
  }

  function headingLabel(text) {
    return (text || "")
      .replace(/\s+/g, " ")
      .trim()
      .replace(/[|]+/g, "-")
      .slice(0, 80);
  }

  function pageTitle() {
    const h1 = document.querySelector(".md-content__inner h1, article h1");
    if (h1 && h1.textContent && h1.textContent.trim()) {
      return headingLabel(h1.textContent);
    }
    return headingLabel(document.title.replace(/\s*-\s*[^-]+$/, "")) || "Field Docs";
  }

  function extractBlockCode(player) {
    const after = player.querySelector('pre[data-state="after"] code, pre[data-role="after"] code');
    const before = player.querySelector('pre[data-state="before"] code, pre[data-role="before"] code');
    const single = player.querySelector('pre[data-state="single"] code, pre[data-state="only"] code, pre[data-role="single"] code, pre[data-role="only"] code');

    if (after && after.textContent && after.textContent.trim()) {
      return normalizeCode(after.textContent);
    }
    if (single && single.textContent && single.textContent.trim()) {
      return normalizeCode(single.textContent);
    }
    if (before && before.textContent && before.textContent.trim()) {
      return normalizeCode(before.textContent);
    }

    const rendered = player.querySelector(".code-diff-line .code-text");
    if (rendered && rendered.textContent) {
      return normalizeCode(rendered.textContent);
    }
    return "";
  }

  function collectBlocks(root) {
    const players = Array.from(root.querySelectorAll(PLAYER_SELECTOR));
    if (!players.length) return [];

    const headings = Array.from(root.querySelectorAll("h1, h2, h3, h4")).sort((a, b) => {
      const aPos = a.compareDocumentPosition(b);
      if (aPos & Node.DOCUMENT_POSITION_FOLLOWING) return -1;
      if (aPos & Node.DOCUMENT_POSITION_PRECEDING) return 1;
      return 0;
    });

    return players
      .map((player, index) => {
        const code = extractBlockCode(player);
        if (!code) return null;

        const section = headingForNode(player, headings);
        const label = section
          ? `${section} ${String(index + 1).padStart(2, "0")}`
          : `Example ${String(index + 1).padStart(2, "0")}`;

        return {
          name: label,
          code,
          uid: makeSafeId(label, index),
          index,
        };
      })
      .filter(Boolean);
  }

  function buildMetadataEdn(blocks) {
    const boxW = 320.0;
    const boxH = 220.0;
    const gap = 36.0;
    const startX = 50.0;
    const y = 80.0;

    const entries = blocks.map((block, idx) => {
      const x = (startX + idx * (boxW + gap)).toFixed(1);
      const name = escapeEdnString(block.name);
      const code = escapeEdnString(block.code);
      const uid = escapeEdnString(block.uid);
      return [
        "  {:id " + uid,
        "   :properties {:name " + name,
        "                :code " + code,
        `                :frame [${x} ${y.toFixed(1)} ${boxW.toFixed(1)} ${boxH.toFixed(1)}]}}` + (idx < blocks.length - 1 ? "," : ""),
      ].join("\n");
    });

    return [
      "{:version 2",
      " :boxes [",
      ...entries,
      " ]}",
    ].join("\n");
  }

  function copyText(text) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      return navigator.clipboard.writeText(text);
    }

    return new Promise((resolve, reject) => {
      try {
        const area = document.createElement("textarea");
        area.value = text;
        area.setAttribute("readonly", "");
        area.style.position = "fixed";
        area.style.left = "-9999px";
        document.body.appendChild(area);
        area.focus();
        area.select();
        const ok = document.execCommand("copy");
        document.body.removeChild(area);
        if (!ok) throw new Error("execCommand copy failed");
        resolve();
      } catch (err) {
        reject(err);
      }
    });
  }

  function setStatus(statusEl, message, kind) {
    statusEl.textContent = message;
    statusEl.setAttribute("data-kind", kind || "info");
  }

  function mount(root) {
    if (!root) return;

    const existing = root.querySelector(`.${CONTAINER_CLASS}`);
    if (existing) existing.remove();

    const blocks = collectBlocks(root);
    if (!blocks.length) {
      root.removeAttribute(BOUND_ATTR);
      return;
    }

    const title = pageTitle();

    const host = document.createElement("div");
    host.className = CONTAINER_CLASS;

    const button = document.createElement("button");
    button.type = "button";
    button.className = "field-doc-export-button";
    button.textContent = "Copy Page As Field Doc";

    const status = document.createElement("span");
    status.className = "field-doc-export-status";
    setStatus(status, `${blocks.length} code blocks detected on ${title}`, "info");

    button.addEventListener("click", async () => {
      const freshBlocks = collectBlocks(root);
      if (!freshBlocks.length) {
        setStatus(status, "No diff blocks found on this page", "error");
        return;
      }

      const titledBlocks = freshBlocks.map((block) => ({
        ...block,
        name: `${title} / ${block.name}`,
      }));

      const edn = buildMetadataEdn(titledBlocks);
      try {
        await copyText(edn);
        setStatus(status, `Copied metadata.edn (${freshBlocks.length} boxes). Paste into a blank Field page.`, "ok");
      } catch (err) {
        console.error("field-doc-export copy failed", err);
        setStatus(status, "Copy failed. Your browser blocked clipboard access.", "error");
      }
    });

    host.appendChild(button);
    host.appendChild(status);

    const anchor = root.querySelector("h1");
    if (anchor && anchor.parentElement) {
      anchor.insertAdjacentElement("afterend", host);
    } else {
      root.prepend(host);
    }

    root.setAttribute(BOUND_ATTR, "true");
  }

  function mountAll() {
    const roots = Array.from(document.querySelectorAll(ROOT_SELECTOR));
    roots.forEach((root) => mount(root));
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", () => setTimeout(mountAll, 0), { once: true });
  } else {
    setTimeout(mountAll, 0);
  }

  if (window.document$ && typeof window.document$.subscribe === "function") {
    window.document$.subscribe(() => {
      setTimeout(mountAll, 0);
    });
  }
})();
