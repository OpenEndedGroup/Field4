
import {send, sendWithReply} from "./afield.mjs"
//
// require.config({
//         paths: {
//                 'vs': document.location.protocol + "//"+document.location.host+'/min/vs', 'bithero': './',
//         }
// });
//
// window.MonacoEnvironment = {
//         getWorkerUrl: function (workerId, label) {
//                 return `data:text/javascript;charset=utf-8,${encodeURIComponent(`
//         self.MonacoEnvironment = {
//           baseUrl: ${document.location.protocol + "//"+document.location.host}+'/min/'
//         };
//         importScripts(${document.location.protocol + "//"+document.location.host}+'/min/vs/base/worker/workerMain.js');`)}`;
//         }
// };
//
// require(["vs/editor/editor.main"], function (e, is) {
//         require(["/min/vs/basic-languages/python/python.js"], function (e, is) {
//         });
//         require(["bithero/glsl"], function (is) {
//                 initMonaco()
//                 require(["/vs/platform/quickinput/common/quickInput.js"], function (is) {
//                         initQuickPick(is)
//                 })
//         })
// });


// require.config({
//         paths: {
//                 'vs': 'https://unpkg.com/monaco-editor@latest/min/vs', 'bithero': './',
//         }
// });

require.config({
        paths: {
                'vs':  document.location.protocol + "//"+document.location.host+'/min/vs', 'bithero': './',
        }
});

// window.MonacoEnvironment = {
//         getWorkerUrl: function (workerId, label) {
//                 return `data:text/javascript;charset=utf-8,${encodeURIComponent(`
//         self.MonacoEnvironment = {
//           baseUrl: 'https://unpkg.com/monaco-editor@latest/min/'
//         };
//         importScripts('https://unpkg.com/monaco-editor@latest/min/vs/base/worker/workerMain.js');`)}`;
//         }
// };

window.MonacoEnvironment = {
        getWorkerUrl: function (workerId, label) {
                return `data:text/javascript;charset=utf-8,${encodeURIComponent(`
        self.MonacoEnvironment = {
          baseUrl: '${document.location.protocol}//${document.location.host}/min/'
        };
        importScripts('${document.location.protocol}//${document.location.host}/min/vs/base/worker/workerMain.js');`)}`;
        }
};

require(["vs/editor/editor.main"], function (e, is) {
        require(["vs/basic-languages/python/python"], function (e, is) {
        });
        require(["vs/basic-languages/clojure/clojure"], function (e, is) {
        });
        require(["bithero/glsl"], function (is) {
                initMonaco()
                require(["vs/platform/quickinput/common/quickInput"], function (is) {
                        initQuickPick(is)
                })
        })
});


let currentEditorGeneration = 0
let lastErrorIs = []

const kindForIcon = (iconName) => {
        switch (iconName) {
                case 'property':
                        return 4
                case 'method':
                        return 0
                case 'keyword':
                        return 17
                case 'package':
                        return 21
                case 'class':
                        return 5
                default:
                        return 25
        }
}

let allPossibleProperties = ["code", "vertex", "fragment", "compute", "task", "mesh", "preamble", "metadata"]
let allPossibleProperties_descriptions = ["Kotlin (main)", "Vertex Shader", "Fragment Shader", "Compute Shader", "Task Shader", "Mesh Shader", "Kotlin (preamble)", "Metadata about this box"]
let allPossibleProperties_languages = ["kotlin", "glsl", "glsl", "glsl", "glsl", "glsl", "kotlin", "yaml"]

const findLanguageForPropertyName = (name) => {
        for (var i = 0; i < allPossibleProperties.length; i++) {
                if (allPossibleProperties[i] === name) return allPossibleProperties_languages[i]
        }
        return "kotlin"
}

function nextProperty(ed, inc) {

        for (var n = 0; n < allPossibleProperties.length + 1; n++) {
                let currentIndex = allPossibleProperties.indexOf(window.selectedProperty)
                let nextIndex = (currentIndex + inc + allPossibleProperties.length) % allPossibleProperties_languages.length

                window.selectedProperty = allPossibleProperties[nextIndex]

                if (currentSelectedCode[0][window.selectedProperty]) {
                        var lang = ""
                        // hack for now
                        if (window.selectedProperty == "code" && currentSelectedCode && currentSelectedCode[0] && currentSelectedCode[0].code_language) {
                                lang = currentSelectedCode[0].code_language
                                monaco.editor.setModelLanguage(globalEditor.getModel(), lang)
                        } else {
                                lang = allPossibleProperties_languages[currentIndex];
                                monaco.editor.setModelLanguage(globalEditor.getModel(), lang)
                        }

                        globalEditor._domElement.querySelectorAll(".hoverlay")[0].innerHTML = allPossibleProperties[currentIndex] + " <i>(" + lang + ")</i>"
                        break
                }
        }
}

