package afield.examples.brief

import afield.brief.BriefEditorExtension
import afield.util.NodeTool
import java.nio.file.Files
import kotlin.io.path.writeText

/*
FIELD_TEST
id: brief.editor-extension.workout
runner: jvm-main
entrypoint: afield.examples.brief.BriefEditorExtensionWorkout
tags: [smoke, examples, brief, monaco, language-extension]
timeoutSec: 30
expect:
  exitCode: 0
  stdoutContains:
    - "brief editor extension workout passed"
*/
/**
 * Runs `brief.js` for real, in node, against a mocked Monaco.
 *
 * The point of doing it this way rather than asserting on the file's text: `brief.js` is served to a
 * browser and nothing else executes it, so without this the first time the code ran would be in front
 * of a person, and a throw inside an AMD factory does not reach the console — it wedges `initMonaco`
 * and every symptom reads "editor not synced yet" (`bootstrapMonaco.js:52-57`). The probe loads the
 * exact bytes off the classpath under bare `eval`, which is the path the file's non-AMD branch exists
 * to support.
 */
object BriefEditorExtensionWorkout {
    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "brief editor extension workout failed" }
    }

    fun runTests(): Boolean {
        val js = BriefEditorExtension.javascript()
        require("FieldLanguageExtensions.register" in js)
        require("#brief" in js)
        require("updateEmbeddedHighlight" in js)
        require("updateFlowLens" in js)
        require("updateDiagnostics" in js)
        require("buildContextPayload" in js)
        require("provideCompletionItems" in js)
        require("registerBriefLanguage" in js)
        require("setLanguageConfiguration" in js)
        require("analyzeLexical" in js)
        // No Monarch grammar, on purpose. A bad rule in one would wedge the editor with no error.
        require("setMonarchTokensProvider" !in js) { "brief.js must not register a Monarch grammar" }
        runNodeProbe(js)
        println("brief editor extension workout passed")
        return true
    }

    private fun runNodeProbe(extensionSource: String) {
        val dir = Files.createTempDirectory("brief-editor-extension-")
        val extensionFile = dir.resolve("brief-extension.js")
        val probeFile = dir.resolve("probe.js")
        extensionFile.writeText(extensionSource)
        probeFile.writeText(nodeProbeSource())

        val node = NodeTool.resolveNode()
            ?: error("brief editor-extension workout needs node: ${NodeTool.unavailableMessage()}")

        val process = ProcessBuilder(node, probeFile.toString(), extensionFile.toString())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        require(exit == 0) { "node probe failed with exit $exit\n$output" }
        require("brief node probe passed" in output) { output }
    }

    private fun nodeProbeSource(): String = """
        const fs = require("fs");
        const source = fs.readFileSync(process.argv[2], "utf8");
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
            languages: { CompletionItemKind: { Keyword: 1 } },
            MarkerSeverity: { Error: 8, Warning: 4 },
            editor: {
              markerCalls: [],
              setModelMarkers(model, owner, markers) { this.markerCalls.push({ model, owner, markers }); }
            }
          }
        };
        global.setTimeout = (fn) => { fn(); return 0; };
        eval(source);

        if (registered.length !== 1) throw new Error("expected one registered extension, got " + registered.length);
        const extension = registered[0];
        if (extension.id !== "brief") throw new Error("wrong extension id " + extension.id);

        const text = `
        import afield.brief.*
        val plan = ${"\"\"\""}
        #brief
        point west  at (0, 0)
        point east  at (9, 0)
        1e10 fix west east
        prefer spine at 1/3 along (west, east)
        insist distance(west, spine) = 6
        ${"\"\"\""}
        `.trim();

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
        const caret = text.indexOf("insist") + 3;
        const model = {
          getLanguageId: () => "kotlin",
          getValue: () => text,
          getVersionId: () => 3,
          getPositionAt: positionAt,
          getOffsetAt: offsetAt,
        };
        const collections = [];
        const editor = {
          getModel: () => model,
          getPosition: () => positionAt(caret),
          hasTextFocus: () => true,
          createDecorationsCollection(initial) {
            const state = { latest: initial || [], set(value) { this.latest = value; } };
            collections.push(state);
            return state;
          },
          addAction() {},
          onDidChangeCursorPosition() {},
          onDidChangeModelContent() {},
          onDidChangeModel() {},
        };

        const regions = extension.findEmbeddedRegions(model);
        if (regions.length !== 1) throw new Error("expected one #brief region, got " + regions.length);
        if (!regions[0].source.includes("prefer spine at")) throw new Error("region source is wrong");

        extension.updateEmbeddedHighlight(editor);
        extension.updateFlowLens(editor);
        extension.updateDiagnostics(editor);

        const highlight = editor._briefHighlightDecorations && editor._briefHighlightDecorations.latest;
        if (!highlight || highlight.length < 20) {
          throw new Error("expected highlight decorations, got " + (highlight && highlight.length));
        }
        // The strength lens: only the statements that did NOT write a strength get an annotation, so
        // this brief has exactly one (`prefer` and `insist` and `1e10` all wrote theirs).
        const flow = editor._briefFlowDecorations && editor._briefFlowDecorations.latest;
        if (!flow || flow.length !== 0) {
          throw new Error("every statement wrote its strength, so expected no lens, got " + JSON.stringify(flow));
        }

        const markers = window.globalMonaco.editor.markerCalls;
        if (markers.length !== 1 || markers[0].owner !== "brief") {
          throw new Error("expected one marker call owned by brief, got " + JSON.stringify(markers));
        }
        if (markers[0].markers.length !== 0) {
          throw new Error("a valid brief was flagged: " + JSON.stringify(markers[0].markers));
        }

        const analyzeLexical = window.__briefExtensionProof.analyzeLexical;
        if (analyzeLexical(regions[0].source).length !== 0) {
          throw new Error("valid brief flagged: " + JSON.stringify(analyzeLexical(regions[0].source)));
        }
        if (analyzeLexical("prefer a at 1/3 along (b, c").length === 0) {
          throw new Error("unclosed bracket not flagged");
        }
        if (analyzeLexical("insist a ~ b").length === 0) {
          throw new Error("a meaningless character was not flagged");
        }
        if (analyzeLexical("offset 3").length === 0) {
          throw new Error("a glue word beginning a statement was not flagged");
        }

        const payload = extension.buildContextPayload(editor);
        if (!payload || payload.label !== "Brief") throw new Error("bad context payload " + JSON.stringify(payload));
        if (payload.detail !== "3 constraints") {
          throw new Error("expected 3 constraints in the badge, got " + JSON.stringify(payload));
        }
        // The caret is on `insist`, so the badge documents that strength rather than the language.
        if (!payload.documentation.includes("1e3")) {
          throw new Error("badge should document the word under the caret: " + JSON.stringify(payload));
        }

        const completions = extension.provideCompletionItems(editor, model, positionAt(caret));
        const labels = completions.map((item) => item.label);
        ["whisper", "prefer", "insist", "demand", "point", "panel", "planar", "mirrors", "distance", "area"]
          .forEach((word) => {
            if (!labels.includes(word)) throw new Error("completion missing " + word);
          });
        if (completions.some((item) => item.language !== "brief")) {
          throw new Error("every completion item must be tagged with the language");
        }
        // Outside a region, nothing: a Route A extension has to return nothing when the caret is not
        // its business, because the registry concatenates every extension's items with no arbitration.
        const outside = extension.provideCompletionItems(editor, model, positionAt(2));
        if (outside.length !== 0) throw new Error("expected no completions outside the region");

        console.log("brief node probe passed");
    """.trimIndent()
}
