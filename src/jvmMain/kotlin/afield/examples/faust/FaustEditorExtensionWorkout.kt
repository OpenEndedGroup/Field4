package afield.examples.faust

import afield.four.synth.FaustEditorExtension
import afield.util.NodeTool
import java.nio.file.Files
import kotlin.io.path.writeText

/*
FIELD_TEST
id: faust.editor-extension.workout
runner: jvm-main
entrypoint: afield.examples.faust.FaustEditorExtensionWorkout
tags: [smoke, examples, faust, monaco, language-extension]
timeoutSec: 90
expect:
  exitCode: 0
  stdoutContains:
    - "faust editor extension workout passed"
*/
/**
 * Runs the shipped `faust.js` under node against the shipped vocabulary.
 *
 * Two contracts are being held here, and both are the kind that fail silently in a browser.
 *
 * The first is between `tools/generate_faust_vocabulary.py` and the extension: the vocabulary is
 * loaded from the real generated JSON rather than a fixture, so if the generator ever changes shape —
 * renames `usage`, stops grouping by `prefix` — completion goes quiet and this fails, instead of the
 * editor politely offering nothing.
 *
 * The second is the line invariant. Faust has no `#` comment, so unlike Sonic Pi the `#faust` marker
 * cannot stay in the program: `FaustEngine` strips it and adds the offset back when it places an error
 * marker. That means `faust.js` must exclude the header from the region body *and* agree with
 * `stripFaustHeader` about how many lines went. If those two ever disagree every diagnostic lands on
 * the wrong row, which looks like a compiler bug rather than an editor one — so it is asserted
 * directly, from both ends.
 */