const initMonaco = () => {
        console.log(" initializing monaco ")
        monaco.editor.defineTheme('dark', {
                base: 'vs-dark', inherit: true, rules: [], colors: {
                        "editor.background": '#1e1e1e', "editor.lineHighlightBackground": "#1e1e1e",
                },
        })

        let allPossibleProperties_keycodes = [monaco.KeyCode.KeyM, monaco.KeyCode.KeyV, monaco.KeyCode.KeyF, monaco.KeyCode.KeyM, monaco.KeyCode.KeyP, monaco.KeyCode.KeyD]

        monaco.editor.create(document.querySelector('.monaco-editor-container'), {
                value: ``,
                language: 'kotlin',
                theme: 'dark',
                automaticLayout: true,
                scrollBeyondLastLine: false,
                lineNumbersMinChars: 3,
                cursorStyle: "line",
                cursorBlinking: "smooth",
                matchBrackets: "never", // interferes with print decorations
                "bracketPairColorization.enabled": true,
                scrollbar: {
                        verticalScrollbarSize: 5
                }
        });

        let ed = monaco.editor.getEditors()[0]

        ed.updateOptions({fontFamily: "Iosevka Web", fontSize: 15, fontLigatures: true, margin: "5px"})
        monaco.editor.setModelLanguage(ed.getModel(), "kotlin");


        ed.updateOptions({
                quickSuggestions: false, wordWrap: "on", minimap: {enabled: false}, suggest: {showWords: false}
        })

        window.globalEditor = ed

        window.globalMonacoEditor = ed
        window.globalMonaco = monaco
        window.globalMonacoEditor._domElement.parentElement.parentElement.style.borderRadius = "5px"
        globalEditor.getModel().setEOL(0)

        window.addOutput = addOutput
        window.clear = clear

        for (let i = 0; i < allPossibleProperties.length; i++) {
                ed.addAction({
                        id: "switch-property-" + allPossibleProperties[i],
                        label: "Edit: " + allPossibleProperties_descriptions[i],
                        keybindings: [monaco.KeyMod.Alt | allPossibleProperties_keycodes[i]],
                        precondition: undefined,
                        keybindingContext: undefined,
                        contextMenuGroupId: "navigation",
                        contextMenuOrder: 2.0,
                        run: function (e) {
                                window.selectedProperty = allPossibleProperties[i]

                                var lang = ""
                                // hack for now
                                if (window.selectedProperty == "code" && currentSelectedCode && currentSelectedCode[0] && currentSelectedCode[0].code_language) {
                                        lang = currentSelectedCode[0].code_language
                                        monaco.editor.setModelLanguage(globalEditor.getModel(), lang)
                                } else {
                                        lang = allPossibleProperties_languages[i];
                                        monaco.editor.setModelLanguage(globalEditor.getModel(), lang)
                                }


                                globalEditor._domElement.querySelectorAll(".hoverlay")[0].innerHTML = allPossibleProperties[i] + " <i>(" + lang + ")</i>"

                        }
                })
        }

        ed.addAction({
                id: 'send-to-preamble',
                label: 'Move code to preamble',
                keybindings: [monaco.KeyMod.Alt | monaco.KeyMod.Shift | monaco.KeyCode.KeyP],
                precondition: 'editorHasSelection',
                run: function (ed) {
                        const model = ed.getModel();
                        const selection = ed.getSelection();
                        const selectedText = model.getValueInRange(selection);
                        currentSelectedCode[0].preamble = selectedText.trim() + "\n" + currentSelectedCode[0].preamble;
                        ed.executeEdits('remove-selection', [{
                                range: selection, text: '', forceMoveMarkers: true
                        }]);

                        send("set-string", {
                                uid: currentSelectedCode[0].uid,
                                property: "preamble",
                                value: currentSelectedCode[0].preamble
                        })

                        return null;
                }
        });

        ed.addAction({
                id: 'ai-chat',
                label: 'AI Chat in editor',
                keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyCode.Digit3],
                run: ed => {
                        const selection = ed.getSelection();
                        const start = selection.startLineNumber-1
                        const end = selection.endLineNumber - 1
                        const uid = currentSelectedCode[0].uid
                        const fullText = ed.getModel().getValue();

                        addPromptBox(ed, start, (v) => {
                                console.log(" ai chatbot ", v)
                                sendWithReply("ai-assist", {
                                        uid: uid,
                                        prompt: fullText,
                                        startLine: start,
                                        extraPrompt: v,
                                        property: currentSelectedProperty,
                                        kind: "chat"
                                }, "exec_" + uid, (message, payload) => {
                                        const original = message.originalName
                                        console.log(" ai assist returned text ", original, payload)

                                        let sel = globalEditor.getSelection()
                                        // hack to maintain undo
                                        globalEditor.executeEdits('ai_assist', [{
                                                identifier: 'delete',
                                                range: new monaco.Range(1, 1, 10000, 1),
                                                text: '',
                                                forceMoveMarkers: true
                                        }]);
                                        globalEditor.executeEdits('ai_assist', [{
                                                identifier: 'insert',
                                                range: new monaco.Range(1, 1, 1, 1),
                                                text: payload.value,
                                                forceMoveMarkers: true
                                        }]);
                                        globalEditor.setSelection(sel);

                                        // globalEditor.setValue(payload.value)

                                })
                        })
                }
        })


        ed.addAction({
                id: "ai-assist",
                label: "Apply Intelligence",
                keybindings: [monaco.KeyMod.Alt | monaco.KeyCode.KeyO],
                precondition: undefined,
                keybindingContext: undefined,
                contextMenuGroupId: "execution",
                contextMenuOrder: 1.5,
                run: function (e) {

                        function wrapSelectionOrCursor(editor) {
                                const model = editor.getModel();
                                const selection = editor.getSelection();
                                const fullText = model.getValue();

                                const startOffset = model.getOffsetAt(selection.getStartPosition());
                                const endOffset = model.getOffsetAt(selection.getEndPosition());

                                if (startOffset !== endOffset) {
                                        // There is a selection
                                        return (fullText.substring(0, startOffset) + '<rewrite>' + fullText.substring(startOffset, endOffset) + '</rewrite>' + fullText.substring(endOffset));
                                } else {
                                        // Only a cursor position
                                        return (fullText.substring(0, startOffset) + '<cursor>' + fullText.substring(startOffset));
                                }
                        }

                        let allText = wrapSelectionOrCursor(globalEditor)

                        console.log(" final text is", allText)

                        const uid = currentSelectedCode[0].uid

                        let tagIndex = allText.indexOf("<cursor>")
                        if (tagIndex === -1) {
                                tagIndex = allText.indexOf("<rewrite>")
                        }
                        let startLine = 0
                        if (tagIndex > 0) {
                                for (let i = 0; i < tagIndex; i++) {
                                        if (allText.charCodeAt(i) === 10) {
                                                startLine++
                                        }
                                }
                        }
                        sendWithReply("ai-assist", {
                                uid: uid,
                                prompt: allText,
                                startLine: startLine,
                                extraPrompt: "",
                                property: currentSelectedProperty,
                                kind: allText.indexOf("<cursor>") !== -1 ? "cursor" : "rewrite"
                        }, "exec_" + uid, (message, payload) => {
                                const original = message.originalName
                                console.log(" ai assist returned text ", original, payload)

                                let sel = globalEditor.getSelection()
                                // hack to maintain undo
                                globalEditor.executeEdits('ai_assist', [{
                                        identifier: 'delete',
                                        range: new monaco.Range(1, 1, 10000, 1),
                                        text: '',
                                        forceMoveMarkers: true
                                }]);
                                globalEditor.executeEdits('ai_assist', [{
                                        identifier: 'insert',
                                        range: new monaco.Range(1, 1, 1, 1),
                                        text: payload.value,
                                        forceMoveMarkers: true
                                }]);
                                globalEditor.setSelection(sel);

                                // globalEditor.setValue(payload.value)

                        })
                }

        })

        const div = document.createElement("div");
        div.className = "hoverlay";
        div.textContent = ".code";
        ed._domElement.appendChild(div)

        ed.addAction({
                id: "ai-assist-error",
                label: "Apply Intelligence to Error message",
                keybindings: [monaco.KeyMod.Alt | monaco.KeyMod.Shift | monaco.KeyCode.KeyO],
                precondition: () => lastErrorIs.length === 2,
                keybindingContext: undefined,
                contextMenuGroupId: "execution",
                contextMenuOrder: 1.5,
                run: function (e) {


                        console.log(" lasterror is ", lastErrorIs)

                        function wrapSelectionOrCursor(editor) {
                                const model = editor.getModel();

                                if (lastErrorIs[0] <= 0) lastErrorIs[0] = 1

                                const endColumn = model.getLineMaxColumn(lastErrorIs[0]);
                                const offset = model.getOffsetAt({lineNumber: lastErrorIs[0], column: endColumn});

                                const fullText = model.getValue();

                                return (fullText.substring(0, offset) + '<error>' + lastErrorIs[1].trim() + '</error>\n' + fullText.substring(offset));
                        }

                        let allText = wrapSelectionOrCursor(globalEditor)

                        console.log(" final text is", allText)

                        const uid = currentSelectedCode[0].uid

                        sendWithReply("ai-assist", {
                                uid: uid, prompt: allText, kind: "error",
                        }, "exec_" + uid, (message, payload) => {
                                const original = message.originalName
                                console.log(" ai assist returned text ", original, payload)

                                let sel = globalEditor.getSelection()
                                // hack to maintain undo
                                globalEditor.executeEdits('ai_assist', [{
                                        identifier: 'delete',
                                        range: new monaco.Range(1, 1, 10000, 1),
                                        text: '',
                                        forceMoveMarkers: true
                                }]);
                                globalEditor.executeEdits('ai_assist', [{
                                        identifier: 'insert',
                                        range: new monaco.Range(1, 1, 1, 1),
                                        text: payload.value,
                                        forceMoveMarkers: true
                                }]);
                                globalEditor.setSelection(sel);

                                // globalEditor.setValue(payload.value)

                        })
                }

        })

        ed.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KEY_P, () => ed.trigger('', 'editor.action.quickCommand', null));


        ed.addAction({
                id: 'toggle-fold-current-line',
                label: 'Toggle Fold on Current Line',
                keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyCode.Quote],
                run: ed => ed.trigger('keyboard', 'editor.toggleFold')
        })

        ed.addAction({
                id: 'forward-next-property',
                label: 'Next Property',
                keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyCode.Period],
                run: ed => nextProperty(ed, 1)
        })

        ed.addAction({
                id: 'back-next-property',
                label: 'Previous Property',
                keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyCode.Comma],
                run: ed => nextProperty(ed, -1)
        })

        ed.addAction({
                id: "execute-fragment",
                label: "Run Line / Selection",
                keybindings: [monaco.KeyMod.Alt | monaco.KeyCode.Enter],
                precondition: undefined,
                keybindingContext: undefined,
                contextMenuGroupId: "execution",
                contextMenuOrder: 1.5,
                run: function (e) {
                        let belowLine = 0
                        let singleLine = false

                        function getSelectedTextOrCurrentLine(editor) {
                                const selection = editor.getSelection();
                                if (!selection.isEmpty()) {
                                        belowLine = selection.startLineNumber - 1
                                        return editor.getModel().getValueInRange(selection);
                                } else {
                                        const lineNumber = selection.endLineNumber;

                                        let getLine = (n) => {
                                                if (n < 0) return undefined
                                                if (n >= editor.getModel().getLineCount()) return undefined
                                                return editor.getModel().getLineContent(n + 1);
                                        }

                                        let [es, start, end] = getEnclosingScope(lineNumber - 1, getLine)

                                        // todo: we should at least flash the effective selection
                                        belowLine = start
                                        singleLine = true

                                        return es.join("\n")

                                        // return selection
                                }
                        }

                        const code = getSelectedTextOrCurrentLine(e)

                        const currentGen = currentEditorGeneration

                        // requestAnimationFrame( () => { globalEditor._commandService.executeCommand("cursorEnd") })

                        const uid = currentSelectedCode[0].uid

                        sendWithReply("execute-fragment", {
                                uid: uid, type: "fragment", line: belowLine, code: code, singleLine: singleLine
                        }, "exec_" + uid, (message, payload) => {
                                handleOutput(payload, message, uid)
                                return true
                        })
                }
        })

        ed.addAction({
                id: "execute-all",
                label: "Run Box",
                keybindings: [monaco.KeyMod.Alt | monaco.KeyCode.Digit0],
                precondition: undefined,
                keybindingContext: undefined,
                contextMenuGroupId: "execution",
                contextMenuOrder: 2.0,
                run: function (e) {

                        const code = currentSelectedCode[0].code
                        const uid = currentSelectedCode[0].uid

                        const currentGen = currentEditorGeneration

                        sendWithReply("execute-all", {
                                uid: uid, type: "all", line: 0, code: code, singleLine: false
                        }, "exec_" + uid, (message, payload) => {

                                handleOutput(payload, message, uid)

                                return true
                        })
                }
        })

        ed.addAction({
                id: "launch-fragment",
                label: "Launch Line / Selection",
                keybindings: [monaco.KeyMod.Alt | monaco.KeyCode.Digit1],
                precondition: undefined,
                keybindingContext: undefined,
                contextMenuGroupId: "execution",
                contextMenuOrder: 1.5,
                run: function (e) {
                        let top = 0
                        let bottom = 0
                        let singleLine = false
                        let belowLine = 0


                        function getSelectedTextOrCurrentLine(editor) {
                                const selection = editor.getSelection();
                                if (!selection.isEmpty()) {
                                        belowLine = selection.endLineNumber - 1
                                        bottom = selection.endLineNumber
                                        top = selection.startLineNumber

                                        return editor.getModel().getValueInRange(selection);
                                } else {
                                        const lineNumber = selection.endLineNumber;

                                        let getLine = (n) => {
                                                if (n < 0) return undefined
                                                if (n >= editor.getModel().getLineCount()) return undefined
                                                return editor.getModel().getLineContent(n + 1);
                                        }

                                        let [es, start, end] = getEnclosingScope(lineNumber - 1, getLine)


                                        // todo: we should at least flash the effective selection
                                        belowLine = start
                                        singleLine = true

                                        top = start
                                        bottom = end

                                        console.log(" enclosing scope is", es)
                                        console.log(" starting from line ", lineNumber, start, end)
                                        return es.join("\n")
                                }
                        }


                        const code = getSelectedTextOrCurrentLine(e)

                        const className = addExecutionMarker(e, top + 1, bottom)

                        const currentGen = currentEditorGeneration

                        const uid = currentSelectedCode[0].uid

                        sendWithReply("launch-fragment", {
                                uid: uid,
                                type: "fragment",
                                line: belowLine,
                                code: code,
                                singleLine: singleLine,
                                strand: className,
                        }, "exec_" + uid, (message, payload) => {
                                handleOutput(payload, message, uid)
                                return true
                        })
                }
        })

        ed.addAction({
                id: "launch-all",
                label: "Launch Box",
                keybindings: [monaco.KeyMod.Alt | monaco.KeyCode.UpArrow],
                precondition: undefined,
                keybindingContext: undefined,
                contextMenuGroupId: "execution",
                contextMenuOrder: 2.0,
                run: function (e) {

                        const code = currentSelectedCode[0].code

                        const currentGen = currentEditorGeneration
                        const uid = currentSelectedCode[0].uid

                        launchAll(uid, code)
                }
        })

        ed.addAction({
                id: "stop-all",
                label: "Stop Box",
                keybindings: [monaco.KeyMod.Alt | monaco.KeyCode.DownArrow],
                precondition: undefined,
                keybindingContext: undefined,
                contextMenuGroupId: "execution",
                contextMenuOrder: 2.0,
                run: function (e) {

                        const code = currentSelectedCode[0].code

                        const currentGen = currentEditorGeneration
                        const uid = currentSelectedCode[0].uid

                        sendWithReply("stop-all", {
                                uid: uid,
                                type: "launch",
                                line: code.split("\n").length + 1,
                                code: code,
                                singleLine: false
                        }, "stop_" + uid, (message, payload) => {
                                return true
                        })
                }
        })

        installNumberCommands(ed)


        // register a no-keybinding command and get its ID
        const acceptCommandId = ed.addCommand(0, (ctx, ...args) => {
                ed.getModel().applyEdits([{
                        range: new monaco.Range(1, 1, 1, 1), text: args[0].trim() + "\n", forceMoveMarkers: true
                }]);
        }, '');

        var completionProvider = {
                provideCompletionItems: (model, position, context, cancellationToken) => {
                        var word = model.getWordUntilPosition(position);
                        var range = {
                                startLineNumber: position.lineNumber,
                                endLineNumber: position.lineNumber,
                                startColumn: word.startColumn,
                                endColumn: word.endColumn,
                        };

                        var completionInfo = {
                                allCode: model.getValue(), at: [position.lineNumber, position.column], word: word,
                        };

                        console.log(" complete me! ", completionInfo, range, ed.getModel().getOffsetAt(completionInfo))

                        const currentGen = currentEditorGeneration

                        var ret = new Promise(resolve => {

                                sendWithReply("complete", {
                                        uid: currentSelectedCode[0].uid,
                                        line: completionInfo.at[0],
                                        allCode: completionInfo.allCode,
                                        column: completionInfo.at[1],
                                        position: ed.getModel().getOffsetAt({
                                                column: completionInfo.at[1], lineNumber: completionInfo.at[0]
                                        })
                                }, "complete-me", (message, payload) => {

                                        if (currentEditorGeneration !== currentGen) return true
                                        var range

                                        console.log(" original location ", completionInfo.at[1], completionInfo.at[0])
                                        console.log(" now location ", payload.columnsStart, payload.lineStart)

                                        if (payload.useLineColumns) {
                                                range = {
                                                        startLineNumber: payload.lineStart,
                                                        endLineNumber: payload.lineStart,
                                                        startColumn: payload.columnsStart + 1,
                                                        endColumn: payload.columnsStart + 1,
                                                }
                                        } else {
                                                var start = ed.getModel().getPositionAt(payload.boundsStart)
                                                var end = ed.getModel().getPositionAt(payload.boundsEnd)
                                                range = {
                                                        startLineNumber: start.lineNumber,
                                                        endLineNumber: end.lineNumber,
                                                        startColumn: start.column,
                                                        endColumn: end.column
                                                }
                                        }

                                        let index = 0
                                        const answer = {
                                                suggestions: payload.metadata.map(m => {

                                                        let command = null
                                                        if (m.prefix) {
                                                                command = {
                                                                        id: acceptCommandId,
                                                                        title: '__completionAccepted_prefix',
                                                                        arguments: [m.prefix],
                                                                }
                                                        }
                                                        return {
                                                                label: m.displayText,
                                                                insertText: m.text,
                                                                range: range,
                                                                kind: kindForIcon(m.icon),
                                                                detail: m.tail,
                                                                sortText: (index++).toString(36).padStart(10, "0"),
                                                                documentation: {
                                                                        value: m.documentation,
                                                                        isTrusted: true,
                                                                        supportThemeIcons: true,
                                                                        supportHtml: true,
                                                                },
                                                                command: command
                                                        }
                                                })
                                        }

                                        console.log(" resolving. Answer is ", answer)
                                        resolve(answer)

                                        return true
                                })
                        })
                        return ret
                }
        }
        monaco.languages.registerCompletionItemProvider("kotlin", completionProvider)
        monaco.languages.registerCompletionItemProvider("python", completionProvider)
        monaco.languages.registerCompletionItemProvider("clojure", completionProvider)


        console.log(" monitoring selection ")
        monitorSelection()

        globalEditor.getModel().onDidChangeContent((event) => {
                clearAllDecorations()
        })

        beginLineTracking(globalEditor)
        initMarkerSupport(globalEditor)
}

