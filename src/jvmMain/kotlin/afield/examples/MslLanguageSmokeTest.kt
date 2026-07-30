package afield.examples

import afield.graphics.MslEditorLanguage
import afield.graphics.VisionMetalKotlinJs
import java.io.File
import java.nio.file.Files
import afield.util.NodeTool

/*
FIELD_TEST
id: visionmetal.msl-language
runner: jvm-main
entrypoint: afield.examples.MslLanguageSmokeTest
tags: [smoke, visionos, editor, completion]
timeoutSec: 120
expect:
  exitCode: 0
  stdoutContains:
    - "MslLanguageSmokeTest ok"
*/

/**
 * Drives the real `msl.js` under Node with the AMD loader and the slice of the Monaco API it touches
 * mocked out, then asks the actual completion provider for suggestions.
 *
 * Worth guarding rather than eyeballing: the provider resolves the receiver of a `.` against the
 * Field ABI structs by reading the surrounding source, and it reads the declared uniform slot order
 * out of the sibling Kotlin box — two pieces of logic that silently degrade to "offers everything"
 * when they break, which looks fine in a screenshot.
 */
object MslLanguageSmokeTest {
    @JvmStatic
    fun main(args: Array<String>) {
        val node = NodeTool.resolveNode()
        if (node == null) {
            // The module still has to be loadable and self-consistent without Node.
            val source = MslEditorLanguage.javascript()
            require(source.contains("define(\"bithero/msl\"")) { "msl.js must register the AMD module" }
            require(source.contains("registerCompletionItemProvider")) { "msl.js must register completion" }
            println("skipping provider drive: ${NodeTool.unavailableMessage()}; msl.js source checks passed")
            println("MslLanguageSmokeTest ok")
            return
        }

        verifyAbiMirrorsTheHead()

        val moduleFile = Files.createTempFile("field-msl-module", ".js").toFile()
        val harnessFile = Files.createTempFile("field-msl-harness", ".js").toFile()
        try {
            moduleFile.writeText(MslEditorLanguage.javascript())
            harnessFile.writeText(harness)
            val process = ProcessBuilder(node, harnessFile.absolutePath, moduleFile.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            require(process.exitValue() == 0) { "msl.js provider drive failed:\n$output" }
            require(output.contains("msl-language-ok")) { "msl.js probe did not pass:\n$output" }
            println(output)
        } finally {
            moduleFile.delete()
            harnessFile.delete()
        }

        println("MslLanguageSmokeTest ok")
    }

    /**
     * The editor's idea of the ABI has to match the head's. `msl.js` hardcodes the struct members for
     * offline completion; the geometry builder writes the vertex layout those members describe. If the
     * two drift, completion quietly offers fields the shader cannot compile against.
     */
    private fun verifyAbiMirrorsTheHead() {
        val js = MslEditorLanguage.javascript()
        // FieldVertex's four float4s, in the order the builder packs them.
        for (member in listOf("position", "normal", "color", "uv")) {
            require(js.contains("\"$member\"")) { "msl.js is missing the FieldVertex member '$member'" }
        }
        for (helper in listOf("fieldTime", "fieldDeltaTime", "fieldPredictedDisplayTime", "fieldUniform")) {
            require(js.contains(helper)) { "msl.js is missing the ABI helper '$helper'" }
        }
        require(VisionMetalKotlinJs.declarations.contains("fun geometry(")) {
            "the Kotlin/JS surface lost its geometry builder, so msl.js documents a uv that nothing writes"
        }
    }

    private val harness = """
        // Mock the AMD loader and the slice of the Monaco API msl.js touches, then drive the real
        // provider. Nothing here reimplements the module's logic — it only supplies a model.
        const fs = require("fs");
        const modulePath = process.argv[2];

        const registered = {};
        const monaco = {
            Range: class {
                constructor(sl, sc, el, ec) { this.startLineNumber = sl; this.startColumn = sc; this.endLineNumber = el; this.endColumn = ec; }
            },
            languages: {
                register(def) { registered.definition = def; },
                setLanguageConfiguration(id, config) { registered.config = config; },
                setMonarchTokensProvider(id, grammar) { registered.grammar = grammar; },
                registerCompletionItemProvider(id, provider) { registered.provider = provider; registered.providerLanguage = id; },
                CompletionItemKind: { Field: 3, Variable: 4, Function: 1, Snippet: 27, Struct: 22, Property: 9, Keyword: 17, TypeParameter: 24 },
                CompletionItemInsertTextRule: { InsertAsSnippet: 4 },
            },
        };

        global.define = (name, deps, factory) => { factory(() => {}, monaco); };
        global.define.amd = true;
        global.window = global;
        new Function("define", "window", fs.readFileSync(modulePath, "utf8"))(global.define, global.window);

        const fail = (message) => { console.error("FAIL: " + message); process.exitCode = 1; };
        const check = (condition, message) => { if (!condition) fail(message); };

        check(registered.definition && registered.definition.id === "msl", "language id should be msl");
        check(registered.definition.extensions.includes(".metal"), ".metal must map to this language");
        check(registered.grammar && registered.grammar.tokenizer && registered.grammar.tokenizer.root, "missing Monarch root state");
        check(registered.grammar.types.includes("FieldVertex"), "ABI structs should tokenize as types");
        check(registered.grammar.builtins.includes("fieldUniform"), "ABI helpers should tokenize as builtins");
        check(registered.provider && registered.providerLanguage === "msl", "no completion provider registered for msl");

        const makeModel = (text) => ({
            getValue: () => text,
            getValueInRange: ({ startLineNumber, startColumn, endLineNumber, endColumn }) => {
                const lines = text.split("\n");
                if (startLineNumber !== endLineNumber) throw new Error("harness only handles single-line ranges");
                return lines[startLineNumber - 1].slice(startColumn - 1, endColumn - 1);
            },
            getWordUntilPosition: (position) => {
                const line = text.split("\n")[position.lineNumber - 1];
                const before = line.slice(0, position.column - 1);
                const match = before.match(/[A-Za-z_]\w*${'$'}/);
                const word = match ? match[0] : "";
                return { word, startColumn: position.column - word.length, endColumn: position.column };
            },
        });
        const complete = (text, lineNumber, column) =>
            registered.provider.provideCompletionItems(makeModel(text), { lineNumber, column }).suggestions;

        // Member completion resolves the receiver's ABI struct from the surrounding declaration.
        const shader = [
            "vertex FieldVarying field_vertex(",
            "    constant FieldFrame &frame [[buffer(1)]])",
            "{",
            "    float t = frame.",
            "}",
        ].join("\n");
        const members = complete(shader, 4, "    float t = frame.".length + 1).map((s) => s.label);
        check(members.includes("timing"), "frame. should offer FieldFrame.timing, got " + members);
        check(members.includes("viewProjection"), "frame. should offer viewProjection");
        check(!members.includes("field_vertex"), "member completion must not fall through to globals");

        const vertexMembers = complete("    FieldVertex v = vertices[vertexID];\n    v.", 2, "    v.".length + 1).map((s) => s.label);
        check(vertexMembers.includes("uv"), "v. should offer FieldVertex.uv, got " + vertexMembers);
        check(vertexMembers.length === 4, "FieldVertex has exactly four members, got " + vertexMembers);

        const filtered = complete("    FieldFrame &frame;\n    frame.ti", 2, "    frame.ti".length + 1).map((s) => s.label);
        check(filtered.length === 1 && filtered[0] === "timing", "frame.ti should narrow to timing, got " + filtered);

        const unknown = complete("    float3 wibble;\n    wibble.", 2, "    wibble.".length + 1).map((s) => s.label);
        check(unknown.length > 4, "an unknown receiver should fall back to globals, not invent ABI members");

        // The u_NAME defines come from the sibling Kotlin box's marker, in declared slot order.
        global.window.currentSelectedCode = [{
            code: "// {{ visionmetal uniforms=phase,amount,tint }}\nonStart { uniform(\"phase\", 1.0) }",
        }];
        const declared = complete("float x = ", 1, 11).filter((s) => String(s.label).startsWith("u_"));
        check(declared.length === 3, "expected the three declared uniforms, got " + declared.map((s) => s.label));
        check(declared[0].label === "u_phase" && declared[0].detail.includes("slot 0"), "u_phase should be slot 0");
        check(declared[2].label === "u_tint" && declared[2].detail.includes("slot 2"), "u_tint should be slot 2");
        check(declared[0].documentation.value.includes("frame.user[0]"), "docs should show the generated define");

        // With no marker, inference has to agree with the server's, including order.
        global.window.currentSelectedCode = [{ code: "onStart { uniform(\"wobble\", 1.0); uniform(\"gain\", 2.0) }" }];
        const inferred = complete("float x = ", 1, 11).filter((s) => String(s.label).startsWith("u_")).map((s) => s.label);
        check(inferred.join(",") === "u_wobble,u_gain", "inference should preserve first-write order, got " + inferred);

        // No box selected must not throw, and must not invent uniform names.
        delete global.window.currentSelectedCode;
        const bare = complete("float x = ", 1, 11);
        check(bare.length > 0 && bare.every((s) => !String(s.label).startsWith("u_")), "no box selected -> no uniform names");

        const snippets = bare.filter((s) => s.kind === monaco.languages.CompletionItemKind.Snippet);
        check(snippets.length === 2, "expected field_vertex and field_fragment snippets, got " + snippets.length);
        const vertexSnippet = snippets.find((s) => s.label === "field_vertex");
        check(vertexSnippet.insertText.includes("[[amplification_id]]"), "the vertex snippet must index per eye");
        check(vertexSnippet.insertText.includes("out.uv = v.uv.xy"), "the vertex snippet should carry uv through");
        check(bare.some((s) => s.label === "[[stage_in]]"), "Metal attributes should be offered");
        check(bare.some((s) => s.label === "fieldTime" && s.insertText === "fieldTime(frame)"), "helpers insert with their argument");

        if (!process.exitCode) console.log("msl-language-ok: " + bare.length + " global suggestions");
    """.trimIndent()
}
