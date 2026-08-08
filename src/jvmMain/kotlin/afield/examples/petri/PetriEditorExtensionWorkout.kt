package afield.examples.petri

import afield.petri.PetriCombinators
import afield.petri.PetriEditorExtension
import afield.util.NodeTool
import java.nio.file.Files
import kotlin.io.path.writeText

/*
FIELD_TEST
id: petri.editor-extension.workout
runner: jvm-main
entrypoint: afield.examples.petri.PetriEditorExtensionWorkout
tags: [smoke, examples, petri, monaco, language-extension]
timeoutSec: 30
expect:
  exitCode: 0
  stdoutContains:
    - "petri editor extension workout passed"
*/

/**
 * Runs `petri.js` for real, under node, against a mock Monaco.
 *
 * The point of a node probe rather than a unit test of a Kotlin mirror is that it exercises **the
 * exact file that ships** — `PetriEditorExtension.javascript()` reads the same resource the browser
 * is served. A highlighter that only works in a re-implementation is a highlighter that does not
 * work.
 *
 * It also checks the one thing most likely to rot: the combinator vocabulary is written down twice,
 * once in `PetriNotation.kt` and once in `petri.js`, because the editor cannot call Kotlin. This
 * asserts the two lists are the same, so adding a combinator on one side and forgetting the other
 * fails here rather than silently underlining valid source in red.
 */