let currentSelectedCode = []
let currentSelectedProperty = "code"
let currentValueAtOrigination = ""

window.currentSelectedCode = currentSelectedCode
window.currentSelectedProperty = currentSelectedProperty

let recentChangeCache = []

function notifyPotentialEditedPropertyHasHadLanguageChanged(uid, property, value) {
        if (currentSelectedCode && currentSelectedCode[0] && currentSelectedCode[0].uid === uid) {
                if (currentSelectedProperty === property) {
                        monaco.editor.setModelLanguage(globalEditor.getModel(), value);
                }
        }
}

function notifyPotentialEditedPropertyHasEditorStateRemoteUpdated(uid, property, value) {
        if (currentSelectedCode && currentSelectedCode[0] && currentSelectedCode[0].uid === uid) {
                if (currentSelectedProperty === property) {
                        // var v = JSON.parse(value)
                        // console.log(" restoring view state", v)
                        // globalEditor.restoreViewState(v)
                        // here we deliberately don't apply it, we're not interested in it
                }
        }
}

function notifyPotentialEditedPropertyHasBeenRemoteUpdated(uid, property, value) {
        if (currentSelectedCode && currentSelectedCode[0] && currentSelectedCode[0].uid === uid) {
                if (currentSelectedProperty === property) {
                        if (globalEditor.getValue() != value && currentValueAtOrigination != value) {

                                if (recentChangeCache.find(x => x == value)) return

                                console.log(" we have a simultaneous edit in progress ")
                                console.log(" common root is ", currentValueAtOrigination)
                                console.log(" current value here is ", globalEditor.getValue())
                                console.log(" incoming value is ", value)

                                let a = currentValueAtOrigination.split("\n")
                                let b = globalEditor.getValue().split("\n")
                                let c = value.split("\n")
                                let m = diff.mergeDigIn(b, a, c)
                                let v = m.result.join("\n")
                                if (v != globalEditor.getValue()) {
                                        console.log(" we've ended up with a real edit")
                                        console.log(v)
                                        console.log(globalEditor.getValue())

                                        let s = globalEditor.getSelection()

                                        globalEditor.setValue(v)
                                        globalEditor.setSelection(s)
                                        currentValueAtOrigination = v
                                        currentSelectedCode[0][property] = v
                                }
                        }
                }
        }
}

