package afield.examples.rectsl

import afield.rectsl.*

import java.io.File
import java.nio.file.Files
import kotlin.io.path.writeText

/*
FIELD_TEST
id: rectsl.editor-extension.workout
runner: jvm-main
entrypoint: afield.examples.rectsl.RectSLEditorExtensionWorkout
tags: [smoke, examples, rectsl, monaco, language-extension]
timeoutSec: 30
expect:
  exitCode: 0
  stdoutContains:
    - "rectsl editor extension workout passed"
*/
object RectSLEditorExtensionWorkout {
    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "rectsl editor extension workout failed" }
    }

    fun runTests(): Boolean {
        val js = RectSLEditorExtension.javascript()
        require("FieldLanguageExtensions.register" in js)
        require("id: extensionId" in js)
        require("#rectsl" in js)
        require("updateEmbeddedHighlight" in js)
        require("updateFlowLens" in js)
        require("buildContextPayload" in js)
        require("provideCompletionItems" in js)
        require("registerRectSLLanguage" in js)
        require("setLanguageConfiguration" in js)
        require("analyzeLexical" in js)
        runNodeProbe(js)
        println("rectsl editor extension workout passed")
        return true
    }

    private fun runNodeProbe(extensionSource: String) {
        val dir = Files.createTempDirectory("rectsl-editor-extension-")
        val extensionFile = dir.resolve("rectsl-extension.js")
        val probeFile = dir.resolve("probe.js")
        extensionFile.writeText(extensionSource)
        probeFile.writeText(nodeProbeSource())

        val process = ProcessBuilder(nodeExecutable(), probeFile.toString(), extensionFile.toString())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        require(exit == 0) { "node probe failed with exit $exit\n$output" }
        require("rectsl node probe passed" in output) { output }
    }

    /**
     * Prefer an explicit Node override, then the repository-local Node installed by Gradle. The
     * smoke runner can launch its child JVM with a deliberately small PATH, so relying on the bare
     * `node` command makes this otherwise hermetic workout depend on the agent's login shell.
     */
    private fun nodeExecutable(): String {
        listOf("AFIELD_NODE", "NODE").firstNotNullOfOrNull { name ->
            System.getenv(name)?.takeIf(String::isNotBlank)?.let(::File)?.takeIf(File::canExecute)
        }?.let { return it.absolutePath }

        val executableName = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "node.exe"
        } else {
            "node"
        }
        val managedRoot = File(System.getProperty("user.dir"), ".gradle/nodejs")
        managedRoot.listFiles()
            .orEmpty()
            .asSequence()
            .map { File(it, "bin/$executableName") }
            .firstOrNull(File::canExecute)
            ?.let { return it.absolutePath }

        return executableName
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
            languages: { CompletionItemKind: { Function: 1 } },
            MarkerSeverity: { Warning: 4 },
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
        if (extension.id !== "rectsl") throw new Error("wrong extension id " + extension.id);

        const text = `
        import afield.rectsl.*
        val program = ${"\"\"\""}
        #rectsl
        ->wall 4 4 meters
        "r" rect
          -1 0 ->xy
          1 ->square
          '( 0 -1  1 1 ) ->x
        ;
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
        const model = {
          getLanguageId: () => "kotlin",
          getValue: () => text,
          getVersionId: () => 7,
          getPositionAt: positionAt,
          getOffsetAt: offsetAt,
        };

        const regions = extension.findEmbeddedRegions(model);
        if (regions.length !== 1) throw new Error("expected one #rectsl region, got " + regions.length);
        if (!regions[0].source.includes("\"r\" rect")) throw new Error("region source missing rect");

        const collections = [];
        const xyOffset = text.indexOf("->xy") + 3;
        const editor = {
          _actions: [],
          getModel: () => model,
          getPosition: () => positionAt(xyOffset),
          hasTextFocus: () => true,
          createDecorationsCollection(initial) {
            const state = { latest: initial || [], set(value) { this.latest = value; } };
            collections.push(state);
            return state;
          },
          addAction(action) { this._actions.push(action); },
          onDidChangeCursorPosition() {},
          onDidChangeModelContent() {},
          onDidChangeModel() {},
        };

        extension.install(editor, window.globalMonaco, window.FieldLanguageExtensions);
        extension.updateEmbeddedHighlight(editor, { editor, model, monaco: window.globalMonaco });
        extension.updateFlowLens(editor, { editor, model, monaco: window.globalMonaco });
        extension.updateDiagnostics(editor, { editor, model, monaco: window.globalMonaco });

        if (!editor._actions.some((action) => action.id === "toggle-rectsl-flow-lens")) {
          throw new Error("missing RectSL flow lens action");
        }
        const highlight = editor._rectslHighlightDecorations && editor._rectslHighlightDecorations.latest;
        if (!highlight || highlight.length < 8) throw new Error("expected highlight decorations, got " + (highlight && highlight.length));
        const flow = editor._rectslFlowDecorations && editor._rectslFlowDecorations.latest;
        if (!flow || flow.length < 2) throw new Error("expected flow decorations for ->xy, got " + (flow && flow.length));

        const payload = extension.buildContextPayload(editor, () => ({ uid: "box", property: "code" }), {});
        if (!payload || !payload.html.includes("RectSL") || !payload.html.includes("2-&gt;0")) {
          throw new Error("bad context payload " + JSON.stringify(payload));
        }
        // Richer badge: the active word's documented signature is surfaced (->xy -> "x y --").
        if (!payload.html.includes("x y --")) {
          throw new Error("context payload missing word signature " + JSON.stringify(payload));
        }
        // Lexical diagnostics: valid source is clean, an unterminated quote is flagged.
        const analyzeLexical = window.__rectslExtensionProof.analyzeLexical;
        if (analyzeLexical(regions[0].source).length !== 0) {
          throw new Error("valid rectsl flagged: " + JSON.stringify(analyzeLexical(regions[0].source)));
        }
        if (analyzeLexical("'( 1 2 ->x").length === 0) {
          throw new Error("unterminated quote not flagged");
        }
        const completionPosition = positionAt(text.indexOf("->sq") + 4);
        const completions = extension.provideCompletionItems(editor, model, completionPosition, { monaco: window.globalMonaco });
        if (!completions || !completions.suggestions || !completions.suggestions.some((item) =>
          item.label === "->square" &&
          item.data && item.data.rectslEffect === "mutator" &&
          String(item.documentation.value).includes("Set width and height")
        )) {
          throw new Error("missing documented ->square completion " + JSON.stringify(completions));
        }
        console.log("rectsl node probe passed");
    """.trimIndent()
}
