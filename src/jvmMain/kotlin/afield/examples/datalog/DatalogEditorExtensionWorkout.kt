package afield.examples.datalog

import afield.datalog.DatalogEditorExtension
import afield.datalog.DatalogExecution
import afield.datalog.DatalogParser
import afield.datalog.dlBoxRelationSchema
import afield.util.NodeTool
import java.nio.file.Files
import kotlin.io.path.writeText

/*
FIELD_TEST
id: datalog.editor-extension.workout
runner: jvm-main
entrypoint: afield.examples.datalog.DatalogEditorExtensionWorkout
tags: [smoke, examples, datalog, monaco, language-extension]
timeoutSec: 60
expect:
  exitCode: 0
  stdoutContains:
    - "datalog editor extension workout passed"
*/

/**
 * Runs `datalog.js` for real, under node, against a mock Monaco.
 *
 * A node probe rather than a unit test of a Kotlin mirror, for the reason the Petri workout gives:
 * it exercises **the exact file that ships** — [DatalogEditorExtension.javascript] reads the same
 * resource the browser is served. A highlighter that only works in a re-implementation is a
 * highlighter that does not work.
 *
 * It also checks the thing most likely to rot. The editor's vocabulary — aggregate operators,
 * builtin functions, `#directives`, the box-graph relation names — is written down twice, once in
 * Kotlin and once in `datalog.js`, because the editor cannot call the server. [checkVocabularyAgrees]
 * compares them, so adding a builtin on one side and forgetting the other fails here rather than
 * silently colouring a real function as an unknown constant.
 */