function notEqual(list1, list2) {
        if (list1.length !== list2.length) return true
        for (let i = 0; i < list1.length; i++) {
                if (list1[i] !== list2[i]) return true
        }
        return false
}

function monitorSelection() {
        const next = window.selectedList
        const nextProperty = window.selectedProperty

        if (currentSelectedCode.length === 1) {

                const codeDiv = currentSelectedCode[0]

                let nc = globalEditor.getModel().getValue()
                var vs = JSON.stringify(globalEditor.saveViewState())
                if (codeDiv[currentSelectedProperty + "_editorState"] !== vs) {
                        codeDiv[currentSelectedProperty + "_editorState"] = vs
                        send("set-string", {
                                uid: codeDiv.uid,
                                property: currentSelectedProperty + "_editorState",
                                value: vs
                        })
                }

                if (nc !== codeDiv[currentSelectedProperty]) {
                        codeDiv[currentSelectedProperty] = nc
                        currentValueAtOrigination = codeDiv[currentSelectedProperty]
                        recentChangeCache.push(currentValueAtOrigination)
                        if (recentChangeCache.length > 5) recentChangeCache.splice(0, 1)

                        send("set-string", {
                                uid: codeDiv.uid,
                                property: currentSelectedProperty,
                                value: codeDiv[currentSelectedProperty]
                        })
                        send("set-string", {
                                uid: codeDiv.uid,
                                property: currentSelectedProperty + "_editorState",
                                value: vs
                        })
                }
        }

        if (notEqual(next, currentSelectedCode) || nextProperty !== currentSelectedProperty) {

                currentEditorGeneration++
                if (next.length === 1) showMonaco()

                clear(globalEditor)

                currentSelectedCode = [...next]
                currentSelectedProperty = nextProperty

                window.currentSelectedCode = currentSelectedCode
                window.currentSelectedProperty = currentSelectedProperty

                if (currentSelectedCode.length === 1) {
                        let code = currentSelectedCode[0][currentSelectedProperty]
                        if (code === undefined) code = ""
                        var state = currentSelectedCode[0][currentSelectedProperty + "_editorState"]
                        if (state) {
                                const disposable = globalEditor.onDidChangeModelContent((event) => {
                                        if (event.isFlush) {
                                                disposable.dispose();
                                                setTimeout(() => {
                                                        globalEditor.restoreViewState(JSON.parse(state))
                                                        globalEditor.focus()
                                                }, 300)
                                        }
                                });
                        }
                        pauseLineTracking()
                        globalEditor.setValue(code.replaceAll("\r\n", "\n"))
                        globalEditor.getModel().setEOL(0)
                        unpauseLineTracking()
                        currentValueAtOrigination = code
                } else {
                        hideMonaco()
                }

                if (currentSelectedCode.length > 0) {
                        var lang = currentSelectedCode[0].code_language
                        if (!lang) lang = findLanguageForPropertyName(currentSelectedProperty)
                        monaco.editor.setModelLanguage(globalEditor.getModel(), lang.toLowerCase())
                        globalEditor._domElement.querySelectorAll(".hoverlay")[0].innerHTML = currentSelectedProperty + " <i>(" + lang + ")</i>"
                } else globalEditor._domElement.querySelectorAll(".hoverlay")[0].innerHTML = ""

        }

        setTimeout(monitorSelection, 100)
}

