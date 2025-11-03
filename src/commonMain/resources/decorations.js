let decor = null

function clearDecorations() {
        if (decor) decor.clear()
        else decor = globalEditor.createDecorationsCollection({})
}

const dynamicStyle = (() => {
        const styleEl = document.createElement('style');
        styleEl.id = 'dynamic-styles';
        document.head.appendChild(styleEl);
        return styleEl;
})();

function defineCssClasses(rules) {
        dynamicStyle.innerText = rules;
}

function setDecorCallbackTable(callbacks) {

        for (const key in callbacks) {
                if (Object.prototype.hasOwnProperty.call(callbacks, key)) {
                        const value = callbacks[key];
                        masterMousedownCallbackTable[key] = value;
                }
        }
}

function setDecorationsForEditor(uid, property, box, decorSpec) {

        if (currentSelectedCode && currentSelectedCode[0] && currentSelectedCode[0].uid === uid) {
                if (currentSelectedProperty === property) {
                        clearDecorations()
                        defineCssClasses(decorSpec.definition);
                        setDecorCallbackTable(decorSpec.callbacks)

                        let d = decorSpec.inline.map((e) => {
                                return {
                                        "range": new monaco.Range(e[0], e[1], e[2], e[3]),
                                        "options": {"inlineClassName": e[4]},
                                }
                        })

                        decor.set(d)

                }
        }
        box.decorSpec = decorSpec
}
