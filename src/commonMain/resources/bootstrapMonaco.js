
import {
        sendWithReply,
        monacoInit,
        monacoRegisterActions,
        monacoStartSelectionMonitor,
        monacoHandleOutput,
        monacoLaunchAll,
        monacoKindForIcon,
        monacoGetEnclosingScope
} from "./afield.mjs"

require.config({
        paths: {
                'vs':  document.location.protocol + "//"+document.location.host+'/min/vs', 'bithero': './',
        }
});

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
        require(["vs/basic-languages/yaml/yaml"], function (e, is) {
        });
        require(["bithero/glsl"], function (is) {
                initMonaco()
                require(["vs/platform/quickinput/common/quickInput"], function (is) {
                        initQuickPick(is)
                })
        })
});


window.currentEditorGeneration = window.currentEditorGeneration || 0
window.lastErrorIs = window.lastErrorIs || []
window.currentSelectedCode = window.currentSelectedCode || []
window.currentSelectedProperty = window.currentSelectedProperty || "code"

const kindForIcon = monacoKindForIcon

const initMonaco = () => {
        console.log(" initializing monaco ")
        let ed = monacoInit(monaco) || monaco.editor.getEditors()[0]

        monacoRegisterActions(ed, monaco)

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
                        var languageId = model.getLanguageId()
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

                        const currentGen = window.currentEditorGeneration

                        var ret = new Promise(resolve => {

                                sendWithReply("complete", {
                                        uid: window.currentSelectedCode[0].uid,
                                        line: completionInfo.at[0],
                                        allCode: completionInfo.allCode,
                                        column: completionInfo.at[1],
                                        position: ed.getModel().getOffsetAt({
                                                column: completionInfo.at[1], lineNumber: completionInfo.at[0]
                                        }),
                                        property: window.currentSelectedProperty
                                }, "complete-me", (message, payload) => {

                                        if (window.currentEditorGeneration !== currentGen) return true
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
                                                                data: {
                                                                        source: languageId,
                                                                        displayText: m.displayText,
                                                                        insertText: m.text,
                                                                        detail: m.tail,
                                                                        aiDocPending: false,
                                                                        aiDocResolved: false
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
                },
                resolveCompletionItem: (item, cancellationToken) => {
                        try {
                                if (!item || !item.data) return item
                                if (item.data.aiDocResolved || item.data.aiDocPending) return item
                                if (item.data.source !== "kotlin") return item
                                if (!globalEditor || !globalEditor.getModel) return item
                                var model = globalEditor.getModel()
                                if (!model) return item
                                var pos = globalEditor.getPosition()
                                if (!pos) return item

                                item.data.aiDocPending = true
                                var rawLabel = item.label
                                var labelText = (typeof rawLabel === "string") ? rawLabel : ((rawLabel && rawLabel.label) ? rawLabel.label : "")
                                var payload = {
                                        uid: window.currentSelectedCode[0].uid,
                                        property: window.currentSelectedProperty,
                                        label: labelText || "",
                                        insertText: item.data.insertText || item.insertText || "",
                                        detail: item.data.detail || item.detail || "",
                                        displayText: item.data.displayText || labelText || "",
                                        existingDocumentation: (item.documentation && item.documentation.value) ? item.documentation.value : "",
                                        allCode: model.getValue(),
                                        line: pos.lineNumber,
                                        column: pos.column,
                                        position: model.getOffsetAt({lineNumber: pos.lineNumber, column: pos.column})
                                }

                                return new Promise(resolve => {
                                        sendWithReply("resolve-completion", payload, "resolve-completion", (message, payload) => {
                                                if (payload && payload.documentation) {
                                                        var existing = (item.documentation && item.documentation.value) || ""
                                                        var combined = existing ? (existing + "\n\n---\n\n" + payload.documentation) : payload.documentation
                                                        item.documentation = {
                                                                value: combined,
                                                                isTrusted: true,
                                                                supportThemeIcons: true,
                                                                supportHtml: true,
                                                        }
                                                }
                                                item.data.aiDocPending = false
                                                item.data.aiDocResolved = true
                                                resolve(item)
                                                return true
                                        })
                                })
                        } catch (e) {
                                if (item && item.data) {
                                        item.data.aiDocPending = false
                                        item.data.aiDocResolved = true
                                }
                                return item
                        }
                }
        }
        monaco.languages.registerCompletionItemProvider("kotlin", completionProvider)
        monaco.languages.registerCompletionItemProvider("python", completionProvider)
        monaco.languages.registerCompletionItemProvider("clojure", completionProvider)
        monaco.languages.registerCompletionItemProvider("glsl", completionProvider)
        monaco.languages.registerCompletionItemProvider("yaml", completionProvider)


        console.log(" monitoring selection ")
        monacoStartSelectionMonitor()

        globalEditor.getModel().onDidChangeContent((event) => {
                clearAllDecorations()
        })

        beginLineTracking(globalEditor)
        initMarkerSupport(globalEditor)
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
        if (window.triggerQuickInput) {
                triggerQuickInput(() => {
                        globalEditor.getAction("editor.action.quickCommand").run()
                })
        } else {
                globalEditor.getAction("editor.action.quickCommand").run()
        }
}

const showPickMenu_custom = (options) => {
        if (window.triggerQuickInput) {
                triggerQuickInput(() => {
                        globalPickMenu_custom(options)
                })
        } else {
                globalPickMenu_custom(options)
        }
}

// Expose for Kotlin/JS callers (e.g., insertFromWorkspace in commands.kt)
window.showPickMenu_custom = showPickMenu_custom

const showPickMenu = () => {
        if (window.triggerQuickInput) {
                triggerQuickInput(() => {
                        globalPickMenu()
                })
        } else {
                globalPickMenu()
        }
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
        return monacoGetEnclosingScope(x, getLine)
}

window.handleOutput = monacoHandleOutput
window.hideMonaco = hideMonaco
window.showMonaco = showMonaco
window.launchAll = monacoLaunchAll

// Documentation iframe support
let docsIframeAnimationGeneration = 0

const showDocsIframe = () => {
	docsIframeAnimationGeneration++
	const container = document.querySelector('.docs-iframe-container')
	if (container) {
		container.style.visibility = "visible"
		container.style.transition = "opacity 0.05s"
		container.style.opacity = "1"
	}
}

const hideDocsIframe = () => {
	docsIframeAnimationGeneration++
	let a = docsIframeAnimationGeneration
	const container = document.querySelector('.docs-iframe-container')
	if (container) {
		container.style.transition = "opacity 0.5s"
		container.style.opacity = "0"
		setTimeout(() => {
			if (docsIframeAnimationGeneration === a) {
				container.style.visibility = "hidden"
			}
		}, 500)
	}
}

const setDocsIframeContent = (url) => {
	const iframe = document.getElementById('docs-iframe')
	if (iframe) {
		iframe.src = url
	}
}

const getDocsIframeElement = () => {
	return document.getElementById('docs-iframe')
}

// Switch between Monaco editor and documentation iframe
const switchToMonaco = () => {
	hideDocsIframe()
	showMonaco()
}

const switchToDocsIframe = (url) => {
	hideMonaco()
	if (url) {
		setDocsIframeContent(url)
	}
	showDocsIframe()
}

window.showDocsIframe = showDocsIframe
window.hideDocsIframe = hideDocsIframe
window.setDocsIframeContent = setDocsIframeContent
window.getDocsIframeElement = getDocsIframeElement
window.switchToMonaco = switchToMonaco
window.switchToDocsIframe = switchToDocsIframe


// fails to inline with js()
window._saveAsTemplateHelper = function (selectedIds) {
        let ed = window.ensureEditor && window.ensureEditor();
        if (!ed) {
                console.error("No editor available for quick input");
                return;
        }
        let cmd = ed.addCommand(0, (accessor, func) => {
                let quickInputService = accessor.get(window.globalIS.IQuickInputService);
                func(quickInputService);
        });
        ed.trigger("", cmd, (quickInput) => {
                let inputBox = quickInput.createInputBox();
                inputBox.placeholder = "Enter template name";
                inputBox.prompt = "Save selection as template";
                inputBox.onDidAccept(() => {
                        let templateName = inputBox.value.trim();

                        // Comprehensive validation
                        if (!templateName) {
                                return;
                        }

                        // Check for invalid characters and patterns
                        let invalidPatterns = [
                                /[\/\\]/,  // Path separators
                                /\.\./,    // Parent directory
                                /^\.$/,    // Current directory
                                /[\x00]/,  // Null bytes
                                /^\s/,     // Leading space
                                /\s$/      // Trailing space
                        ];

                        let reservedNames = ['metadata', 'con', 'prn', 'aux', 'nul',
                                'com1', 'com2', 'com3', 'com4', 'com5',
                                'com6', 'com7', 'com8', 'com9',
                                'lpt1', 'lpt2', 'lpt3', 'lpt4', 'lpt5',
                                'lpt6', 'lpt7', 'lpt8', 'lpt9'];

                        for (let pattern of invalidPatterns) {
                                if (pattern.test(templateName)) {
                                        import("./afield.mjs").then((afield) => {
                                                afield.notify("Invalid template name. Avoid path separators and special characters.", "error");
                                        });
                                        return;
                                }
                        }

                        if (reservedNames.includes(templateName.toLowerCase())) {
                                import("./afield.mjs").then((afield) => {
                                        afield.notify("Invalid template name. This is a reserved name.", "error");
                                });
                                return;
                        }

                        inputBox.hide();
                        import("./afield.mjs").then((afield) => {
                                afield.send("save-as-template", {
                                        uids: selectedIds,
                                        templateName: templateName
                                });
                        });
                });
                inputBox.onDidHide(() => {
                        inputBox.dispose();
                        delete window._saveAsTemplateHelper;
                });
                inputBox.show();
        });
}