const setBoxRunning = (uid, isRunning, watermark, strand) => {
        // console.log("setBoxRunning, looking for ", uid, isRunning, watermark, strand)
        let first = [...document.querySelectorAll(".box")].find(x => x.uid === uid)
        let name = `${watermark}--${strand}`
        if (first) {
                // console.log(" is running ", first, first.runningList, watermark, isRunning)
                if (isRunning) {
                        first.classList.add("running");
                        first.runningList = first.runningList ?? new Set()
                        first.runningList.add(name)
                        setStatusForExecutionMarker(strand, true)

                } else {
                        first.runningList = first.runningList ?? new Set()

                        setStatusForExecutionMarker(strand, false)

                        if (first.runningList.delete(name)) {

                        } else {
                                // TODO
                                console.log(" tried to remove a watermark that wasn't there, just going to can the running list altogether")
                                first.runningList.clear()
                        }
                        setTimeout(() => {
                                if (first.runningList.size === 0) first.classList.remove("running")
                        }, 100)
                }
                // console.log(" outOfrunning ", first, first.runningList, watermark, isRunning)
        } else {
                console.log("warning: tried to set running status on something that doesn't exist?", uid)
        }
}
window.setBoxRunning = setBoxRunning

let hideAnimationGeneration = 0

const hideMonaco = () => {
        hideAnimationGeneration++
        let a = hideAnimationGeneration
        document.querySelector('.monaco-editor-container').style.transition = "opacity 0.5s"
        document.querySelector('.monaco-editor-container').style.opacity = "0"
        setTimeout(() => {
                if (hideAnimationGeneration === a) document.querySelector('.monaco-editor-container').style.visibility = "hidden"
        }, 500)
}

