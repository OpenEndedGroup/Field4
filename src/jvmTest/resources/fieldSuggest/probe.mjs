// Regression cover for the editor's completion *presentation* path, by running the real
// src/commonMain/resources/fieldSuggest.js under node.
//
// Why this file exists: the completion server deliberately returns everything unranked
// (CompletionCorpusProbe.kt says so), so ranking, grouping, category titles, recency, the row
// markup, the doc panel and the signature badge all live in that one JavaScript file — and until
// this probe, nothing executed it. The defect that motivated it (commit 9ba22de77) was a two-word
// category rendering as "Audio Ai", found by a human looking at a popup.
//
// Shape: install a minimal browser/Monaco host (host.mjs), stub the compiled Kotlin/JS bundle's
// three imports (afield.mjs), then go in through the public entry point — `installFieldSuggest` →
// `controller.open()` → a canned server reply → and assert on the HTML the popup actually wrote.
//
//   node probe.mjs cases                     # the behaviour suite
//   node probe.mjs categories cats.json      # the class-level category-title guard
//
// Exits non-zero and prints every failure. Add a case by adding to CASES below; a case is an async
// function that throws (or returns a string) to fail.

import { installHost, makeModel, makeMonaco, makeEditor, settle } from "./host.mjs";
import { rpc, requests, defer, flush } from "./afield.mjs";
import { readFileSync } from "node:fs";

const window = installHost();
const { installFieldSuggest } = await import("./fieldSuggest.js");

const RECENCY_KEY = "field.suggest.recency.v1";
const CARET = "|";

// ---------------------------------------------------------------------------- reading the popup

const unescapeHtml = (text) =>
  String(text)
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, "&");

/** Visible text of a fragment of the popup's own HTML. */
const textOf = (html) => unescapeHtml(String(html).replace(/<[^>]*>/g, "")).trim();

const firstGroup = (regex, html) => {
  const match = regex.exec(html);
  return match ? match[1] : "";
};

/**
 * Parse the rendered list into an ordered `[{kind:"section"|"row", …}]`. Reads the popup's emitted
 * HTML string, not the harness DOM, so the harness cannot make a broken render look right.
 */
const readList = (html) => {
  const marks = [];
  const boundary = /<div class="afield-suggest-(section|row)([^"]*)"/g;
  let match;
  while ((match = boundary.exec(html)) !== null) {
    marks.push({ kind: match[1], mods: match[2], start: match.index });
  }
  return marks.map((mark, i) => {
    const chunk = html.slice(mark.start, i + 1 < marks.length ? marks[i + 1].start : html.length);
    if (mark.kind === "section") return { kind: "section", title: textOf(chunk), raw: chunk };
    return {
      kind: "row",
      active: /\bis-active\b/.test(mark.mods),
      index: Number(firstGroup(/data-index="(\d+)"/, chunk)),
      icon: textOf(firstGroup(/<div class="afield-suggest-icon"[^>]*>([\s\S]*?)<\/div>/, chunk)),
      label: textOf(firstGroup(/<div class="afield-suggest-label">([\s\S]*?)<\/div>/, chunk)),
      detail: textOf(firstGroup(/<div class="afield-suggest-detail">([\s\S]*?)<\/div>/, chunk)),
      recent: /afield-suggest-recent/.test(chunk),
      raw: chunk,
    };
  });
};

const sectionsOf = (html) => readList(html).filter((e) => e.kind === "section").map((e) => e.title);
const rowsOf = (html) => readList(html).filter((e) => e.kind === "row");
const labelsOf = (html) => rowsOf(html).map((row) => row.label);

// ---------------------------------------------------------------------------- driving the popup

/** One server completion candidate, in the wire shape `Completion.metadata` really has. */
const candidate = (text, extra = {}) => ({
  text,
  displayText: extra.displayText || text,
  tail: extra.tail || "",
  icon: extra.icon || "method",
  category: extra.category || "",
  ...extra,
});

/**
 * A server reply. `boundsStart`/`boundsEnd` span the token being replaced, which is what every
 * engine in the tree actually sends (see `GlslCompletionSupport.complete`), and what makes accepting
 * a completion overwrite the typed prefix instead of appending to it.
 */
const completionReply = (metadata, boundsStart, boundsEnd) => ({
  metadata,
  boundsStart,
  boundsEnd,
  useLineColumns: false,
});

/**
 * Open the popup over `code` (with `|` marking the caret) against a canned server reply, and hand
 * back the controller plus what it rendered.
 */
const openPopup = async ({
  code,
  metadata = [],
  aiMetadata = [],
  languageId = "kotlin",
  recency = null,
  resolve = null,
  resolveSource = null,
  // RPC names whose reply is parked until the case calls `flush(name)` — the only way to observe the
  // popup's intermediate states.
  deferred = [],
}) => {
  const caret = code.indexOf(CARET);
  if (caret < 0) throw new Error(`fixture has no ${CARET} caret marker`);
  const text = code.replace(CARET, "");
  const model = makeModel(text, languageId);
  const position = model.getPositionAt(caret);
  const editor = makeEditor(model, position);
  const prefix = model.getWordUntilPosition(position).word;

  window.localStorage.clear();
  if (recency) window.localStorage.setItem(RECENCY_KEY, JSON.stringify(recency));
  requests.length = 0;
  window._badges.length = 0;
  rpc.clear();
  flush();
  window._httpRoutes.clear();
  window._httpRequests.length = 0;
  const park = (name, responder) => rpc.set(name, deferred.includes(name) ? defer(responder) : responder);
  park("complete", () => completionReply(metadata, caret - prefix.length, caret));
  park("complete-kotlin-ai", () => completionReply(aiMetadata, caret - prefix.length, caret));
  if (resolve) rpc.set("resolve-completion", resolve);
  if (resolveSource) rpc.set("resolve-source", resolveSource);

  const monaco = makeMonaco();
  const controller = installFieldSuggest(editor, monaco);
  await controller.open({ manual: true });
  await settle();

  const list = () => controller.root.querySelector(".afield-suggest-list").innerHTML;
  const doc = () => controller.root.querySelector(".afield-suggest-doc").innerHTML;
  const docNode = () => controller.root.querySelector(".afield-suggest-doc");
  /** Deliver one key to the popup's own `onKeyDown`, the way Monaco would. */
  const press = (keyCode) => {
    const handler = editor._handlers.onKeyDown[0];
    handler({ keyCode, ctrlKey: false, metaKey: false, preventDefault() {}, stopPropagation() {} });
  };
  const activeRow = () => rowsOf(list()).find((row) => row.active) || null;
  return { controller, editor, model, monaco, position, caret, list, doc, docNode, press, activeRow };
};

/**
 * The recency store key fieldSuggest.js computes (`completionKeyFor`), mirrored here so a case can
 * seed one entry. This is the harness's only duplicated rule, and the "an accepted completion is
 * remembered" case guards the duplication: a real `accept()` must write a key of this same shape.
 */
const SEPARATOR = "\u001f";
const recencyEntry = (languageId, category, insertText, detail, count) => ({
  [[languageId, String(category || "other").toLowerCase(), insertText, detail].join(SEPARATOR)]: {
    count,
    lastAccepted: Date.now(),
  },
});

// ---------------------------------------------------------------------------- assertions