object FaustEditorExtensionWorkout {
    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "faust editor extension workout failed" }
    }

    fun runTests(): Boolean {
        val js = FaustEditorExtension.javascript()
        require("FieldLanguageExtensions.register" in js)
        require("#faust" in js)
        require("updateEmbeddedHighlight" in js)
        require("updateDiagnostics" in js)
        require("buildContextPayload" in js)
        require("provideCompletionItems" in js)
        require("registerFaustLanguage" in js)
        require("setLanguageConfiguration" in js)
        require("faust-vocabulary.json" in js)

        val vocabulary = FaustEditorExtension.vocabularyJson()
        require("\"functions\"" in vocabulary) { "the vocabulary has no functions" }
        require("\"prefixes\"" in vocabulary) { "the vocabulary has no library prefixes" }
        require("\"primitives\"" in vocabulary) { "the vocabulary has no primitives" }

        runNodeProbe(js, vocabulary)
        println("faust editor extension workout passed")
        return true
    }

    private fun runNodeProbe(extensionSource: String, vocabularyJson: String) {
        val dir = Files.createTempDirectory("faust-editor-extension-")
        val extensionFile = dir.resolve("faust-extension.js")
        val vocabularyFile = dir.resolve("faust-vocabulary.json")
        val probeFile = dir.resolve("probe.js")
        extensionFile.writeText(extensionSource)
        vocabularyFile.writeText(vocabularyJson)
        probeFile.writeText(nodeProbeSource())

        val node = NodeTool.resolveNode()
            ?: error("faust editor-extension workout needs node: ${NodeTool.unavailableMessage()}")

        val process = ProcessBuilder(node, probeFile.toString(), extensionFile.toString(), vocabularyFile.toString())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        require(exit == 0) { "node probe failed with exit $exit\n$output" }
        require("faust node probe passed" in output) { output }
        output.lineSequence().filter { it.startsWith("probe:") }.forEach { println(it) }
    }

    private fun nodeProbeSource(): String = """
        const fs = require("fs");
        const source = fs.readFileSync(process.argv[2], "utf8");
        const vocabulary = JSON.parse(fs.readFileSync(process.argv[3], "utf8"));
        const registered = [];
        class Range {
          constructor(startLineNumber, startColumn, endLineNumber, endColumn) {
            this.startLineNumber = startLineNumber;
            this.startColumn = startColumn;
            this.endLineNumber = endLineNumber;
            this.endColumn = endColumn;
          }
        }
        const markers = {};
        global.window = {
          FieldLanguageExtensions: {
            register(extension) { registered.push(extension); return extension; }
          },
          globalMonaco: {
            Range,
            MarkerSeverity: { Error: 8, Warning: 4 },
            languages: {
              CompletionItemKind: { Function: 1, Field: 2, Keyword: 3, Module: 4 },
              register() {},
              setLanguageConfiguration() {},
              getLanguages: () => []
            },
            editor: {
              onDidChangeModelLanguage() {},
              setModelMarkers(model, owner, list) { markers[owner] = list; }
            }
          }
        };
        global.setTimeout = (fn) => { fn(); return 0; };
        // No fetch: the extension must fall back cleanly, and the probe injects the real vocabulary
        // itself so this tests the shipped JSON rather than a fixture.
        global.fetch = undefined;
        eval(source);

        if (registered.length !== 1) throw new Error("expected one registered extension, got " + registered.length);
        const extension = registered[0];
        if (extension.id !== "faust") throw new Error("wrong extension id " + extension.id);

        const proof = global.window.__faustExtensionProof;
        if (!proof) throw new Error("no proof object");

        // --- vocabulary ---
        proof.indexVocabulary(vocabulary);
        if (!proof.vocabulary.loaded) throw new Error("vocabulary did not index");
        if (proof.vocabulary.functions.size < 800) {
          throw new Error("only " + proof.vocabulary.functions.size + " functions indexed");
        }
        for (const want of ["os.sawtooth", "fi.lowpass", "en.adsr", "no.noise"]) {
          if (!proof.vocabulary.functions.has(want)) throw new Error("no " + want + " in the vocabulary");
        }
        if (!proof.vocabulary.prefixes.has("os")) throw new Error("no os prefix");
        const oscillators = proof.vocabulary.byPrefix.get("os") || [];
        if (oscillators.length < 40) throw new Error("only " + oscillators.length + " oscillators");
        const saw = proof.vocabulary.functions.get("os.sawtooth");
        if (!saw.usage) throw new Error("os.sawtooth has no signature");
        if (!saw.summary) throw new Error("os.sawtooth has no summary");
        console.log("probe: indexed " + proof.vocabulary.functions.size + " functions across " +
          proof.vocabulary.prefixes.size + " libraries (libfaust " + proof.vocabulary.version + ")");

        // --- the document under test ---
        const lines = [
          'import afield.four.synth.*',
          '',
          'faust(${"\"\"\""}',
          '#faust',
          'import("stdfaust.lib");',
          '// an ordinary comment',
          'freq = hslider("freq [unit:Hz]", 220, 50, 2000, 0.01);',
          'process = os.sawtooth(freq) : fi.lowpass(2, 1200) * 0.2 <: _,_;',
          '${"\"\"\""})'
        ];
        const text = lines.join("\n");

        function positionAt(offset) {
          const before = text.slice(0, offset).split("\n");
          return { lineNumber: before.length, column: before[before.length - 1].length + 1 };
        }
        function offsetAt(position) {
          const all = text.split("\n");
          let offset = 0;
          for (let i = 0; i < position.lineNumber - 1; i++) offset += all[i].length + 1;
          return offset + position.column - 1;
        }
        const model = {
          getLanguageId: () => "kotlin",
          getValue: () => text,
          getVersionId: () => 3,
          getPositionAt: positionAt,
          getOffsetAt: offsetAt,
          getLineContent: (n) => text.split("\n")[n - 1]
        };

        // --- region detection ---
        const regions = proof.findEmbeddedRegions(model);
        if (regions.length !== 1) throw new Error("expected one #faust region, got " + regions.length);
        const region = regions[0];
        const body = text.slice(region.bodyStart, region.bodyEnd);

        // The invariant that matters most: '#' is NOT a comment in Faust, so the marker must be gone
        // from what the compiler sees. If it were included, every program would fail to parse.
        if (/#faust/.test(body)) throw new Error("the #faust header must NOT be in the region body");
        if (!/^import\("stdfaust\.lib"\);/.test(body)) {
          throw new Error("body should start at the first real line, got " + JSON.stringify(body.slice(0, 40)));
        }
        if (text.slice(region.markerStart, region.markerStart + 6) !== "#faust") {
          throw new Error("markerStart is wrong: " + JSON.stringify(text.slice(region.markerStart, region.markerStart + 8)));
        }

        // And the line arithmetic FaustEngine's error markers depend on. Faust reports line N of the
        // stripped body; the box line is that plus the number of lines the header took. Here the
        // literal opens on box line 3, so body line 1 is box line 5.
        const bodyStartLine = positionAt(region.bodyStart).lineNumber;
        if (bodyStartLine !== 5) throw new Error("body should start on box line 5, got " + bodyStartLine);
        const processLineInBody = body.split("\n").findIndex((l) => l.startsWith("process =")) + 1;
        const processLineInBox = positionAt(text.indexOf("process =")).lineNumber;
        if (bodyStartLine + processLineInBody - 1 !== processLineInBox) {
          throw new Error("line arithmetic is off: body " + processLineInBody + " vs box " + processLineInBox);
        }
        console.log("probe: region body starts on box line " + bodyStartLine +
          ", 'process' is body line " + processLineInBody + " / box line " + processLineInBox);

        // --- tokenizing ---
        const tokens = proof.tokenize(body);
        const kindOf = (t) => t.kind + ":" + t.text;
        const all = tokens.map(kindOf);
        if (!all.includes("qualified:os.sawtooth")) {
          throw new Error("os.sawtooth must be one qualified token, got " + all.join(" "));
        }
        if (!all.includes("qualified:fi.lowpass")) throw new Error("fi.lowpass is not one token");
        if (!all.includes("operator:<:")) throw new Error("'<:' must be one operator token, got " + all.join(" "));
        if (!all.some((t) => t.startsWith("comment://"))) throw new Error("no line comment token");
        if (!all.some((t) => t.startsWith("string:"))) throw new Error("no string token");
        if (!all.includes("word:process")) throw new Error("no process word token");
        if (!all.includes("word:hslider")) throw new Error("no hslider word token");

        // Colour classes, which are what a reader actually sees.
        const classFor = (text_) => {
          const t = tokens.find((tok) => tok.text === text_);
          if (!t) throw new Error("no token for " + text_);
          return proof.classForToken(t);
        };
        if (classFor("process") !== "afield-stacksl-token-definition") {
          throw new Error("process should read as a definition, got " + classFor("process"));
        }
        if (classFor("hslider") !== "afield-stacksl-token-modifier") {
          throw new Error("hslider should read as a UI primitive, got " + classFor("hslider"));
        }
        if (classFor("os.sawtooth") !== "afield-stacksl-token-creative") {
          throw new Error("a known library call should read as a call, got " + classFor("os.sawtooth"));
        }
        const unknown = proof.tokenize("process = zz.nope(1);")
          .find((t) => t.text === "zz.nope");
        if (proof.classForToken(unknown) !== "afield-stacksl-token-identifier") {
          throw new Error("an unknown library must not be dressed up as a call");
        }
        console.log("probe: tokenized " + tokens.length + " tokens, qualified names and <: intact");

        // --- lexical diagnostics ---
        const issues = (src) => proof.analyzeLexical(src).map((i) => i.severity + ":" + i.message);
        const clean = proof.analyzeLexical(body);
        if (clean.length !== 0) throw new Error("a valid program should be clean, got " + JSON.stringify(clean));

        const unbalanced = issues('process = os.osc(440;');
        if (!unbalanced.some((m) => m.startsWith("error") && /never closed/.test(m))) {
          throw new Error("an unclosed bracket should be an error, got " + unbalanced.join(" | "));
        }
        const badPrefix = issues('import("stdfaust.lib");\nprocess = zz.nope(1);');
        if (!badPrefix.some((m) => /not a library bound by stdfaust/.test(m))) {
          throw new Error("an unknown library should warn, got " + badPrefix.join(" | "));
        }
        const badMember = issues('import("stdfaust.lib");\nprocess = os.sawtoothX(1);');
        if (!badMember.some((m) => /not a documented function/.test(m))) {
          throw new Error("an unknown member should warn, got " + badMember.join(" | "));
        }
        const noProcess = issues('import("stdfaust.lib");\nfoo = 1;');
        if (!noProcess.some((m) => /No 'process' definition/.test(m))) {
          throw new Error("a program with no process should warn, got " + noProcess.join(" | "));
        }
        // A real program must not be nagged about `process` just because it takes arguments.
        const processWithArgs = issues('import("stdfaust.lib");\nprocess(x) = x;');
        if (processWithArgs.some((m) => /No 'process' definition/.test(m))) {
          throw new Error("process(x) = ... is a definition");
        }
        console.log("probe: diagnostics clean on valid source, and catch 4 kinds of mistake");

        // --- completion ---
        proof.vocabulary.loaded = true;
        const completionsAt = (needle, extra) => {
          const at = text.indexOf(needle) + needle.length;
          return proof.provideCompletionItems(
            { getModel: () => model, hasTextFocus: () => true },
            model, positionAt(at), {}
          );
        };
        // After `os.` the offer must be oscillators, not the whole language.
        const qualified = proof.completionContext(body, body.indexOf("os.sawtooth") + 3);
        if (qualified.kind !== "qualified" || qualified.prefix !== "os") {
          throw new Error("completionContext should see a qualified name, got " + JSON.stringify(qualified));
        }
        const partial = proof.completionContext(body, body.indexOf("os.sawtooth") + 6);
        if (partial.partial !== "saw") throw new Error("partial should be 'saw', got " + partial.partial);

        const osOffers = completionsAt("os.");
        if (!osOffers.length) throw new Error("no completions after os.");
        const osLabels = osOffers.map((s) => s.label);
        if (!osLabels.includes("sawtooth")) throw new Error("os. should offer sawtooth");
        if (osLabels.includes("lowpass")) throw new Error("os. must not offer filters");
        if (osLabels.some((l) => l.includes("."))) throw new Error("os. should offer bare names");
        const sawOffer = osOffers.find((s) => s.label === "sawtooth");
        if (!sawOffer.documentation || !/sawtooth\(freq\)/.test(sawOffer.documentation.value)) {
          throw new Error("os.sawtooth completion should carry its signature");
        }

        // A bare caret offers the language and the libraries.
        const bare = completionsAt("freq = ");
        const bareLabels = bare.map((s) => s.label);
        for (const want of ["hslider", "process", "os", "fi"]) {
          if (!bareLabels.includes(want)) throw new Error("a bare caret should offer " + want);
        }
        const sliderOffer = bare.find((s) => s.label === "hslider");
        if (!/init/.test(sliderOffer.insertText) && !/\$\{2:/.test(sliderOffer.insertText)) {
          throw new Error("hslider should insert a snippet, got " + sliderOffer.insertText);
        }
        const osModule = bare.find((s) => s.label === "os");
        if (osModule.insertText !== "os.") throw new Error("a library should insert with its dot");
        console.log("probe: os. offers " + osOffers.length + " oscillators; a bare caret offers " +
          bare.length + " language items and libraries");

        // --- highlighting and markers run without a real Monaco ---
        const editor = {
          getModel: () => model,
          hasTextFocus: () => true,
          createDecorationsCollection: (d) => ({ set(next) { this.last = next; }, last: d })
        };
        extension.updateEmbeddedHighlight(editor);
        if (!editor._faustHighlightDecorations || !editor._faustHighlightDecorations.last.length) {
          throw new Error("no highlight decorations produced");
        }
        extension.updateDiagnostics(editor);
        if (!("faust-diagnostics" in markers)) throw new Error("diagnostics were never set");
        if (markers["faust-diagnostics"].length !== 0) {
          throw new Error("a valid program should have no markers, got " +
            JSON.stringify(markers["faust-diagnostics"]));
        }

        // --- the context badge ---
        const badgeAt = (needle, offsetInto) => {
          const at = text.indexOf(needle) + (offsetInto === undefined ? 1 : offsetInto);
          return extension.buildContextPayload(
            { getModel: () => model, getPosition: () => positionAt(at), hasTextFocus: () => true },
            null, {}
          );
        };
        const badge = badgeAt("os.sawtooth", 3);
        if (!badge || !/sawtooth/.test(badge.html)) throw new Error("no context badge for os.sawtooth");
        if (!/Faust/.test(badge.html)) throw new Error("the badge should name the language");
        console.log("probe: highlight, markers and context badge all produced");

        console.log("faust node probe passed");
    """.trimIndent()
}