const showMonaco = () => {
        hideAnimationGeneration++
        document.querySelector('.monaco-editor-container').style.visibility = "visible"
        document.querySelector('.monaco-editor-container').style.transition = "opacity 0.05s"
        document.querySelector('.monaco-editor-container').style.opacity = "1"
}

const showActionMenu = () => {
        let isFocused = document.activeElement === document.querySelector("textarea.inputarea.monaco-mouse-cursor-text")
        if (isFocused) {
        } else {
                document.querySelector('.monaco-editor-container').style.opacity = "1"
                document.querySelector('.monaco-editor-container').style.visibility = "visible"
                document.querySelector("textarea.inputarea.monaco-mouse-cursor-text").focus()
                globalEditor.getAction("editor.action.quickCommand").run()
                document.querySelector(".monaco-findInput .input").focus()
                document.querySelector('.quick-input-widget').style.visibility = "visible"
                document.querySelector('.quick-input-widget').style.display = "block"
                document.querySelector('.quick-input-widget').style.boxShadow = "none"
                document.querySelector('.monaco-editor-container').style.visibility = "hidden"
        }
}

const showPickMenu_custom = (options) => {
        document.querySelector('.monaco-editor-container').style.opacity = "1"
        document.querySelector('.monaco-editor-container').style.visibility = "visible"
        document.querySelector("textarea.inputarea.monaco-mouse-cursor-text").focus()
        globalEditor.getAction("editor.action.quickCommand").run()
        document.querySelector(".monaco-findInput .input").focus()
        document.querySelector('.quick-input-widget').style.visibility = "visible"
        document.querySelector('.quick-input-widget').style.display = "block"
        document.querySelector('.quick-input-widget').style.boxShadow = "none"
        document.querySelector('.monaco-editor-container').style.visibility = "hidden"
        globalPickMenu_custom(options)
}

