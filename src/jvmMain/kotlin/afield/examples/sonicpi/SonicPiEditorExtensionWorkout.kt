package afield.examples.sonicpi

import afield.four.synth.SonicPiEditorExtension
import afield.util.NodeTool
import java.nio.file.Files
import kotlin.io.path.writeText

/*
FIELD_TEST
id: sonicpi.editor-extension.workout
runner: jvm-main
entrypoint: afield.examples.sonicpi.SonicPiEditorExtensionWorkout
tags: [smoke, examples, sonicpi, monaco, language-extension]
timeoutSec: 60
expect:
  exitCode: 0
  stdoutContains:
    - "sonicpi editor extension workout passed"
*/
/**
 * Runs the shipped `sonicpi.js` under node against the shipped vocabulary.
 *
 * The vocabulary is loaded from the real generated `sonicpi-vocabulary.json` rather than a fixture,
 * which makes this a contract test between the generator and the extension: if
 * `generate_sonic_pi_vocabulary.py` ever changes shape — renames `optDocs`, stops grouping samples —
 * the completions here go quiet and this fails, instead of the editor quietly offering nothing.
 */
object SonicPiEditorExtensionWorkout {
    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "sonicpi editor extension workout failed" }
    }

    fun runTests(): Boolean {
        val js = SonicPiEditorExtension.javascript()
        require("FieldLanguageExtensions.register" in js)
        require("#sonicpi" in js)
        require("updateEmbeddedHighlight" in js)
        require("buildContextPayload" in js)
        require("provideCompletionItems" in js)
        require("registerSonicPiLanguage" in js)
        require("setLanguageConfiguration" in js)
        require("sonicpi-vocabulary.json" in js)

        val vocabulary = SonicPiEditorExtension.vocabularyJson()
        require("\"fns\"" in vocabulary) { "the vocabulary has no fns" }
        require("\"sampleGroups\"" in vocabulary) { "the vocabulary has no sample groups" }

        runNodeProbe(js, vocabulary)
        println("sonicpi editor extension workout passed")
        return true
    }

    private fun runNodeProbe(extensionSource: String, vocabularyJson: String) {
        val dir = Files.createTempDirectory("sonicpi-editor-extension-")
        val extensionFile = dir.resolve("sonicpi-extension.js")
        val vocabularyFile = dir.resolve("sonicpi-vocabulary.json")
        val probeFile = dir.resolve("probe.js")
        extensionFile.writeText(extensionSource)
        vocabularyFile.writeText(vocabularyJson)
        probeFile.writeText(nodeProbeSource())

        val node = NodeTool.resolveNode()
            ?: error("sonicpi editor-extension workout needs node: ${NodeTool.unavailableMessage()}")

        val process = ProcessBuilder(node, probeFile.toString(), extensionFile.toString(), vocabularyFile.toString())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        require(exit == 0) { "node probe failed with exit $exit\n$output" }
        require("sonicpi node probe passed" in output) { output }
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
        global.window = {
          FieldLanguageExtensions: {
            register(extension) { registered.push(extension); return extension; }
          },
          globalMonaco: {
            Range,
            languages: {
              CompletionItemKind: { Function: 1, Property: 2, EnumMember: 3, Keyword: 4 }
            },
            editor: { onDidChangeModelLanguage() {} }
          }
        };
        global.setTimeout = (fn) => { fn(); return 0; };
        // No fetch: the extension must fall back cleanly, and the probe injects the real
        // vocabulary itself so this tests the shipped JSON rather than a fixture.
        global.fetch = undefined;
        eval(source);

        if (registered.length !== 1) throw new Error("expected one registered extension, got " + registered.length);
        const extension = registered[0];
        if (extension.id !== "sonicpi") throw new Error("wrong extension id " + extension.id);

        const proof = global.window.__sonicpiExtensionProof;
        if (!proof) throw new Error("no proof object");
        proof.indexVocabulary(vocabulary);
        if (!proof.vocabulary.loaded) throw new Error("vocabulary did not index");
        if (proof.vocabulary.fns.size < 100) throw new Error("only " + proof.vocabulary.fns.size + " fns indexed");
        if (!proof.vocabulary.fns.has("live_loop")) throw new Error("no live_loop in the vocabulary");
        if (!proof.vocabulary.synths.has("dsaw")) throw new Error("no dsaw synth");
        if (!proof.vocabulary.fx.has("reverb")) throw new Error("no reverb fx");
        if (!proof.vocabulary.samples.has("bd_haus")) throw new Error("no bd_haus sample");
        console.log("probe: indexed " + proof.vocabulary.fns.size + " fns, " +
          proof.vocabulary.synths.size + " synths, " + proof.vocabulary.fx.size + " fx, " +
          proof.vocabulary.samples.size + " samples (Sonic Pi " + proof.vocabulary.version + ")");

        const text = [
          'import afield.four.synth.*',
          'sonicPi(${"\"\"\""}',
          '#sonicpi',
          '# an ordinary comment',
          'live_loop :bikes do',
          '  use_synth :dsaw',
          '  with_fx :reverb, room: 0.5 do',
          '    play :e2, amp: 0.4, release: 8',
          '    sample :bd_haus',
          '  end',
          '  sleep 8',
          'end',
          '${"\"\"\""})'
        ].join("\n");

        function positionAt(offset) {
          const before = text.slice(0, offset).split("\n");
          return { lineNumber: before.length, column: before[before.length - 1].length + 1 };
        }
        function offsetAt(position) {
          const lines = text.split("\n");
          let offset = 0;
          for (let i = 0; i < position.lineNumber - 1; i++) offset += lines[i].length + 1;
          return offset + position.column - 1;
        }
        const model = {
          getLanguageId: () => "kotlin",
          getValue: () => text,
          getVersionId: () => 7,
          getPositionAt: positionAt,
          getOffsetAt: offsetAt,
          getLineContent: (n) => text.split("\n")[n - 1],
          getWordUntilPosition: (position) => {
            const line = text.split("\n")[position.lineNumber - 1].slice(0, position.column - 1);
            const m = /[A-Za-z_][A-Za-z0-9_?!]*$/.exec(line);
            const word = m ? m[0] : "";
            return { word, startColumn: position.column - word.length, endColumn: position.column };
          }
        };

        // --- region detection ---
        const regions = proof.findEmbeddedRegions(model);
        if (regions.length !== 1) throw new Error("expected one #sonicpi region, got " + regions.length);
        const region = regions[0];
        if (!/^\s*#sonicpi/.test(region.source)) {
          throw new Error("the header must stay in the region source (Sonic Pi counts it as line 1)");
        }
        if (text.slice(region.headerStart, region.headerEnd) !== "#sonicpi") {
          throw new Error("header offsets wrong: " + JSON.stringify(text.slice(region.headerStart, region.headerEnd)));
        }
        // The invariant the error marker depends on: a line's number inside the region equals its
        // line number in the box. Sonic Pi reports errors against the source it was handed, so if
        // these two ever diverge every marker lands on the wrong row. It holds because the opening
        // triple quote ends its line, so the region starts with a newline and its line 1 is the
        // box's line 1.
        const lineInRegion = (offset) => region.source.slice(0, offset - region.bodyStart).split("\n").length;
        const lineInModel = (offset) => positionAt(offset).lineNumber;
        const offsets = ["#sonicpi", "use_synth", "play :e2", "sleep 8"].map((needle) => {
          const at = text.indexOf(needle);
          if (at < 0) throw new Error("no " + needle + " in the probe source");
          return { needle, delta: lineInModel(at) - lineInRegion(at), boxLine: lineInModel(at) };
        });
        const deltas = new Set(offsets.map((o) => o.delta));
        if (deltas.size !== 1) {
          throw new Error("region-to-box line offset drifts: " + JSON.stringify(offsets));
        }
        const [delta] = [...deltas];
        // The offset is the line the opening triple quote sits on, minus one. It is 0 only when the
        // sonicPi( call is the box's first line, which is why the Kotlin side infers it rather than
        // defaulting it: an `import` above the call shifts every error marker by one.
        const expectedDelta = lineInModel(region.bodyStart) - 1;
        if (delta !== expectedDelta) {
          throw new Error("offset " + delta + " does not match the opening line " + expectedDelta);
        }
        console.log("probe: region-to-box line offset is a constant " + delta +
          " (" + offsets.map((o) => o.needle + "@" + o.boxLine).join(", ") + ")");

        // --- tokenisation and classification ---
        const tokens = proof.tokenize(region.source, region.bodyStart);
        const classOf = (needle, kind) => {
          const token = tokens.find((t) => t.text === needle && (!kind || t.kind === kind));
          if (!token) throw new Error("no token " + needle + (kind ? " of kind " + kind : ""));
          return proof.classForToken(token);
        };
        const expect = (needle, kind, expected) => {
          const got = classOf(needle, kind);
          if (got !== expected) throw new Error(needle + " classified " + got + ", expected " + expected);
        };
        expect("live_loop", "word", "afield-stacksl-token-keyword");
        expect("play", "word", "afield-stacksl-token-keyword");
        expect("do", "word", "afield-stacksl-token-control");
        expect("amp:", "opt", "afield-stacksl-token-operator");
        expect(":dsaw", "symbol", "afield-stacksl-token-creative");
        expect(":reverb", "symbol", "afield-stacksl-token-creative");
        expect(":bd_haus", "symbol", "afield-stacksl-token-creative");
        expect(":bikes", "symbol", "afield-stacksl-token-constant");
        expect("8", "number", "afield-stacksl-token-number");
        if (!tokens.some((t) => t.kind === "comment" && t.text.includes("ordinary comment"))) {
          throw new Error("the # comment was not tokenised as a comment");
        }
        console.log("probe: :dsaw/:reverb/:bd_haus read as sound names, :bikes as a plain symbol");

        // --- completions ---
        const bareLine = text.split("\n").findIndex((l) => l.includes("sleep 8")) + 1;
        const bare = proof.provideCompletionItems(
          { getModel: () => model, getPosition: () => ({ lineNumber: bareLine, column: 8 }) },
          model,
          { lineNumber: bareLine, column: 8 }
        );
        if (!bare || !bare.suggestions.length) throw new Error("no completions on a bare line");
        if (!bare.suggestions.some((s) => s.label === "live_loop")) throw new Error("live_loop not offered");

        // `use_synth :` must offer synths, not every symbol in Sonic Pi.
        const synthLine = text.split("\n").findIndex((l) => l.includes("use_synth")) + 1;
        const synthCol = text.split("\n")[synthLine - 1].indexOf(":dsaw") + 3;
        const synthSuggest = proof.provideCompletionItems(
          { getModel: () => model, getPosition: () => ({ lineNumber: synthLine, column: synthCol }) },
          model,
          { lineNumber: synthLine, column: synthCol }
        );
        const synthLabels = (synthSuggest ? synthSuggest.suggestions : []).map((s) => s.label);
        if (!synthLabels.includes(":dsaw")) throw new Error("use_synth : did not offer :dsaw");
        if (synthLabels.includes(":bd_haus")) {
          throw new Error("use_synth : offered a sample name, so the domain filter is not working");
        }
        console.log("probe: use_synth : offered " + synthLabels.length + " synths and no samples");

        // Opts of the function being called come first.
        const playLine = text.split("\n").findIndex((l) => l.includes("play :e2")) + 1;
        // A column where an opt is being typed, not one inside `:e2` — a caret after a colon is a
        // symbol position and correctly gets synth/sample names instead.
        const optCol = text.split("\n")[playLine - 1].indexOf("release:") + 2;
        const playSuggest = proof.provideCompletionItems(
          { getModel: () => model, getPosition: () => ({ lineNumber: playLine, column: optCol }) },
          model,
          { lineNumber: playLine, column: optCol }
        );
        const optLabels = (playSuggest ? playSuggest.suggestions : []).filter((s) => s.kind === 2).map((s) => s.label);
        if (!optLabels.includes("amp:")) throw new Error("play's amp: opt not offered, got " + optLabels.slice(0, 8));
        if (!optLabels.includes("release:")) throw new Error("play's release: opt not offered");
        console.log("probe: play offered " + optLabels.length + " of its own opts, including amp: and release:");

        // --- context badge ---
        const badgeCol = text.split("\n")[playLine - 1].indexOf("play") + 2;
        const payload = proof.buildContextPayload(
          { getModel: () => model, getPosition: () => ({ lineNumber: playLine, column: badgeCol }) },
          null,
          { contextKey: "probe" }
        );
        if (!payload || !payload.html.includes("Sonic Pi")) throw new Error("no Sonic Pi badge");
        if (!payload.html.includes("play")) throw new Error("badge did not name play: " + payload.html);
        console.log("probe: badge = " + payload.html.replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim());

        // A caret outside any #sonicpi region must not be claimed by this extension.
        const outside = proof.buildContextPayload(
          { getModel: () => model, getPosition: () => ({ lineNumber: 1, column: 3 }) },
          null,
          { contextKey: "probe" }
        );
        if (outside) throw new Error("claimed a caret on the Kotlin import line");

        console.log("sonicpi node probe passed");
    """.trimIndent()
}