const fail = (message) => {
  throw new Error(message);
};
const eq = (actual, expected, what) => {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a !== e) fail(`${what}\n  expected: ${e}\n  actual:   ${a}`);
};
const ok = (condition, what) => {
  if (!condition) fail(what);
};
const before = (labels, first, second, what) => {
  const i = labels.indexOf(first);
  const j = labels.indexOf(second);
  ok(i >= 0, `${what}: ${first} is not in the list at all (${JSON.stringify(labels)})`);
  ok(j >= 0, `${what}: ${second} is not in the list at all (${JSON.stringify(labels)})`);
  ok(i < j, `${what}: expected ${first} above ${second}, got ${JSON.stringify(labels)}`);
};

// ---------------------------------------------------------------------------- the cases

const CASES = {
  // ---- the defect that motivated all of this, end to end -------------------------------------
  "multi-word category headers render their own spelling": async () => {
    const { list } = await openPopup({
      code: "val board = candidateB|",
      metadata: [
        candidate("candidateBoard", { category: "Audio AI", tail: ": String", icon: "method" }),
        candidate("candidateBoardHtml", { category: "Audio AI", tail: ": String" }),
        candidate("candidateBoids", { category: "ALife", tail: ": Boids" }),
      ],
    });
    const html = list();
    eq(sectionsOf(html), ["Audio AI", "ALife"], "group headers");
    ok(!/Audio Ai/.test(html), `the "Audio Ai" defect (9ba22de77) is back:\n${html}`);
    ok(!/\bAlife\b/.test(html), `"ALife" rendered as "Alife":\n${html}`);
  },

  // ---- grouping ------------------------------------------------------------------------------
  "each category gets exactly one header and its rows stay contiguous": async () => {
    // Scores deliberately interleave the two categories (via the server's contextScore), so a
    // popup that only sorted — and never grouped — would render G, I, G, I.
    const opened = await openPopup({
      code: "val x = |",
      metadata: [
        candidate("gridA", { category: "Graphics", contextScore: 0 }),
        candidate("importB", { category: "Imports", contextScore: 50 }),
        candidate("gridC", { category: "Graphics", contextScore: 40 }),
        candidate("importD", { category: "Imports", contextScore: 0 }),
      ],
    });
    const html = opened.list();
    eq(sectionsOf(html), ["Graphics", "Imports"], "one header per category, in first-ranked order");
    eq(labelsOf(html), ["gridC", "gridA", "importB", "importD"], "rows grouped, ranked within group");
    eq(
      readList(html).map((e) => e.kind),
      ["section", "row", "row", "section", "row", "row"],
      "header then its rows",
    );
    // Grouping has to happen to the ITEM LIST and not only to the markup, or the arrow keys walk in
    // score order while the eye walks in group order. `renderList` groups a second time, so this is
    // the only assertion that can tell the difference — and the one that fails if the
    // `orderItemsForGroupedDisplay` pass is dropped from `filterAndLimit`.
    eq(rowsOf(html).map((row) => row.index), [0, 1, 2, 3], "keyboard order is visual order");
    opened.press(opened.monaco.KeyCode.DownArrow);
    eq(opened.activeRow().label, "gridA", "down arrow lands on the row below, not the next by score");
  },

  // ---- ranking -------------------------------------------------------------------------------
  "fuzzy ranking prefers exact, then prefix, then word boundary, then subsequence": async () => {
    const { list } = await openPopup({
      code: "val v = ve|",
      // Deliberately supplied worst-first: only ranking can produce the expected order.
      metadata: [
        candidate("valueContainer", { category: "Math" }),
        candidate("toVec", { category: "Math" }),
        candidate("vecLength", { category: "Math" }),
        candidate("vec", { category: "Math" }),
      ],
    });
    eq(labelsOf(list()), ["vec", "vecLength", "toVec", "valueContainer"], "fuzzy order");
  },

  "category weight breaks a fuzzy tie": async () => {
    const { list } = await openPopup({
      code: "val l = line|",
      metadata: [
        candidate("line", { category: "Imports", insertText: "import afield.line" }),
        candidate("line", { category: "Graphics", tail: ": FLine" }),
      ],
    });
    eq(sectionsOf(list()), ["Graphics", "Imports"], "Graphics (weight 26) outranks Imports (10)");
  },

  "the server's contextScore boost wins, and a non-numeric one is inert": async () => {
    const boosted = await openPopup({
      code: "val v = v|",
      metadata: [
        candidate("vecA", { category: "Math" }),
        candidate("vecB", { category: "Math", contextScore: 40 }),
      ],
    });
    eq(labelsOf(boosted.list()), ["vecB", "vecA"], "contextScore boost");

    const poisoned = await openPopup({
      code: "val v = v|",
      metadata: [
        candidate("vecA", { category: "Math", contextScore: "not-a-number" }),
        candidate("vecB", { category: "Math", contextScore: null }),
      ],
    });
    const html = poisoned.list();
    eq(labelsOf(html), ["vecA", "vecB"], "a junk contextScore falls back to payload order");
    ok(!/NaN/.test(html), `NaN leaked into the popup:\n${html}`);
    for (const item of poisoned.controller.items) {
      ok(Number.isFinite(item.score), `item ${item.label} scored ${item.score}`);
    }
  },

  // Note for anyone extending these: within one group the popup shows RANK order, but the groups
  // themselves are ordered by their best-ranked member (`orderItemsForGroupedDisplay`). So a
  // ranking change between two categories is visible as a change of GROUP order, never as rows
  // interleaving. Both halves of that are asserted below.
  "after a dot, Object plumbing sinks below the real members": async () => {
    // All from one declaring file, which is how the server categorises the members of a type.
    const members = [
      candidate("equals", { category: "Math", icon: "method" }),
      candidate("extrude", { category: "Math", icon: "method" }),
      candidate("edges", { category: "Math", icon: "value" }),
    ];
    const free = await openPopup({ code: "val q = e|", metadata: members });
    const member = await openPopup({ code: "val v = Vec3()\nv.e|", metadata: members });

    ok(free.controller.memberContext === false, "expected a non-member position for `e|`");
    ok(member.controller.memberContext === true, "expected a member position for `v.e|`");
    eq(labelsOf(free.list()), ["edges", "equals", "extrude"], "plain fuzzy order");
    eq(labelsOf(member.list()), ["edges", "extrude", "equals"], "equals demoted after a dot");
  },

  "after a dot, keywords sink below the real members": async () => {
    // Keywords arrive categorised `Kotlin` (KotlinCompletionSupport.inferCompletionCategory), so the
    // -180 keyword penalty shows up as the Kotlin group moving, not as rows interleaving.
    const metadata = [
      candidate("else", { category: "Kotlin", icon: "keyword" }),
      candidate("edges", { category: "Math", icon: "value" }),
    ];
    const free = await openPopup({ code: "val q = e|", metadata });
    const member = await openPopup({ code: "val v = Vec3()\nv.e|", metadata });
    eq(sectionsOf(free.list()), ["Kotlin", "Math"], "a keyword may lead where a keyword is legal");
    eq(sectionsOf(member.list()), ["Math", "Kotlin"], "after a dot, members lead");
  },

  "an accepted completion is remembered and badged as recent": async () => {
    const metadata = [
      candidate("vecA", { category: "Math" }),
      candidate("vecB", { category: "Math" }),
    ];
    const cold = await openPopup({ code: "val v = v|", metadata });
    eq(labelsOf(cold.list()), ["vecA", "vecB"], "no recency: payload order");

    const warm = await openPopup({
      code: "val v = v|",
      metadata,
      recency: recencyEntry("kotlin", "Math", "vecB", "", 5),
    });
    const rows = rowsOf(warm.list());
    eq(rows.map((r) => r.label), ["vecB", "vecA"], "a recently accepted item is boosted");
    ok(rows[0].recent, `expected a "recent" badge on vecB, got: ${rows[0].raw}`);
    ok(!rows[1].recent, `did not expect a "recent" badge on vecA, got: ${rows[1].raw}`);
  },

  // ---- what a row shows --------------------------------------------------------------------
  "the return type the server sent in `tail` is what the row shows": async () => {
    const { list } = await openPopup({
      code: "val n = cros|",
      metadata: [
        candidate("cross", {
          displayText: "cross(other: Vec3)",
          tail: ": Vec3",
          category: "Math",
          icon: "method",
        }),
      ],
    });
    const row = rowsOf(list())[0];
    eq(row.label, "cross(other: Vec3)", "row label");
    ok(row.detail.startsWith(": Vec3"), `row detail should carry the tail, got ${row.detail}`);
    eq(row.icon, "me", "icon cell is the first two characters of the icon name");
    ok(row.active, "the first row is active");
  },

  "a hostile label or category cannot inject markup": async () => {
    const { list } = await openPopup({
      code: "val x = evi|",
      metadata: [
        candidate("evil", {
          displayText: `evil<img src=x onerror="boom()">`,
          tail: `<script>1</script>`,
          category: `<b>Sneaky</b>`,
        }),
      ],
    });
    const html = list();
    ok(!/<img/.test(html), `an unescaped <img> reached the popup:\n${html}`);
    ok(!/<script/.test(html), `an unescaped <script> reached the popup:\n${html}`);
    ok(!/onerror="boom/.test(html), `an unescaped handler reached the popup:\n${html}`);
    // The label is tokenised into per-token spans, so the escaped text is not contiguous; read it
    // back through the row instead, which proves nothing was dropped either.
    eq(rowsOf(html)[0].label, `evil<img src=x onerror="boom()">`, "label round-trips as text");
    // The header still title-cases, so the text is mangled — but it is inert, which is the point.
    eq(sectionsOf(html).length, 1, "one header");
    ok(/Sneaky/.test(html), "the category still names itself in the header");
    ok(/&lt;/.test(firstGroup(/<div class="afield-suggest-section">([\s\S]*?)<\/div>/, html)),
      `expected the category's markup to be escaped in the header:\n${html}`);
  },

  // ---- the request the popup sends ----------------------------------------------------------
  "the completion request carries the caret the editor is actually at": async () => {
    const { caret, position } = await openPopup({
      code: "val a = 1\nval b = fli|",
      metadata: [candidate("fline", { category: "Graphics" })],
    });
    const complete = requests.find((r) => r.name === "complete");
    ok(complete, `no "complete" request was sent: ${JSON.stringify(requests.map((r) => r.name))}`);
    eq(complete.payload.uid, "box-under-test", "request uid");
    eq(complete.payload.property, "code", "request property");
    eq(complete.payload.line, position.lineNumber, "request line");
    eq(complete.payload.column, position.column, "request column");
    eq(complete.payload.position, caret, "request offset");
    ok(complete.payload.allCode.includes("val b = fli"), "request carries the whole box source");
    ok(
      requests.some((r) => r.name === "complete-kotlin-ai"),
      "Kotlin should also ask for AI completions",
    );
  },

  "observability records the exact presented order and accepted visible rank": async () => {
    const opened = await openPopup({
      code: "val v = ve|",
      // Server order is deliberately not display order. This asserts telemetry reads the popup's
      // post-ranking list rather than echoing the RPC response.
      metadata: [
        candidate("valueContainer", { category: "Math" }),
        candidate("vecLength", { category: "Math", tail: ": Double" }),
        candidate("vec", { category: "Math", tail: ": Vec3" }),
      ],
    });

    const observations = requests.filter((r) => r.name === "completion-interaction");
    const requested = observations.find((r) => r.payload.event === "requested");
    ok(requested, `no requested observation: ${JSON.stringify(observations)}`);
    eq(requested.payload.allCode, "val v = ve", "observed unsaved source");
    eq(requested.payload.position, opened.caret, "observed caret offset");
    eq(requested.payload.line, opened.position.lineNumber, "observed caret line");
    eq(requested.payload.column, opened.position.column, "observed caret column");

    const presentations = observations.filter((r) => r.payload.event === "presented");
    ok(presentations.length >= 1, `no presentation observation: ${JSON.stringify(observations)}`);
    const visible = presentations.at(-1).payload;
    eq(
      visible.candidates.map((candidate) => candidate.displayText),
      ["vec", "vecLength", "valueContainer"],
      "telemetry candidate order is the row order the user sees",
    );

    opened.press(opened.monaco.KeyCode.DownArrow);
    opened.press(opened.monaco.KeyCode.Enter);
    await settle();
    const outcome = requests
      .filter((r) => r.name === "completion-interaction" && r.payload.event === "outcome")
      .at(-1)?.payload;
    ok(outcome, "no terminal completion observation");
    eq(outcome.interactionId, requested.payload.interactionId, "one correlated interaction");
    eq(outcome.outcome, "accepted", "terminal outcome");
    eq(outcome.selectedIndex, 1, "accepted row's visible zero-based rank");
    eq(outcome.selected.displayText, "vecLength", "accepted candidate");
    eq(outcome.sourceAfter, "val v = vecLength", "source after acceptance");
    eq(outcome.presentationRevision, visible.presentationRevision, "accepted presentation revision");
  },

  "closing autocomplete records that no completion was picked": async () => {
    const opened = await openPopup({
      code: "val v = ve|",
      metadata: [candidate("vec", { category: "Math" })],
    });
    const requested = requests.find(
      (r) => r.name === "completion-interaction" && r.payload.event === "requested",
    );
    opened.press(opened.monaco.KeyCode.Escape);
    const outcome = requests.find(
      (r) =>
        r.name === "completion-interaction" &&
        r.payload.event === "outcome" &&
        r.payload.interactionId === requested.payload.interactionId,
    )?.payload;
    ok(outcome, "no dismissed terminal observation");
    eq(outcome.outcome, "dismissed", "no candidate was picked");
    eq(outcome.reason, "escape", "dismissal reason");
    eq(outcome.selected, null, "dismissal has no selected candidate");
  },

  "only Kotlin pays for the AI round trip": async () => {
    await openPopup({
      code: "void main() { vec3 c = te| }",
      languageId: "glsl",
      metadata: [candidate("textureSize", { category: "Graphics" })],
    });
    eq(
      requests.map((r) => r.name).filter((n) => n === "complete-kotlin-ai"),
      [],
      "a GLSL box must not ask for Kotlin AI completions",
    );
  },

  // ---- the doc pane ------------------------------------------------------------------------
  "the doc pane says so when there is nothing, then shows what resolve returns": async () => {
    const opened = await openPopup({
      code: "val n = cros|",
      metadata: [candidate("cross", { category: "Math", generateDocumentation: true })],
      resolve: () => ({ documentation: "Cross product of two vectors." }),
    });
    // `resolve-completion` is fired from the first render, so by now it has come back.
    const resolveRequest = requests.find((r) => r.name === "resolve-completion");
    ok(resolveRequest, `no lazy documentation request: ${JSON.stringify(requests.map((r) => r.name))}`);
    eq(resolveRequest.payload.insertText, "cross", "resolve request insertText");
    ok(opened.doc().includes("Cross product of two vectors."), `doc pane: ${opened.doc()}`);

    const bare = await openPopup({
      code: "val n = cros|",
      metadata: [candidate("cross", { category: "Math" })],
    });
    ok(
      bare.doc().includes("No documentation available"),
      `expected the empty-doc message, got: ${bare.doc()}`,
    );
  },

  "the doc pane links its source and explains the receiver": async () => {
    const { doc } = await openPopup({
      code: "val n = cros|",
      metadata: [
        candidate("cross", {
          category: "Math",
          documentation: "Cross product.",
          documentationSourceName: "Vec3.kt",
          documentationSourcePath: "src/jvmMain/kotlin/afield/math/Vec3.kt",
          documentationSourceLine: 42,
          receiverDisplayName: "Vec3",
          receiverDocumentation: "A three component vector.",
          receiverDocumentationSourceName: "Vec3.kt",
          receiverDocumentationSourcePath: "src/jvmMain/kotlin/afield/math/Vec3.kt",
          receiverDocumentationSourceLine: 7,
        }),
      ],
    });
    const html = doc();
    ok(/class="afield-suggest-source-link"/.test(html), `no source link:\n${html}`);
    ok(/field-source-view:\?path=/.test(html), `source link is not a field-source-view: URL:\n${html}`);
    ok(/line=42/.test(html), `source link lost its line:\n${html}`);
    ok(/<code>Vec3.kt:42<\/code>/.test(html), `source footer label:\n${html}`);
    ok(/About Vec3/.test(html), `receiver section title:\n${html}`);
    ok(html.includes("A three component vector."), `receiver documentation:\n${html}`);
  },

  // ---- accepting ---------------------------------------------------------------------------
  "accepting inserts the text, records recency and badges the signature": async () => {
    const { controller, editor, model } = await openPopup({
      code: "val n = cros|",
      metadata: [
        candidate("cross", {
          displayText: "cross(other: Vec3)",
          text: "cross()",
          tail: ": Vec3",
          category: "Math",
          icon: "method",
        }),
      ],
    });
    ok(controller.accept(), "accept() should report that it inserted something");
    await settle();

    eq(model.getValue(), "val n = cross()", "the insertText replaced the typed prefix");
    ok(editor._edits.length === 1, `expected one edit, got ${editor._edits.length}`);
    const stored = JSON.parse(window.localStorage.getItem(RECENCY_KEY) || "{}");
    const keys = Object.keys(stored);
    eq(keys.length, 1, `expected one recency entry, got ${JSON.stringify(stored)}`);
    ok(keys[0].includes("cross()"), `recency key should identify the completion: ${keys[0]}`);
    eq(stored[keys[0]].count, 1, "recency count");

    const badge = window._badges.at(-1);
    ok(badge, "accepting a call should raise a signature badge");
    ok(
      /afield-call-signature-param">other/.test(badge.badge.html),
      `parameter name not highlighted:\n${badge.badge.html}`,
    );
    ok(
      /afield-call-signature-type">Vec3/.test(badge.badge.html),
      `type not highlighted:\n${badge.badge.html}`,
    );
  },

  "an empty answer says No completions rather than spinning forever": async () => {
    const { list } = await openPopup({ code: "val x = zzq|", metadata: [] });
    // Quick stubs are only replaced once real items arrive, so an empty answer to a prefix that
    // matches none of them is the case that must land on the empty state.
    ok(/afield-suggest-empty/.test(list()), `expected the empty state, got:\n${list()}`);
    ok(!/afield-suggest-spinner/.test(list()), `still spinning after the server answered:\n${list()}`);
  },

  // ---- what the popup does while it is still waiting ------------------------------------------
  "a slow AI round trip never holds up the list": async () => {
    // This is the behaviour fieldSuggest.js's own comment in renderItems is about: AI completions
    // cost a model round trip, so the list must render on the fast base answer alone.
    const opened = await openPopup({
      code: "val v = ve|",
      metadata: [candidate("vec", { category: "Math", tail: ": Vec3" })],
      aiMetadata: [candidate("vecFromMouse", { category: "Math", tail: ": Vec3" })],
      deferred: ["complete-kotlin-ai"],
    });
    eq(labelsOf(opened.list()), ["vec"], "the base answer renders on its own");
    ok(!/spinner/.test(opened.list()), "must not spin while only the AI answer is outstanding");

    eq(flush("complete-kotlin-ai"), 1, "expected one parked AI reply");
    await settle();
    eq(sectionsOf(opened.list()), ["Math", "AI"], "AI items arrive in their own group, below");
    eq(labelsOf(opened.list()), ["vec", "vecFromMouse"], "and the base item keeps its place");
  },

  "quick stubs stand in until the compiler answers, then get out of the way": async () => {
    const opened = await openPopup({
      code: "val v = ve|",
      metadata: [candidate("vec", { category: "Math", tail: ": Vec3", icon: "method" })],
      deferred: ["complete"],
    });
    // The hard-coded KOTLIN_QUICK_COMPLETIONS fill the wait; `vec` is one of them.
    const stub = opened.controller.items.find((item) => item.label === "vec");
    ok(stub, `expected a quick stub for vec, got ${JSON.stringify(labelsOf(opened.list()))}`);
    eq(stub.source, "quick", "before the compiler answers, the row is a quick stub");
    eq(stub.detail, "", "a quick stub has no return type to show");

    eq(flush("complete"), 1, "expected one parked base reply");
    await settle();
    const real = opened.controller.items.find((item) => item.label === "vec");
    eq(real.source, "rpc", "the compiler's answer replaces the stub");
    eq(real.detail, ": Vec3", "and it brings the return type with it");
    eq(
      opened.controller.items.filter((item) => item.source === "quick").length,
      0,
      "no quick stubs survive alongside real completions",
    );
  },

  // ---- keyboard ------------------------------------------------------------------------------
  "arrow keys move the selection and Escape closes the popup": async () => {
    const opened = await openPopup({
      code: "val v = ve|",
      metadata: [
        candidate("vec", { category: "Math" }),
        candidate("vecLength", { category: "Math" }),
        candidate("vecNormalize", { category: "Math" }),
      ],
    });
    const { press, monaco, controller, activeRow } = opened;
    eq(activeRow().index, 0, "the first row starts active");
    press(monaco.KeyCode.DownArrow);
    eq(activeRow().index, 1, "down arrow");
    press(monaco.KeyCode.DownArrow);
    press(monaco.KeyCode.DownArrow);
    eq(activeRow().index, 2, "down arrow clamps at the last row");
    press(monaco.KeyCode.UpArrow);
    eq(activeRow().index, 1, "up arrow");
    press(monaco.KeyCode.PageUp);
    eq(activeRow().index, 0, "page up clamps at the first row");
    ok(controller.isVisible(), "still open");
    press(monaco.KeyCode.Escape);
    ok(!controller.isVisible(), "escape closes the popup");
  },

  "Enter accepts whatever is selected": async () => {
    const opened = await openPopup({
      code: "val v = ve|",
      metadata: [
        candidate("vec", { category: "Math" }),
        candidate("vecLength", { category: "Math" }),
      ],
    });
    opened.press(opened.monaco.KeyCode.DownArrow);
    opened.press(opened.monaco.KeyCode.Enter);
    await settle();
    eq(opened.model.getValue(), "val v = vecLength", "Enter inserted the selected row");
    ok(!opened.controller.isVisible(), "and closed the popup");
  },

  // ---- the documentation browser --------------------------------------------------------------
  "a [Symbol] link opens a source card, with breadcrumbs and history": async () => {
    const opened = await openPopup({
      code: "val n = cros|",
      metadata: [
        candidate("cross", {
          category: "Math",
          documentation: "Cross product of [Vec3](field-source:?name=Vec3).",
        }),
        candidate("crossSigned", { category: "Math", documentation: "A signed variant." }),
      ],
      resolveSource: (payload) => ({
        found: true,
        title: payload.symbol,
        documentation: "A three component vector.",
        category: "Math",
        sourceName: "Vec3.kt",
        sourcePath: "src/jvmMain/kotlin/afield/math/Vec3.kt",
        sourceLine: 7,
      }),
    });
    ok(opened.doc().includes("Cross product of"), `the completion's own doc first:\n${opened.doc()}`);

    opened.controller.navigateToSource(
      "field-source:?path=src/jvmMain/kotlin/afield/math/Vec3.kt&line=7&name=Vec3",
      "Vec3",
    );
    await settle();
    const card = opened.doc();
    ok(card.includes("A three component vector."), `navigated card body:\n${card}`);
    ok(/class="afield-suggest-nav"/.test(card), `no back/forward control:\n${card}`);
    ok(/cross › Vec3/.test(card), `breadcrumbs should trail from the completion:\n${card}`);
    ok(!/data-nav="back" disabled/.test(card), "back should be live on a card");
    ok(/data-nav="forward" disabled/.test(card), "forward should be dead at the end of history");

    opened.controller.navBack();
    await settle();
    const back = opened.doc();
    ok(back.includes("Cross product of"), `back should return to the completion:\n${back}`);
    ok(/data-nav="back" disabled/.test(back), "back should be dead at the root");

    opened.controller.navForward();
    await settle();
    ok(opened.doc().includes("A three component vector."), "forward should return to the card");

    // Moving the selection abandons the browse — a card about one symbol must not survive onto
    // another.
    opened.controller.setActive(1);
    await settle();
    const moved = opened.doc();
    eq(opened.controller.navStack.length, 0, "changing selection drops the browse history");
    ok(moved.includes("A signed variant."), `expected the newly selected item's doc:\n${moved}`);
    ok(!/class="afield-suggest-nav"/.test(moved), `no stale back/forward control:\n${moved}`);
  },

  "the source footer expands the real source inline, and collapses again": async () => {
    const opened = await openPopup({
      code: "val n = cros|",
      metadata: [
        candidate("cross", {
          category: "Math",
          documentation: "Cross product.",
          documentationSourceName: "Vec3.kt",
          documentationSourcePath: "src/jvmMain/kotlin/afield/math/Vec3.kt",
          documentationSourceLine: 42,
        }),
      ],
    });
    window._httpRoutes.set(
      "/api/source",
      JSON.stringify({
        found: true,
        path: "src/jvmMain/kotlin/afield/math/Vec3.kt",
        startLine: 40,
        language: "kotlin",
        code: "fun Vec3.cross(other: Vec3): Vec3 = Vec3(\n    y * other.z - z * other.y,\n)",
      }),
    );
    const anchor = opened.docNode().querySelector(".afield-suggest-source-link");
    ok(anchor, `no source link to click:\n${opened.doc()}`);

    opened.controller.viewSource("field-source-view:?path=src/x/Vec3.kt&line=42", anchor);
    await settle();
    ok(
      window._httpRequests.some((url) => url.startsWith("/api/source?")),
      `expected a GET /api/source, saw ${JSON.stringify(window._httpRequests)}`,
    );
    ok(/context=60/.test(window._httpRequests[0]), `expected surrounding context: ${window._httpRequests[0]}`);
    // The popup appends this block as an element rather than rewriting the pane's innerHTML, so read
    // it off the node.
    const block = opened.docNode().querySelector(".afield-suggest-source-code");
    ok(block, "the inline source block was not inserted");
    ok(/language-kotlin/.test(block.innerHTML), `expected a highlightable block:\n${block.innerHTML}`);
    ok(/Vec3.kt:40/.test(block.innerHTML), `expected the path and first line:\n${block.innerHTML}`);
    ok(!/<\/code>fun/.test(block.innerHTML), "source must be escaped, not spliced in as markup");

    opened.controller.viewSource("field-source-view:?path=src/x/Vec3.kt&line=42", anchor);
    await settle();
    ok(
      !opened.docNode().querySelector(".afield-suggest-source-code"),
      "clicking again should collapse the inline source",
    );
  },

  // ---- volume ---------------------------------------------------------------------------------
  "a huge answer is capped, and duplicates are shown once": async () => {
    const many = [];
    for (let i = 0; i < 140; i++) {
      many.push(candidate(`item${String(i).padStart(3, "0")}`, { category: "Math" }));
    }
    many.push(candidate("item000", { category: "Math" })); // an exact duplicate
    const { list, controller } = await openPopup({ code: "val x = item|", metadata: many });
    eq(rowsOf(list()).length, 80, "MAX_VISIBLE_ITEMS rows");
    eq(
      controller.items.filter((item) => item.label === "item000").length,
      1,
      "the duplicate is shown once",
    );
  },

  // ---- characterization: today's behaviour, written down because it is surprising -------------
  "CHARACTERIZATION: the category weights predate the server's category vocabulary": async () => {
    // CATEGORY_WEIGHT keys the *old* client-side buckets (local/graphics/functions/values/…), so of
    // the categories the Kotlin side now sends only `Graphics` scores at all: `Math`, `Data`,
    // `Polytopes`, `Audio AI`, `ALife`, `Solvers`, `Imaging`, `Workspace`, `Function`, `Kotlin`,
    // `External` all fall through to 0 — the same weight as `other`, and below `imports` (10) and
    // `snippets` (8). The visible consequence is asserted here rather than argued about: at equal
    // fuzzy score, an import outranks a Math function.
    //
    // This is a characterization, NOT an endorsement. If you are here because you rebalanced the
    // weights, this expectation is what you came to change.
    const { list } = await openPopup({
      code: "val l = line|",
      metadata: [
        candidate("line", { category: "Math", tail: ": Double" }),
        candidate("line", { category: "Imports" }),
      ],
    });
    eq(sectionsOf(list()), ["Imports", "Math"], "today: Imports (10) outranks Math (unweighted)");
  },
};

// ---------------------------------------------------------------------------- category guard

/**
 * RANK mode: where does the answer the author actually wrote land among the rows a human can SEE?
 *
 * Every other measurement of completion in this repo asks whether the right candidate is in the
 * response. The popup renders at most `MAX_VISIBLE_ITEMS` (80) of them, so "in the payload" and "on
 * screen" are different questions and only the second is the user's. This mode replays REAL recorded
 * `/complete` payloads (`rank-payloads.json`, whose rows the Kotlin side has run through the server's
 * own `classifyMetadata`, so the categories are the ones a browser would receive) and reports the
 * truth's rank in the rendered list, the number of visible rows, and the ranking wall time.
 *
 * Each case records the rank observed when it was cut. A case that gets worse fails. A case that gets
 * BETTER also fails, asking for the fixture to be re-cut — the same ratchet shape as the caret-recall
 * floors, and for the same reason: an improvement nobody records is an improvement the next change can
 * silently undo.
 */
const runRankGuard = async (specPath) => {
  const spec = JSON.parse(readFileSync(specPath, "utf8"));
  const failures = [];
  console.log(
    "  " + "case".padEnd(22) + "sent".padStart(6) + "visible".padStart(9) +
    "rank".padStart(10) + "was".padStart(10) + "ms".padStart(7)
  );
  for (const c of spec.cases) {
    const t0 = process.hrtime.bigint();
    const { list } = await openPopup({ code: c.code, metadata: c.metadata });
    const ms = Number(process.hrtime.bigint() - t0) / 1e6;
    const rows = labelsOf(list());
    const index = rows.findIndex((row) => row === c.truth || row.startsWith(c.truth));
    const rank = index < 0 ? null : index + 1;
    const was = c.observedRank == null ? null : c.observedRank;
    const show = (v) => (v === null ? "not shown" : String(v));
    console.log(
      "  " + c.label.padEnd(22) + String(c.metadata.length).padStart(6) +
      String(rows.length).padStart(9) + show(rank).padStart(10) + show(was).padStart(10) +
      ms.toFixed(1).padStart(7)
    );
    // `FIELD_RANK_DUMP=<n>` prints the first n rows a person would actually see, for the case named by
    // `FIELD_RANK_DUMP_CASE` (default: all). A rank on its own says the answer moved without saying what
    // moved above it, which is not enough to tell a ranking bug from a ranking decision.
    const dumpRows = Number(process.env.FIELD_RANK_DUMP) || 0;
    const dumpCase = process.env.FIELD_RANK_DUMP_CASE || "";
    if (dumpRows > 0 && (!dumpCase || c.label === dumpCase)) {
      console.log(`    -- ${c.label}: first ${Math.min(dumpRows, rows.length)} of ${rows.length} visible`);
      rows.slice(0, dumpRows).forEach((row, i) => {
        const m = c.metadata.find((x) => (x.displayText || x.text) === row);
        const bits = m
          ? `category=${JSON.stringify(m.category || "")} commonMemberRank=${m.commonMemberRank || 0}`
          : "(no matching payload row)";
        console.log(`    ${String(i + 1).padStart(4)}  ${row.padEnd(44)} ${bits}`);
      });
    }
    if (was === null && rank !== null) {
      failures.push(
        `${c.label}: \`${c.truth}\` is now VISIBLE at rank ${rank}, where the fixture records it as ` +
        `off-screen. That is an improvement — re-cut rank-payloads.json's observedRank so the gate holds it.`
      );
    } else if (was !== null && rank === null) {
      failures.push(
        `${c.label}: \`${c.truth}\` was visible at rank ${was} and is now OFF-SCREEN ` +
        `(${rows.length} rows shown of ${c.metadata.length} sent) — a person typing cannot see it.`
      );
    } else if (was !== null && rank > was) {
      failures.push(`${c.label}: \`${c.truth}\` fell from rank ${was} to ${rank} of ${rows.length} visible rows`);
    }
  }
  for (const message of failures) console.log(`FAIL rank: ${message}`);
  if (failures.length === 0) console.log("PASS rank: every recorded answer is where it was recorded");
  return failures.length;
};

// ---------------------------------------------------------------------------- rank, at corpus scale

/**
 * RANKMETRICS mode: the same question as RANK, over the whole caret corpus, with the terms separated.
 *
 * A rank per case is a ratchet; a rank per *population* is what a weighting decision needs — and the
 * commit that fixed completion categories closed by saying that whether `CATEGORY_WEIGHT` should learn
 * the server's vocabulary "is a decision and not a measurement". So this replays every caret in
 * `rank-corpus.tsv.gz` (recorded from the sidecar's own `/complete`; see `tools/record_rank_corpus.py`)
 * and reports recall, MRR and median rank — split by caret form, because a bare `expr.` and a typed
 * `expr.ab` are different questions and one mean over both hides the bare dot inside the prefix.
 *
 * ### Isolating the section effect from the weight effect
 *
 * `filterAndLimit` cuts to `MAX_VISIBLE_ITEMS` on the **flat** ranked list and only then calls
 * `orderItemsForGroupedDisplay`. So sectioning is a permutation *within* the visible rows — it never
 * decides which rows are visible — and `rankItems` writes its own `score` onto every item it returns.
 * That makes the decomposition exact, with no re-implementation of the ranker:
 *
 *   flat rank     position in `controller.items` re-sorted by descending `score`  (weights only)
 *   visible rank  position in `controller.items` as rendered                      (weights + sections)
 *   neutral rank  the same case replayed with every category replaced by one constant, which both
 *                 collapses the sections into one group and ties every `categoryScore` at 0
 *
 *   visible − flat     what SECTIONING costs the answer
 *   neutral − flat     what CATEGORY WEIGHT buys it
 *
 * The rendered row count and `controller.items` are checked to agree per case: if they ever diverge,
 * the flat-rank recovery is reading the wrong array and every number below is wrong the same way.
 */

/** The category keys `CATEGORY_WEIGHT` scores above zero, read out of the staged fieldSuggest.js.
 *  Read rather than listed: this mode exists to count how many real completions land in a category the
 *  table does not know, and a hand-copy would keep agreeing with itself after a rebalance. */
const WEIGHT_KEYS = new Set(
  [...(readFileSync(new URL("./fieldSuggest.js", import.meta.url), "utf8")
    .match(/const CATEGORY_WEIGHT = new Map\(\[[\s\S]*?\]\);/) || [""])[0]
    .matchAll(/\["([^"]+)",\s*(-?\d+)\]/g)]
    .filter((m) => Number(m[2]) > 0)
    .map((m) => m[1]),
);

// Floors, not printouts. Each sits a little below the figure measured when this was cut (the run is in
// dev_docs/completion_rank_at_scale.md), so a real regression fails and noise does not. A number
// printed without a floor near it is how this project once reported 98.6% behind an assertion that
// would have passed at 6%.
const METRIC_FLOORS = {
  // Of the carets where the popup had to leave something out, how many still show the answer.
  // Measured 2026-08-17: prefixed 0.822, bare 0.703.
  "prefixed contested recall": 0.78,
  "bare contested recall": 0.66,
  // Mean reciprocal visible rank over every caret of that form, an absent answer counting 0.
  // Measured 2026-08-17: prefixed 0.647, bare 0.124.
  "prefixed mrr": 0.61,
  "bare mrr": 0.11,
  // Split by whether this tree DECLARES the receiver, because the global bare figure averages two
  // populations that are a factor of ten apart and the deferred decision was about the second one.
  // Measured 2026-08-17: owned 0.892 / 0.219, foreign 0.540 / 0.022.
  "bare-owned contested recall": 0.85,
  "bare-owned mrr": 0.20,
  "bare-foreign contested recall": 0.50,
  "bare-foreign mrr": 0.018,
};
// Sectioning pushing the answer down by more than this in the median means a category assignment has
// broken again the way `FLine.moveTo` filed under Math did.
const MEDIAN_SECTION_SHIFT_CEILING = 8;

const median = (values) => {
  if (!values.length) return null;
  const s = [...values].sort((a, b) => a - b);
  const mid = s.length >> 1;
  return s.length % 2 ? s[mid] : (s[mid - 1] + s[mid]) / 2;
};

const p90 = (values) => {
  if (!values.length) return null;
  const s = [...values].sort((a, b) => a - b);
  return s[Math.min(s.length - 1, Math.floor(0.9 * s.length))];
};

/**
 * Is this rendered label the member the author wrote?
 *
 * Deliberately stricter than RANK mode's `row === truth || row.startsWith(truth)`. A bare `startsWith`
 * accepts `mapNotNull(…)` as an answer of `map`, `toIntOrNull()` as `toInt`, and `lengthSquared` as
 * `length` — and the corpus is full of all three, so at 578 carets a loose predicate is not a rounding
 * error, it is a recall figure that cannot fail. A rendered label is the popup's `displayText`, which is
 * either the bare name of a property or `name(params…)` for a callable, so the exact rule is available.
 */
const isTruthLabel = (label, truth) => label === truth || label.startsWith(`${truth}(`);

/** Rank of `truth` among row labels, 1-based, or null. */
const rankOfTruth = (labels, truth) => {
  const index = labels.findIndex((row) => isTruthLabel(row, truth));
  return index < 0 ? null : index + 1;
};

const weightKeyOf = (category) => String(category || "other").trim().toLowerCase() || "other";

const runRankMetrics = async (specPath) => {
  const spec = JSON.parse(readFileSync(specPath, "utf8"));
  const failures = [];
  const rows = [];
  let mismatchedItems = 0;
  for (const c of spec.cases) {
    const shipped = await openPopup({ code: c.code, metadata: c.metadata });
    const items = shipped.controller.items;
    const rendered = labelsOf(shipped.list());
    if (rendered.length !== items.length) mismatchedItems++;
    const flat = [...items]
      .sort((a, b) => Number(b.score) - Number(a.score))
      .map((i) => String(i.displayText || i.text || ""));

    // The same payload with the category signal removed entirely: one group, every categoryScore 0.
    const neutral = await openPopup({
      code: c.code,
      metadata: c.metadata.map((m) => ({ ...m, category: "uncategorised" })),
    });

    // The same predicate the rank uses, so "(none)" below means exactly "the answer is not in the
    // payload at all" and cannot quietly mean "the matcher disagreed with itself".
    const truthRow = c.metadata.find((m) => isTruthLabel(String(m.displayText || m.text || ""), c.truth));
    rows.push({
      label: c.label,
      form: c.form,
      truth: c.truth,
      receiver: String(c.receiver || ""),
      // The decision `69ca4d5cb` deferred was about *foreign* receivers specifically: "a foreign
      // receiver you cannot annotate has no prior and no weight, so a bare dot on it is alphabetical".
      // A receiver this tree declares can carry `@CommonMembers` and a `@file:CompletionCategory`;
      // `java.lang.Math` and `kotlin.collections.List` cannot. Split, so a real effect on one
      // population cannot average against no effect on the other.
      owned: String(c.receiver || "").startsWith("afield."),
      sent: c.metadata.length,
      // Payload recall — the number every other completion measurement in this tree reports. Kept
      // beside the visible rank on purpose: 96% of it once hid an answer at index 159 of 249.
      present: truthRow !== undefined,
      contested: c.metadata.length > rendered.length,
      visibleRank: rankOfTruth(rendered, c.truth),
      flatRank: rankOfTruth(flat, c.truth),
      neutralRank: rankOfTruth(labelsOf(neutral.list()), c.truth),
      truthCategory: (truthRow || {}).category || "",
      unweightedRows: c.metadata.filter((m) => !WEIGHT_KEYS.has(weightKeyOf(m.category))).length,
    });
  }

  if (mismatchedItems > 0) {
    failures.push(
      `${mismatchedItems} cases render a different number of rows than \`controller.items\` holds, so the ` +
        `flat-rank recovery is reading the wrong array and every decomposition below is invalid`,
    );
  }

  const byForm = {};
  const report = (name, subset) => {
    const found = subset.filter((r) => r.visibleRank !== null);
    const contested = subset.filter((r) => r.contested);
    const contestedFound = contested.filter((r) => r.visibleRank !== null);
    const mrr = subset.length
      ? subset.reduce((sum, r) => sum + (r.visibleRank ? 1 / r.visibleRank : 0), 0) / subset.length
      : 0;
    // Both decompositions are heavy-tailed — a median of 0 with 40 cases moved by 30 rows is a real
    // effect that a median alone denies — so each reports how many cases it moves at all, and by how
    // much at the 90th percentile of those it moves.
    const shifts = found.filter((r) => r.flatRank !== null).map((r) => r.visibleRank - r.flatRank);
    const gains = found.filter((r) => r.neutralRank !== null).map((r) => r.neutralRank - r.flatRank);
    // The sectioning counterfactual: what ranking ACROSS the sections instead of grouping into them
    // would give. It is the flat order, which the shipped ranker already produced — so this costs
    // nothing to measure and it is not a model of a redesign, it is the redesign's own numbers.
    const flatMrr = subset.length
      ? subset.reduce((sum, r) => sum + (r.flatRank ? 1 / r.flatRank : 0), 0) / subset.length
      : 0;
    const tail = (values) => {
      const moved = values.filter((v) => v !== 0);
      return { moved: moved.length, p90: p90(moved.map(Math.abs)) };
    };
    const section = tail(shifts);
    const weight = tail(gains);
    const m = {
      cases: subset.length,
      inPayload: subset.filter((r) => r.present).length / Math.max(1, subset.length),
      contested: contested.length,
      recall: found.length / Math.max(1, subset.length),
      contestedRecall: contestedFound.length / Math.max(1, contested.length),
      mrr,
      flatMrr,
      medianFlatRank: median(found.filter((r) => r.flatRank !== null).map((r) => r.flatRank)),
      medianRank: median(found.map((r) => r.visibleRank)),
      medianSectionShift: median(shifts),
      medianWeightGain: median(gains),
      section,
      weight,
    };
    console.log(
      `  ${name.padEnd(13)}${String(m.cases).padStart(6)}${m.inPayload.toFixed(3).padStart(10)}` +
        `${String(m.contested).padStart(11)}${m.recall.toFixed(3).padStart(8)}` +
        `${m.contestedRecall.toFixed(3).padStart(10)}${m.mrr.toFixed(3).padStart(6)}` +
        `${m.flatMrr.toFixed(3).padStart(8)}${String(m.medianRank ?? "-").padStart(8)}` +
        `${String(m.medianFlatRank ?? "-").padStart(8)}` +
        `${`${m.medianSectionShift ?? "-"}/${section.moved}/${section.p90 ?? "-"}`.padStart(12)}` +
        `${`${m.medianWeightGain ?? "-"}/${weight.moved}/${weight.p90 ?? "-"}`.padStart(12)}`,
    );
    byForm[name] = m;
    return m;
  };

  console.log(
    `\n  ${spec.cases.length} carets, ${spec.corpus.rows} distinct recorded rows, wire limit ${spec.corpus.limit}`,
  );
  console.log("  (section / weight columns read median / cases moved / p90 of those moved)");
  console.log("  (flatMrr / flatMed are the sectioning counterfactual: rank ACROSS sections, not into them)");
  console.log(
    "  " + "form".padEnd(13) + "cases".padStart(6) + "inPayload".padStart(10) + "contested".padStart(11) +
      "recall".padStart(8) + "c-recall".padStart(10) + "mrr".padStart(6) + "flatMrr".padStart(8) +
      "medRank".padStart(8) + "flatMed".padStart(8) + "section".padStart(12) + "weight".padStart(12),
  );
  report("prefixed", rows.filter((r) => r.form === "prefixed"));
  report("bare", rows.filter((r) => r.form === "bare"));
  report("all", rows);
  // Split the other way. The deferred decision is about a bare dot on a receiver nobody here can
  // annotate, so that is the population it has to be measured on.
  report("bare-owned", rows.filter((r) => r.form === "bare" && r.owned));
  report("bare-foreign", rows.filter((r) => r.form === "bare" && !r.owned && r.receiver !== ""));

  // The two rows the deferred decision names, by name, because they are the two a reader will look for.
  console.log("\n  the two receivers `69ca4d5cb` deferred, caret by caret");
  for (const receiver of ["java.lang.Math", "kotlin.collections.List", "java.util.List"]) {
    const named = rows.filter((r) => r.receiver === receiver);
    if (!named.length) continue;
    const bare = named.filter((r) => r.form === "bare");
    console.log(
      `    ${receiver}: ${named.length} carets (${bare.length} bare), ` +
        `visible recall ${(named.filter((r) => r.visibleRank !== null).length / named.length).toFixed(3)}, ` +
        `median rank ${median(named.filter((r) => r.visibleRank !== null).map((r) => r.visibleRank)) ?? "-"}`,
    );
    // Bare first: the prefixed form was never the broken case, and it is the bare one the
    // deferred decision is about.
    for (const r of [...bare, ...named.filter((r) => r.form !== "bare")].slice(0, 8)) {
      console.log(
        `      ${r.form.padEnd(9)}${String(r.truth).padEnd(18)}sent ${String(r.sent).padStart(4)}` +
          `   visible ${String(r.visibleRank ?? "not shown").padStart(9)}` +
          `   flat ${String(r.flatRank ?? "-").padStart(9)}`,
      );
    }
  }

  // Per-category, over the carets whose ANSWER carries that category — the breakdown a rebalance needs.
  const byCategory = new Map();
  for (const r of rows) {
    // Two different nothings, kept apart. `(absent)` is a caret whose answer the server never sent —
    // the recall harness's problem, not the ranker's. `(uncategorised)` is an answer the server DID
    // send and `classifyMetadata` gave no category to, which is a classification gap and not a
    // weighting one; conflating them made one bucket of 168 look like the worst-ranked category here.
    const key = r.truthCategory || (r.present ? "(uncategorised)" : "(absent)");
    const bucket = byCategory.get(key) || { cases: 0, found: 0, ranks: [], weighted: WEIGHT_KEYS.has(weightKeyOf(key)) };
    bucket.cases++;
    if (r.visibleRank !== null) {
      bucket.found++;
      bucket.ranks.push(r.visibleRank);
    }
    byCategory.set(key, bucket);
  }
  console.log("\n  the answer's own category (w = CATEGORY_WEIGHT scores it above zero)");
  console.log(
    "  " + "category".padEnd(22) + "w".padStart(3) + "cases".padStart(7) + "recall".padStart(8) +
      "medRank".padStart(9),
  );
  const ordered = [...byCategory.entries()].sort((a, b) => b[1].cases - a[1].cases);
  for (const [name, b] of ordered) {
    if (b.cases < 3) continue;
    console.log(
      "  " + name.padEnd(22) + (b.weighted ? "w" : "·").padStart(3) + String(b.cases).padStart(7) +
        (b.found / b.cases).toFixed(3).padStart(8) + String(median(b.ranks) ?? "-").padStart(9),
    );
  }
  const unweightedCases = [...byCategory.values()].filter((b) => !b.weighted).reduce((n, b) => n + b.cases, 0);
  const totalRows = rows.reduce((n, r) => n + r.sent, 0);
  const unweightedRows = rows.reduce((n, r) => n + r.unweightedRows, 0);
  console.log(
    `\n  CATEGORY_WEIGHT exposure: ${unweightedRows}/${totalRows} recorded rows ` +
      `(${((100 * unweightedRows) / totalRows).toFixed(1)}%) and ${unweightedCases}/${rows.length} answers ` +
      `carry a category the client scores 0`,
  );

  for (const [name, floor] of Object.entries(METRIC_FLOORS)) {
    const form = name.split(" ")[0];
    const metric = name.endsWith("mrr") ? "mrr" : "contestedRecall";
    const value = byForm[form][metric];
    if (!(value >= floor)) failures.push(`${name} is ${value.toFixed(3)}, below its floor of ${floor}`);
  }
  // The sectioning counterfactual is only a counterfactual about ORDER, and this asserts why: the
  // visible set is decided before `orderItemsForGroupedDisplay` ever runs, so an answer is visible in
  // the flat order exactly when it is visible in the grouped one. If someone moves the
  // `.slice(MAX_VISIBLE_ITEMS)` to after the grouping, sectioning starts deciding *visibility* and this
  // fails — which is the whole argument for "collapsing sections recovers none of the missing answers".
  const flatOnly = rows.filter((r) => (r.visibleRank === null) !== (r.flatRank === null));
  if (flatOnly.length > 0) {
    failures.push(
      `${flatOnly.length} answers are visible in one of the flat/grouped orders and not the other ` +
        `(e.g. ${flatOnly[0].label}). Sectioning is supposed to permute the visible rows, not choose ` +
        `them — so either filterAndLimit now cuts after grouping, or this decomposition is wrong.`,
    );
  }
  if (byForm.all.medianSectionShift > MEDIAN_SECTION_SHIFT_CEILING) {
    failures.push(
      `sectioning now pushes the answer down by a median of ${byForm.all.medianSectionShift} rows ` +
        `(ceiling ${MEDIAN_SECTION_SHIFT_CEILING}) — a category assignment has broken the way FLine.moveTo did`,
    );
  }
  for (const message of failures) console.log(`FAIL rankmetrics: ${message}`);
  if (failures.length === 0) console.log("PASS rankmetrics: every population floor holds");
  return failures.length;
};

/**
 * The class-level guard behind the "Audio Ai" defect: *every* category the Kotlin side can put on a
 * completion must render its own spelling in the popup header. The expectations come from Kotlin
 * (see FieldSuggestPresentationTest.kt), which enumerates the vocabulary from the source tree, so a
 * newly declared `@file:CompletionCategory("Two Words")` fails here until it is mapped.
 */
const runCategoryGuard = async (specPath) => {
  const spec = JSON.parse(readFileSync(specPath, "utf8"));
  const failures = [];
  for (const { category, expected, origin } of spec.categories) {
    const { list } = await openPopup({
      code: "val x = wid|",
      metadata: [candidate("widget", { category })],
    });
    const sections = sectionsOf(list());
    if (sections.length !== 1) {
      failures.push(`${category}: expected one header, got ${JSON.stringify(sections)}`);
      continue;
    }
    if (sections[0] !== expected) {
      failures.push(
        `${category} (${origin}) renders as "${sections[0]}", expected "${expected}" — ` +
          `add it to titleForCategory's \`known\` map in fieldSuggest.js`,
      );
    }
  }
  console.log(`checked ${spec.categories.length} completion categories`);
  for (const failure of failures) console.log(`FAIL ${failure}`);
  return failures.length;
};

// ---------------------------------------------------------------------------- main

const mode = process.argv[2] || "cases";
const specPath = process.argv[3];
let failed = 0;

if (mode === "cases" || mode === "all") {
  for (const [name, body] of Object.entries(CASES)) {
    try {
      const result = await body();
      if (typeof result === "string") throw new Error(result);
      console.log(`PASS ${name}`);
    } catch (error) {
      failed++;
      console.log(`FAIL ${name}: ${error && error.stack ? error.stack : error}`);
    }
  }
}
if (mode === "categories" || mode === "all") {
  if (!specPath) {
    console.log("FAIL categories mode needs a path to the category spec json");
    failed++;
  } else {
    failed += await runCategoryGuard(specPath);
  }
}
if (mode === "rank") {
  if (!specPath) {
    console.log("FAIL rank mode needs a path to the recorded-payload spec json");
    failed++;
  } else {
    failed += await runRankGuard(specPath);
  }
}
if (mode === "rankmetrics") {
  if (!specPath) {
    console.log("FAIL rankmetrics mode needs a path to the wide-corpus spec json");
    failed++;
  } else {
    failed += await runRankMetrics(specPath);
  }
}

if (failed > 0) {
  console.log(`field-suggest presentation probe FAILED: ${failed} problem(s)`);
  process.exit(1);
}
console.log("field-suggest presentation probe passed");