const showPickMenu = () => {
        document.querySelector('.monaco-editor-container').style.opacity = "1"
        document.querySelector('.monaco-editor-container').style.visibility = "visible"
        document.querySelector("textarea.inputarea.monaco-mouse-cursor-text").focus()
        globalPickMenu()
        document.querySelector("textarea.inputarea.monaco-mouse-cursor-text").blur()
        document.querySelector(".monaco-findInput .input").focus()
        document.querySelector('.quick-input-widget').style.visibility = "visible"
        document.querySelector('.quick-input-widget').style.display = "block"
        document.querySelector('.quick-input-widget').style.boxShadow = "none"
        document.querySelector('.monaco-editor-container').style.visibility = "hidden"
}


window.addEventListener("keydown", (e) => {
            const isCtrlOrCmdP = (e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'p';
            if (e.key === "F1" || isCtrlOrCmdP) {

                    let isFocused = document.activeElement === document.querySelector("textarea.inputarea.monaco-mouse-cursor-text")
                    if (isFocused) {
                            globalEditor.trigger("", "editor.action.quickCommand", null);
                            e.preventDefault();
                            e.stopImmediatePropagation();

                    } else {
                            e.preventDefault();
                            e.stopImmediatePropagation();
                            updateActions()
                            showPickMenu()
                    }
            }
    }, {capture: true}  // run before the browser’s own handlers
)

window.showActionMenu = showActionMenu


/**
 * Returns the largest group of lines that encloses line x in curly brackets.
 * If no such group exists, it returns the starting line (i.e. the line at index x).
 *
 * @param {number} x - The line number to check.
 * @returns {string[] | null} - An array of lines corresponding to the scope, or null if x is invalid.
 */
function getEnclosingScope(x, getLine) {
        // First, load all lines into an array.
        const lines = [];
        let i = 0;
        while (true) {
                const line = getLine(i);
                if (line === undefined) break;
                lines.push(line);
                i++;
        }

        // If x is out-of-bound, return null.
        if (x < 0 || x >= lines.length) {
                return null;
        }

        // Parse the lines to match up curly braces.
        // Each time we see a '{', we push its location onto a stack.
        // When we see a '}', we pop the last '{' and record the complete scope.
        const scopes = [];
        const stack = [];
        for (let i = 0; i < lines.length; i++) {
                const line = lines[i];
                for (let j = 0; j < line.length; j++) {
                        const char = line[j];
                        if (char === '{') {
                                // Save the position of the opening bracket.
                                stack.push({startLine: i, startIndex: j});
                        } else if (char === '}') {
                                if (stack.length > 0) {
                                        // Pop the matching opening brace.
                                        const scope = stack.pop();
                                        scope.endLine = i;
                                        scope.endIndex = j;
                                        scopes.push(scope);
                                }
                                // Unmatched '}' are simply ignored.
                        }
                }
        }

        // Find the outermost (largest) scope that encloses line x.
        let candidate = null;
        for (const scope of scopes) {
                if (x >= scope.startLine && x <= scope.endLine) {
                        // Update candidate if:
                        // - No candidate has been chosen yet, or
                        // - This scope starts earlier (is larger) or ends later.
                        if (candidate === null || scope.startLine < candidate.startLine || scope.endLine > candidate.endLine) {
                                candidate = scope;
                        }
                }
        }

        // If no scope encloses x, return the starting line as a one-element array.
        if (candidate === null) {
                return [[lines[x]], x, x];
        }

        // Return the lines of the enclosing scope (inclusive).
        return [lines.slice(candidate.startLine, candidate.endLine + 1), candidate.startLine, candidate.endLine + 1];
}

function setStatus(uid, good, text) {
        let first = [...document.querySelectorAll(".box")].find(x => x.uid === uid)
        if (first) {
                var s = first.querySelector(".statusArea")
                var tooltiptext = first.querySelector(".tooltiptext");

                if (good) {
                        s.classList.add("status-good")
                        s.classList.remove("status-error")
                        s.classList.remove("flash-inverted")
                        requestAnimationFrame(() => {
                                s.classList.add("flash-inverted")
                        })
                } else {
                        s.classList.remove("status-good")
                        s.classList.add("status-error")
                        s.classList.remove("flash-inverted")
                        requestAnimationFrame(() => {
                                s.classList.add("flash-inverted")
                        })
                }

                tooltiptext.innerHTML = text;
        }
}

function handleOutput(payload, message, uid) {
        const response = payload;
        const original = message.originalName
        // console.log(" handle output ", payload, message, uid)
        if (original === "output") {
                const line = response.line
                const html = response.html
                const kind = response.kind

                if (kind === "normal") {
                        setStatus(uid, true, html)
                        if (currentSelectedCode && currentSelectedCode[0] && currentSelectedCode[0].uid === uid) {
                                clearErrors(globalEditor)
                                addOutput(globalEditor, html, line, "normal", response.annotation, response.frame)
                        }
                } else if (kind === "ok") {
                        setStatus(uid, true, html)
                        if (currentSelectedCode && currentSelectedCode[0] && currentSelectedCode[0].uid === uid) {
                                clearErrors(globalEditor)
                                addOutput(globalEditor, html, line, "ok", response.annotation, response.frame)
                        }
                } else {
                        if (html === "") setStatus(uid, true, html); else setStatus(uid, false, html)
                        if (currentSelectedCode && currentSelectedCode[0] && currentSelectedCode[0].uid === uid) {
                                if (html === "") {
                                        clearErrors(globalEditor)
                                } else {
                                        addOutput(globalEditor, html, line, "error", response.annotation, response.frame)
                                        lastErrorIs = [line - 1, html]
                                }
                        }
                }
        }
        return true
}

function launchAll(uid, code) {
        sendWithReply("launch-all", {
                uid: uid, type: "launch", line: 0, code: code, singleLine: false
        }, "exec_" + uid, (message, payload) => {
                return handleOutput(payload, message, uid);
        })
}


window.notifyPotentialEditedPropertyHasBeenRemoteUpdated = notifyPotentialEditedPropertyHasBeenRemoteUpdated
window.notifyPotentialEditedPropertyHasEditorStateRemoteUpdated = notifyPotentialEditedPropertyHasEditorStateRemoteUpdated
window.notifyPotentialEditedPropertyHasHadLanguageChanged = notifyPotentialEditedPropertyHasHadLanguageChanged
window.hideMonaco = hideMonaco
window.launchAll = launchAll