object PetriEditorExtensionWorkout {
    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "petri editor extension workout failed" }
    }

    fun runTests(): Boolean {
        val js = PetriEditorExtension.javascript()
        require("FieldLanguageExtensions.register" in js)
        require("id: extensionId" in js)
        require("#petri" in js)
        require("updateEmbeddedHighlight" in js)
        require("updateDiagnostics" in js)
        require("buildContextPayload" in js)
        require("provideCompletionItems" in js)
        require("registerPetriLanguage" in js)
        require("setLanguageConfiguration" in js)
        require("analyzeLexical" in js)

        checkCombinatorsAgree(js)
        runNodeProbe(js)
        println("petri editor extension workout passed")
        return true
    }

    /**
     * The editor's combinator list must equal [PetriCombinators]'s.
     *
     * The editor is JavaScript in a browser and the registry is Kotlin on a server, so there is no
     * way to share the list at run time — but there is every way to check it at build time, and an
     * unchecked duplicate is just a bug with a delay on it.
     */
    private fun checkCombinatorsAgree(js: String) {
        val block = js.substringAfter("const combinatorWords = [", "").substringBefore("];", "")
        require(block.isNotBlank()) { "could not find combinatorWords in petri.js" }
        val inEditor = Regex("\"([^\"]+)\"").findAll(block).map { it.groupValues[1] }.toSet()
        val inKotlin = PetriCombinators.names.toSet()
        require(inEditor == inKotlin) {
            "petri.js and PetriCombinators disagree: only in the editor ${inEditor - inKotlin}, " +
                "only in Kotlin ${inKotlin - inEditor}"
        }
    }

    private fun runNodeProbe(extensionSource: String) {
        val dir = Files.createTempDirectory("petri-editor-extension-")
        val extensionFile = dir.resolve("petri-extension.js")
        val probeFile = dir.resolve("probe.js")
        extensionFile.writeText(extensionSource)
        probeFile.writeText(nodeProbeSource())

        val node = NodeTool.resolveNode()
            ?: error("petri editor-extension workout needs node: ${NodeTool.unavailableMessage()}")

        val process = ProcessBuilder(node, probeFile.toString(), extensionFile.toString())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        require(exit == 0) { "node probe failed with exit $exit\n$output" }
        require("petri node probe passed" in output) { output }
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
            register(extension) {
              registered.push(extension);
              return extension;
            }
          },
          globalMonaco: {
            Range,
            languages: { CompletionItemKind: { Function: 1, Keyword: 2, EnumMember: 3, Property: 4 } },
            MarkerSeverity: { Warning: 4, Error: 8 },
            editor: {
              markerCalls: [],
              setModelMarkers(model, owner, markers) {
                this.markerCalls.push({ model, owner, markers });
              }
            }
          }
        };
        global.setTimeout = (fn) => { fn(); return 0; };
        eval(source);

        if (registered.length !== 1) throw new Error("expected one registered extension, got " + registered.length);
        const extension = registered[0];
        if (extension.id !== "petri") throw new Error("wrong extension id " + extension.id);

        const text = `
        import afield.petri.*
        val net = petri(${"\"\"\""}
        #petri
        !grid 1/16
        kick  : 1
        verse : "bd ~ sd ~"
        kick -[ hit @1/16 pri=2 : bd ]-> hats
        ~armed + 2*hats -[ roll @1/8 => stack ]-> fill
        ${"\"\"\""})
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
        const model = {
          getLanguageId: () => "kotlin",
          getValue: () => text,
          getVersionId: () => 7,
          getPositionAt: positionAt,
          getOffsetAt: offsetAt,
        };

        const regions = extension.findEmbeddedRegions(model);
        if (regions.length !== 1) throw new Error("expected one #petri region, got " + regions.length);
        if (!regions[0].source.includes("-[ hit")) throw new Error("region source missing the arc line");

        const collections = [];
        const stackOffset = text.indexOf("stack") + 2;
        const editor = {
          getModel: () => model,
          getPosition: () => positionAt(stackOffset),
          hasTextFocus: () => true,
          createDecorationsCollection(initial) {
            const state = { latest: initial || [], set(value) { this.latest = value; } };
            collections.push(state);
            return state;
          },
          onDidChangeCursorPosition() {},
          onDidChangeModelContent() {},
          onDidChangeModel() {},
        };

        extension.install(editor, window.globalMonaco);
        extension.updateEmbeddedHighlight(editor);
        extension.updateDiagnostics(editor);

        const highlight = editor._petriHighlightDecorations && editor._petriHighlightDecorations.latest;
        if (!highlight || highlight.length < 20) {
          throw new Error("expected highlight decorations, got " + (highlight && highlight.length));
        }

        // A transition name inside -[ ]- must be coloured differently from a place name outside it:
        // that contrast is what makes the net's bipartite structure readable in plain text.
        const tokenize = window.__petriExtensionProof.tokenize;
        const tokens = tokenize(regions[0].source, 0);
        const classes = new Set(highlight.map((d) => d.options.inlineClassName));
        if (!classes.has("afield-stacksl-token-definition")) throw new Error("transition names are not distinguished");
        if (!classes.has("afield-stacksl-token-identifier")) throw new Error("place names are not distinguished");
        if (!classes.has("afield-stacksl-token-creative")) throw new Error("a quoted pattern is not distinguished");

        // Valid source is clean. This matters more than it looks: a highlighter that cries wolf on
        // correct code is worse than one that says nothing.
        const analyzeLexical = window.__petriExtensionProof.analyzeLexical;
        const clean = analyzeLexical(regions[0].source);
        if (clean.length !== 0) throw new Error("valid petri flagged: " + JSON.stringify(clean));

        const cases = [
          ["a -[ t @nonsense ]-> b", "number of cycles"],
          ["a -[ t => wobble ]-> b", "not a combinator"],
          ["!timing sideways", "!timing must be"],
          ["!grid 0", "positive number of cycles"],
          ["a -[ t pri=x ]-> b", "whole number"],
          ["verse : \"unterminated", "unterminated"],
          ["a -[ t ]-> ~b", "left of a transition"],
          ["a -[ t -> b", "no matching"],
          ["just some words", "expected a place declaration"],
        ];
        for (const [src, needle] of cases) {
          const found = analyzeLexical(src);
          if (!found.some((issue) => issue.message.includes(needle))) {
            throw new Error("expected '" + needle + "' for " + JSON.stringify(src) + ", got " + JSON.stringify(found));
          }
        }

        // Diagnostics reach Monaco as markers, not just as a return value.
        const markerCalls = window.globalMonaco.editor.markerCalls;
        if (!markerCalls.some((call) => call.owner === "petri-diagnostics")) {
          throw new Error("diagnostics never reached setModelMarkers");
        }

        // The context badge explains the word under the caret.
        const payload = extension.buildContextPayload(editor, () => ({ uid: "box", property: "code" }), {});
        if (!payload || !payload.html.includes("Petri") || !payload.html.includes("stack")) {
          throw new Error("bad context payload " + JSON.stringify(payload));
        }
        if (!payload.html.includes("layered")) {
          throw new Error("context payload missing the combinator's description " + JSON.stringify(payload));
        }

        // Completion is context-sensitive: after `=>` it offers combinators and nothing else.
        const afterArrow = positionAt(text.indexOf("=> stack") + 3 + "sta".length);
        const combinators = extension.provideCompletionItems(editor, model, afterArrow, { monaco: window.globalMonaco });
        if (!combinators.suggestions.some((item) => item.label === "stack")) {
          throw new Error("no combinator completions after => : " + JSON.stringify(combinators.suggestions.map((s) => s.label)));
        }
        if (combinators.suggestions.some((item) => item.label === "!grid")) {
          throw new Error("directives should not be offered after =>");
        }

        console.log("petri node probe passed");
    """.trimIndent()
}