object DatalogEditorExtensionWorkout {

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "datalog editor extension workout failed" }
    }

    fun runTests(): Boolean {
        val js = DatalogEditorExtension.javascript()
        require("FieldLanguageExtensions.register" in js) { "datalog.js does not register with the extension registry" }
        require("id: extensionId" in js)
        require("#datalog" in js)
        require("updateEmbeddedHighlight" in js)
        require("updateDiagnostics" in js)
        require("buildContextPayload" in js)
        require("provideCompletionItems" in js)
        require("registerDatalogLanguage" in js)
        require("setLanguageConfiguration" in js)
        require("analyzeLexical" in js)
        // No Monarch grammar: one bad rule wedges initMonaco with no console error.
        require("setMonarchTokensProvider" !in js) {
            "datalog.js must not install a Monarch grammar — highlighting is decoration-based"
        }

        checkVocabularyAgrees(js)
        checkHeaderIsAcceptedByTheParser()
        runNodeProbe(js)
        println("datalog editor extension workout passed")
        return true
    }

    /**
     * The editor's word lists must equal the parser's.
     *
     * There is no way to share them at run time — the editor is JavaScript in a browser and
     * [DatalogParser] is Kotlin on a server — but there is every way to check at build time, and an
     * unchecked duplicate is just a bug with a delay on it.
     */
    private fun checkVocabularyAgrees(js: String) {
        fun jsList(name: String): Set<String> {
            val block = js.substringAfter("const $name = [", "").substringBefore("];", "")
            require(block.isNotBlank()) { "could not find $name in datalog.js" }
            return Regex("\"([^\"]+)\"").findAll(block).map { it.groupValues[1] }.toSet()
        }

        fun agree(what: String, inEditor: Set<String>, inKotlin: Set<String>) {
            require(inEditor == inKotlin) {
                "datalog.js and Kotlin disagree about $what: only in the editor ${inEditor - inKotlin}, " +
                    "only in Kotlin ${inKotlin - inEditor}"
            }
        }

        agree("aggregate operators", jsList("aggregateOps"), DatalogParser.aggregateOps)
        agree("builtin functions", jsList("functionWords"), DatalogParser.functions)
        agree(
            "directives",
            jsList("directiveWords"),
            DatalogExecution.directiveHelp.map { it.first.substringBefore(' ') }.toSet(),
        )

        // The box-graph relation names, which the editor offers as completions once a program has
        // said `#boxes`. Parsed out of the `boxRelations` table's first column.
        val relationBlock = js.substringAfter("const boxRelations = [", "").substringBefore("\n  ];", "")
        val inEditor = Regex("\\[\"([^\"]+)\",").findAll(relationBlock).map { it.groupValues[1] }.toSet()
        agree("box-graph relations", inEditor, dlBoxRelationSchema.map { it.first.substringBefore('(') }.toSet())
    }

    /**
     * The other half of the contract: the header the editor keys on has to be something the parser
     * will swallow, or the same text cannot be both highlighted and run.
     */
    private fun checkHeaderIsAcceptedByTheParser() {
        val unit = DatalogParser.parse(
            """
            #datalog
            edge(a, b).
            path(X, Y) :- edge(X, Y).
            """.trimIndent()
        )
        require(unit.directives.isEmpty()) { "#datalog should be dropped, got ${unit.directives}" }
        require(unit.rules.size == 2) { "the program after the header did not parse: ${unit.rules}" }
    }

    private fun runNodeProbe(extensionSource: String) {
        val dir = Files.createTempDirectory("datalog-editor-extension-")
        val extensionFile = dir.resolve("datalog-extension.js")
        val probeFile = dir.resolve("probe.js")
        extensionFile.writeText(extensionSource)
        probeFile.writeText(nodeProbeSource())

        val node = NodeTool.resolveNode()
            ?: error("datalog editor-extension workout needs node: ${NodeTool.unavailableMessage()}")

        val process = ProcessBuilder(node, probeFile.toString(), extensionFile.toString())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        require(exit == 0) { "node probe failed with exit $exit\n$output" }
        require("datalog node probe passed" in output) { output }
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
        const languageRegistrations = [];
        const languageConfigurations = [];
        global.window = {
          FieldLanguageExtensions: {
            register(extension) {
              registered.push(extension);
              return extension;
            }
          },
          globalMonaco: {
            Range,
            languages: {
              CompletionItemKind: { Function: 1, Keyword: 2, EnumMember: 3, Property: 4 },
              getLanguages: () => languageRegistrations,
              register: (definition) => languageRegistrations.push(definition),
              setLanguageConfiguration: (id, configuration) => languageConfigurations.push({ id, configuration }),
            },
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
        if (extension.id !== "datalog") throw new Error("wrong extension id " + extension.id);

        const proof = window.__datalogExtensionProof;
        const tokenize = proof.tokenize;
        const analyzeLexical = proof.analyzeLexical;
        const analyzeProgram = proof.analyzeProgram;

        const text = `
        import afield.datalog.*
        val d = Datalog()
        d.add(${"\"\"\""}
        #datalog
        % who reaches whom
        edge(a, b). edge(b, c).
        path(X, Y) :- edge(X, Y).
        path(X, Z) :- path(X, Y), edge(Y, Z).
        lonely(X) :- box(X), !path(X, _).
        busy(P, N) :- box(P), N = count : { child(P, _) }, N > 2.
        ${"\"\"\""})
        val plain = ${"\"\"\""}
        not datalog at all
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
          getVersionId: () => 3,
          getPositionAt: positionAt,
          getOffsetAt: offsetAt,
        };

        // Exactly one region: the #datalog string, not the plain one next to it.
        const regions = extension.findEmbeddedRegions(model);
        if (regions.length !== 1) throw new Error("expected one #datalog region, got " + regions.length);
        if (!regions[0].source.includes("path(X, Z)")) throw new Error("region source missing the recursive rule");
        if (regions[0].source.includes("not datalog at all")) throw new Error("region ran past its closing quotes");

        const collections = [];
        const caret = text.indexOf("path(X, Y) :- edge") + 2;
        const editor = {
          getModel: () => model,
          getPosition: () => positionAt(caret),
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

        const highlight = editor._datalogHighlightDecorations && editor._datalogHighlightDecorations.latest;
        if (!highlight || highlight.length < 40) {
          throw new Error("expected highlight decorations, got " + (highlight && highlight.length));
        }
        const classes = new Set(highlight.map((d) => d.options.inlineClassName));
        // The distinctions that make a rule readable at a glance.
        if (!classes.has("afield-stacksl-token-definition")) throw new Error("rule heads are not distinguished");
        if (!classes.has("afield-stacksl-token-modifier")) throw new Error("logic variables are not distinguished");
        if (!classes.has("afield-stacksl-token-control")) throw new Error("':-' is not distinguished");
        if (!classes.has("afield-stacksl-token-comment")) throw new Error("a %% comment is not distinguished");
        if (!classes.has("afield-stacksl-token-creative")) throw new Error("the aggregate operator is not distinguished");
        if (!classes.has("afield-stacksl-token-directive")) throw new Error("the #datalog header is not marked");

        // A head and a body use of the same predicate get different colours.
        const headOffset = regions[0].bodyStart + regions[0].source.indexOf("path(X, Y)");
        const bodyOffset = regions[0].bodyStart + regions[0].source.indexOf("path(X, Y), edge");
        const classAt = (offset) => {
          const hit = highlight.find((d) => {
            const p = positionAt(offset);
            return d.range.startLineNumber === p.lineNumber && d.range.startColumn === p.column;
          });
          return hit && hit.options.inlineClassName;
        };
        if (classAt(headOffset) !== "afield-stacksl-token-definition") {
          throw new Error("a rule head should read as a definition, got " + classAt(headOffset));
        }
        if (classAt(bodyOffset) === "afield-stacksl-token-definition") {
          throw new Error("a body goal should not read as a definition");
        }

        // Valid source is clean. This matters more than it looks: a highlighter that cries wolf on
        // correct code is worse than one that says nothing.
        const clean = analyzeLexical(regions[0].source);
        if (clean.length !== 0) throw new Error("valid datalog flagged: " + JSON.stringify(clean));

        const cases = [
          ['edge(a, b.', "is not closed"],
          ['edge("a, b).', "not terminated"],
          ['n = count : { child(P, _) .', "is not closed"],
          ['edge(a, b)', "not terminated with"],
          ['/* open', "not closed with '*/'"],
          ['edge(a, b)).', "no matching"],
        ];
        for (const [src, needle] of cases) {
          const found = analyzeLexical(src);
          if (!found.some((issue) => issue.message.includes(needle))) {
            throw new Error("expected '" + needle + "' for " + JSON.stringify(src) + ", got " + JSON.stringify(found));
          }
        }

        // A session command is not a clause. This underlined every `#why` in a Datalog box until
        // analyzeLexical learned to skip a '#' line the way it skips a '%' one.
        const withDirective = 'cell(a3, 10).\nover(C) :- cell(C, V), V > 5.\n\n#why over(a3)\n';
        if (analyzeLexical(withDirective).length !== 0) {
          throw new Error("a #directive line was flagged: " + JSON.stringify(analyzeLexical(withDirective)));
        }

        // Numbers keep their decimal point: `1.5` is not an unterminated clause.
        if (analyzeLexical("weight(a, 1.5).").length !== 0) {
          throw new Error("a decimal point was mistaken for a clause terminator");
        }

        // Diagnostics reach Monaco as markers, not just as a return value.
        const markerCalls = window.globalMonaco.editor.markerCalls;
        if (!markerCalls.some((call) => call.owner === "datalog-diagnostics")) {
          throw new Error("diagnostics never reached setModelMarkers");
        }

        // Arity is counted lexically, and that is what the badge reports.
        const analysis = analyzeProgram(regions[0].source, 0);
        const path = analysis.predicates.get("path");
        if (!path || !path.arities.has(2) || path.heads !== 2) {
          throw new Error("bad arity analysis " + JSON.stringify({ arities: path && [...path.arities], heads: path && path.heads }));
        }
        const payload = extension.buildContextPayload(editor, () => ({ uid: "box", property: "code" }), {});
        if (!payload || !payload.html.includes("Datalog")) throw new Error("bad context payload " + JSON.stringify(payload));
        if (!payload.html.includes("path/2")) throw new Error("context payload should report the arity: " + payload.html);

        // Completion knows the program's own predicates, and offers directives only after '#'.
        const inBody = positionAt(regions[0].bodyStart + regions[0].source.indexOf("edge(Y, Z)") + 2);
        const completions = extension.provideCompletionItems(editor, model, inBody, { monaco: window.globalMonaco });
        const labels = completions.suggestions.map((s) => s.label);
        if (!labels.includes("edge")) throw new Error("no predicate completions: " + JSON.stringify(labels));
        if (labels.some((l) => String(l).startsWith("#"))) throw new Error("directives offered mid-clause");

        // The standalone language is registered for loose .datalog files, with % comments.
        if (!languageRegistrations.some((l) => l.id === "datalog" && l.extensions.includes(".datalog"))) {
          throw new Error("the datalog language was not registered: " + JSON.stringify(languageRegistrations));
        }
        const configuration = languageConfigurations.find((c) => c.id === "datalog");
        if (!configuration || configuration.configuration.comments.lineComment !== "%") {
          throw new Error("datalog language configuration missing or wrong");
        }

        // A whole datalog document is one scope, whatever case the language id arrives in — a box's
        // code_language is the display label "Datalog", a loose file's is the id "datalog".
        for (const languageId of ["datalog", "Datalog"]) {
          const loose = "path(X, Y) :- edge(X, Y).";
          const looseModel = {
            getLanguageId: () => languageId,
            getValue: () => loose,
            getVersionId: () => 1,
            getPositionAt: (offset) => ({ lineNumber: 1, column: offset + 1 }),
            getOffsetAt: (position) => position.column - 1,
          };
          const looseEditor = {
            getModel: () => looseModel,
            getPosition: () => ({ lineNumber: 1, column: 2 }),
            hasTextFocus: () => true,
            createDecorationsCollection(initial) {
              return { latest: initial || [], set(value) { this.latest = value; } };
            },
            onDidChangeCursorPosition() {},
            onDidChangeModelContent() {},
            onDidChangeModel() {},
          };
          extension.updateEmbeddedHighlight(looseEditor);
          const looseDecorations = looseEditor._datalogHighlightDecorations.latest;
          if (!looseDecorations || looseDecorations.length < 8) {
            throw new Error("a loose ." + languageId + " document was not highlighted: " + JSON.stringify(looseDecorations));
          }
        }

        console.log("datalog node probe passed");
    """.trimIndent()
}
