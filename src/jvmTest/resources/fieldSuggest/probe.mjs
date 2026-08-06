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
import { rpc, requests } from "./afield.mjs";
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
  rpc.set("complete", () => completionReply(metadata, caret - prefix.length, caret));
  rpc.set("complete-kotlin-ai", () => completionReply(aiMetadata, caret - prefix.length, caret));
  if (resolve) rpc.set("resolve-completion", resolve);

  const controller = installFieldSuggest(editor, makeMonaco());
  await controller.open({ manual: true });
  await settle();

  const list = () => controller.root.querySelector(".afield-suggest-list").innerHTML;
  const doc = () => controller.root.querySelector(".afield-suggest-doc").innerHTML;
  return { controller, editor, model, position, caret, list, doc };
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
    const { list } = await openPopup({
      code: "val x = |",
      metadata: [
        candidate("gridA", { category: "Graphics", contextScore: 0 }),
        candidate("importB", { category: "Imports", contextScore: 50 }),
        candidate("gridC", { category: "Graphics", contextScore: 40 }),
        candidate("importD", { category: "Imports", contextScore: 0 }),
      ],
    });
    const html = list();
    eq(sectionsOf(html), ["Graphics", "Imports"], "one header per category, in first-ranked order");
    eq(labelsOf(html), ["gridC", "gridA", "importB", "importD"], "rows grouped, ranked within group");
    eq(
      readList(html).map((e) => e.kind),
      ["section", "row", "row", "section", "row", "row"],
      "header then its rows",
    );
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
};

// ---------------------------------------------------------------------------- category guard

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

if (failed > 0) {
  console.log(`field-suggest presentation probe FAILED: ${failed} problem(s)`);
  process.exit(1);
}
console.log("field-suggest presentation probe passed");
