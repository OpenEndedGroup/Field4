// we should check to see if 'line' is deleted and delete the zone
// declare interface KnownOutput {
//         id: string
//         line: number
//         domNode: HTMLElement
//         domNodeInner: HTMLElement
// }

let knownOutputs = [] //: KnownOutput[]
let decorationsCollection = null
let decorationsCollection_contents = []

let executionDecorations = []
let executionsCollection = null

const debounceTimers = new Map();

function debounceTimeout(key, fn, delay, ...args) {
        if (debounceTimers.has(key)) {
                clearTimeout(debounceTimers.get(key));
        }

        const id = setTimeout(() => {
                debounceTimers.delete(key);
                fn(...args);
        }, delay);

        debounceTimers.set(key, id);
}

function clear(editor) {
        knownOutputs.forEach(x => {
                editor.changeViewZones(function (changeAccessor) {
                        changeAccessor.removeZone(x.id)
                })
                if (x.domNode.parentElement) x.domNode.parentElement.removeChild(x.domNode)
        })
        knownOutputs.splice(0, knownOutputs.length)

        if (decorationsCollection && decorationsCollection.clear) decorationsCollection.clear()
        if (executionsCollection && executionsCollection.clear) executionsCollection.clear()
}

function clearErrors(editor) {
        var toRemove = []
        knownOutputs.forEach(x => {
                if (x.kind === "error") {
                        editor.changeViewZones(function (changeAccessor) {
                                changeAccessor.removeZone(x.id)
                        })
                        if (x.domNode.parentElement) x.domNode.parentElement.removeChild(x.domNode)
                        toRemove.push(x)
                }
        })

        for (n of toRemove) {
                knownOutputs.splice(knownOutputs.indexOf(n), 1)
        }
}

function weakHash(d) {
        if (d.__weakheash__) return d.__weakheash__; else {
                d.__weakheash__ = Math.random()
                return d.__weakheash__
        }
}

function flash(d) {
        d.style.transition = "background 0s"
        d.style.background = "rgba(255,255,255,0.4)"
        debounceTimeout("blink" + weakHash(d), () => {
                d.style.transition = "background 0.3s"
                d.style.background = "rgba(0,0,0,1)"
        }, 20)
}

let order = 0

function removeNewestOutput() {
        let a = -1
        let aIs = null

        knownOutputs.forEach(x => {
                if (x.insertionOrder > a) {
                        a = x.insertionOrder
                        aIs = x
                }
        })

        if (aIs) {
                console.log(" removing ", aIs)
                window.globalMonacoEditor.changeViewZones(function (changeAccessor) {
                        changeAccessor.removeZone(aIs.id)
                })
                knownOutputs.splice(knownOutputs.indexOf(aIs), 1)
        }
}

function removeArea(id) {
        let aIs = null

        knownOutputs.forEach(x => {
                if (x.id == id) {
                        aIs = x
                }
        })

        if (aIs) {
                window.globalMonacoEditor.changeViewZones(function (changeAccessor) {
                        changeAccessor.removeZone(id)
                })
                knownOutputs.splice(knownOutputs.indexOf(aIs), 1)
        }
}

function toggleArea(id) {
        let aIs = null

        knownOutputs.forEach(x => {
                if (x.id == id) {
                        aIs = x
                }
        })

        if (aIs) {
                window.globalMonacoEditor.changeViewZones(function (changeAccessor) {
                        if (aIs.zone.heightInLines === aIs.big) aIs.zone.heightInLines = aIs.small; else aIs.zone.heightInLines = aIs.big
                        changeAccessor.layoutZone(id)
                        setTimeout(() => {
                                aIs.domNode.scrollTo({left: 0, top: 1000, behavior: "smooth"})
                        }, 100)
                })
        }
}


function addInlineOutput(editor, line, text, extra_class, frameMarker) {
        console.log("addInlineOutput", line, text)
        if (decorationsCollection == null) {
                decorationsCollection = editor.createDecorationsCollection([])
                decorationsCollection_contents = []
        }
        if (line >= editor.getModel().getLineCount()) line = editor.getModel().getLineCount()
        if (line == 0) line = 1
        while (line > 1 && editor.getModel().getLineLength(line) == 0) line--
        var l = editor.getModel().getLineLength(line)

        for (var i = decorationsCollection_contents.length - 1; i >= 0; i--) {
                if (decorationsCollection_contents[i].frameMarker < frameMarker) {
                        decorationsCollection_contents.splice(i, 1)
                }
                if (decorationsCollection_contents[i]._line === line) {
                        decorationsCollection_contents.splice(i, 1)
                }
        }

        decorationsCollection_contents.push({
                range: new monaco.Range(line, l, line, l + 1), options: {
                        after: {
                                content: text,
                                inlineClassName: "tick_text flash-inverted " + extra_class + " q" + Math.random(), // need a random class to force content refresh in the case of identical text to get the flash
                                inlineClassNameAffectsLetterSpacing: true,
                                cursorStops: 1
                        }, stickiness: 0, blockPadding: {left: 100},
                }, _line: line, _text: text, isTick: true, frameMarker: frameMarker
        })

        decorationsCollection.set(decorationsCollection_contents)
}

function addExecutionMarker(editor, start_line, end_line) {
        if (executionsCollection == null) executionsCollection = editor.createDecorationsCollection([])

        let n = Math.floor(performance.now())
        let className = `execution-marker-${n}`

        const sheet = document.styleSheets[0];
        const rule = `.${className} { background: black; width: 5px !important; margin-left:-15px;}`
        sheet.insertRule(rule, sheet.cssRules.length);

        let range = new monaco.Range(start_line, 1, end_line, 1)
        let index = 0
        let found = []
        for (var e of executionDecorations) {
                let rangeNow = executionsCollection.getRange(index)

                if (range.intersectRanges(rangeNow)) {
                        found.unshift(index)
                }
                index++
        }

        for (var f of found) {
                executionDecorations.splice(f, 1)
                executionsCollection.set(executionDecorations)
        }

        // this should be an append, but also a deletion of anything with an overlapping range
        const newDecoration = {
                range: range, options: {
                        isWholeLine: true, linesDecorationsClassName: `${className} running`
                }, _sourceClassname: className
        }
        executionsCollection.append([newDecoration])
        executionDecorations.push(newDecoration)

        console.log("decoration added", className)
        return className
}

function setStatusForExecutionMarker(className, running) {

        var index = 0
        executionDecorations.forEach(decor => {
                if (decor._sourceClassname === className) {
                        if (!running) decor.options.linesDecorationsClassName = className; else decor.options.linesDecorationsClassName = `${className} running`
                        decor.range = executionsCollection.getRange(index)
                }
                index++
        })

        if (executionsCollection) executionsCollection.set(executionDecorations)

        // this is wrong — we need to rewrite executionDecorations with new classList

        // let found = [...document.querySelectorAll(`.${className}`)]
        // for(var f of found) {
        //         if (!running)
        //                 f.classList.remove("running")
        //         else
        //                 f.classList.add("running")
        // }
        //
        //
}

function clearAllDecorations() {
        if (decorationsCollection) decorationsCollection.set([])
        decorationsCollection_contents = []
        // if (executionsCollection) executionsCollection.set([])

}


function looksLikeHTML(str) {
        if (typeof str !== "string") return false;
        const s = str.trim();

        const startMarkup = /^(?:<\?xml\b[^>]*\?>|<!DOCTYPE\b[^>]*>|<!--[\s\S]*?-->|<[A-Za-z][-\w:]*\b(?:\s[^>]*)?>)/;

        return startMarkup.test(s);
}

function chatKeyboardCallback(e, id, input) {
        if (e.isComposing) return true;

        if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                console.log(" -- enter ", id, input.value)
                knownOutputs.forEach(ko => {
                        if (ko.addPromptBox_uid === id) {
                                removeArea(ko.id)
                                console.log(" running callback for ", input.value)
                                ko.acceptCallback(input.value)
                        }
                })
                return false;
        }

        if (e.key === 'Escape') {
                e.preventDefault();
                console.log(" -- escape", id, input.value)
                knownOutputs.forEach(ko => {
                        if (ko.addPromptBox_uid === id) {
                                removeArea(ko.id)
                        }
                })
                return false;
        }

        return true;
}

window.chatKeyboardCallback = chatKeyboardCallback

var addPromptBox_uid = 0

function addPromptBox(editor, belowLine, acceptCallback) {
        editor.changeViewZones(function (changeAccessor) {
                var domNode = document.createElement("div");

                addPromptBox_uid++

                domNode.innerHTML = `<textarea
                  rows="1"
                  placeholder=">"
                  id="promptBox_${addPromptBox_uid}"
                  style="
                    box-sizing: border-box;
                    height: 30px;
                    padding: 8px;
                    margin: 3px;
                    border: 1px solid rgba(255, 255, 255, 0.1);
                    border-radius: 5px;
                    resize: vertical;
                    overflow: hidden;
                    font-style: inherit;
                    font-variant: inherit;
                    font-weight: inherit;
                    font-stretch: inherit;
                    line-height: inherit;
                    font-family: inherit;
                    font-optical-sizing: inherit;
                    font-size-adjust: inherit;
                    font-kerning: inherit;
                    font-feature-settings: inherit;
                    font-variation-settings: inherit;
                    font-size: 70%;
                    font-style: italic;
                    width: calc(100% - 20px);
                    outline: none;
                    color: white;
                    background: rgba(0,0,0,0.7);
                      "
                  oninput="this.style.height='auto';this.style.height=this.scrollHeight+'px';"
                  onfocus="this.style.height='auto';this.style.height=this.scrollHeight+'px';"
                  onkeydown="return window.chatKeyboardCallback(event, ${addPromptBox_uid}, this);"
                ></textarea>`

                domNode.style.zIndex = "100"

                let zone = {
                        afterLineNumber: belowLine,
                        heightInLines: 1,
                        domNode: domNode, // marginDomNode: domNodeControl,
                }

                const id = changeAccessor.addZone(zone);

                setTimeout(() => {
                        let steal = document.body.querySelector(`#promptBox_${addPromptBox_uid}`)
                        steal.focus()
                }, 100)

                knownOutputs.push({
                        id: id,
                        zone: zone,
                        big: 1,
                        small: 1,
                        line: belowLine,
                        domNode: domNode,
                        domNodeInner: null,
                        domNodeAnnotation: null,
                        insertionOrder: ++order,
                        kind: "",
                        addPromptBox_uid,
                        acceptCallback,
                })

        });
}

window.addPromptBox = addPromptBox

function addOutput(editor, text, belowLine, extraClass, annotation, frameMarker) {

        var textClean = text

        var isHTML = false
        if (looksLikeHTML(text)) {
                isHTML = true
        } else {
                textClean = textClean/*.trim()*/.replaceAll("\n", "<br>")
        }

        if (belowLine === -1) {
                belowLine = editor.getModel().getValue().split("\n").length
        }

        if (decorationsCollection_contents) {
                var changed = false
                for (var i = decorationsCollection_contents.length - 1; i >= 0; i--) {
                        if (decorationsCollection_contents[i].frameMarker < frameMarker) {
                                decorationsCollection_contents.splice(i, 1)
                                changed = true
                        }
                        if (decorationsCollection_contents[i]._line === belowLine) {
                                decorationsCollection_contents.splice(i, 1)
                                changed = true
                        }
                }
                if (changed) {
                        decorationsCollection.set(decorationsCollection_contents)
                }
        }

        let found = knownOutputs.find(x => x.line == belowLine)
        if (found) {

                if (isHTML) found.domNodeInner.innerHTML = textClean;
                else {
                        // found.domNodeInner.innerHTML += "<br>" + textClean
                        // found.domNodeInner.innerHTML = found.domNodeInner.innerHTML.replaceAll("<br><br>", "<br>")
                        appendWithBadge(found.domNodeInner, textClean)

                        requestAnimationFrame(() => {
                                if (found.domNodeInner.innerHTML.length > 10000) {
                                        // remove the first 50% of
                                        found.domNodeInner.children
                                }

                                var cr = found.domNodeInner.getBoundingClientRect()
                                console.log(" appended, new bounding rect is ", cr.height)
                                let l = Math.max(2, cr.height / 20)
                                found.big = l
                                found.small = Math.min(2, l)

                                // could auto expand here I think?

                        })
                }
                // found.domNodeInner.innerHTML = found.domNodeInner.innerHTML.replaceAll("<br><br>", "<br>")

                found.domNode.classList.remove(...found.domNode.classList)
                found.domNode.classList.add("execution_output")
                found.domNode.classList.add(extraClass)
                found.domNode.style.zIndex = "11"
                if (!isHTML) {
                        flash(found.domNodeInner)
                        setTimeout(() => {
                                found.domNode.scrollTo(0, found.domNodeInner.scrollHeight - 35)
                        }, 10)

                }
                debounceTimeout("align", () => {
                        clearTabSpans()
                        var n = 0
                        while (n++ < 10 && alignSpansWithinXPixels(150)) ;
                }, 50)

                return
        }

        let extraText = ""
        found = decorationsCollection_contents.find(x => x._line == belowLine)
        if (found) {
                if (found.isTick) {
                        decorationsCollection_contents.splice(decorationsCollection_contents.indexOf(found), 1)
                        decorationsCollection.set(decorationsCollection_contents)
                        found = false
                } else {
                        extraText += found._text
                        decorationsCollection_contents.splice(decorationsCollection_contents.indexOf(found), 1)
                        decorationsCollection.set(decorationsCollection_contents)
                }
        }

        if (!found && textClean.indexOf("<br>") == -1 && extraClass != "error" && !isHTML) {
                addInlineOutput(editor, belowLine, textClean, extraClass, frameMarker)
        } else {

                editor.changeViewZones(function (changeAccessor) {
                        var domNode = document.createElement("div");
                        domNode.style.overflow = "scroll";
                        domNode.classList.add(extraClass)
                        domNode.classList.add("execution_output")

                        var domNodeAnnotation = document.createElement("div");

                        var domNodeInner = document.createElement("div");
                        domNode.appendChild(domNodeInner)
                        // domNodeInner.innerHTML = textClean
                        appendWithBadge(domNodeInner, textClean)


                        domNode.appendChild(domNodeAnnotation)
                        domNodeAnnotation.innerHTML = annotation ?? ""
                        domNodeAnnotation.classList.add("output_annotation")


                        domNodeInner.onmousedown = (event) => {
                                console.log(" event !! ", event)
                        }

                        domNodeInner.classList.add("inner_output")
                        domNodeInner.style.background = "rgba(0,0,0,1)";
                        domNodeInner.style.borderRadius = "8px";
                        domNodeInner.style.marginBottom = "8px";
                        domNodeInner.style.width = "calc( 100% - 8px )";
                        domNodeInner.style.padding = "4px"

                        domNodeInner.style.marginTop = "3px"
                        domNodeInner.style.marginLeft = "3px"
                        domNodeInner.style.paddingLeft = "8px"
                        domNodeInner.style.overflowX = "scroll"

                        domNode.style.zIndex = "11"

                        domNodeControl = document.createElement("div")
                        domNodeControl.style.borderLeft = "5px solid rgba(0, 0, 0, 1)"
                        domNodeControl.style.marginLeft = "3px"
                        domNodeControl.classList.add("notselectable")

                        domNode.onwheel = (wheel) => {
                                domNode.scrollBy({top: wheel.deltaY})
                                wheel.preventDefault()
                                wheel.stopPropagation()
                                wheel.stopImmediatePropagation()
                        }

                        document.body.appendChild(domNode)
                        var cr = domNode.getBoundingClientRect()
                        document.body.removeChild(domNode)

                        var l = Math.min(5, textClean.split("\n").length) + 1
                        l = Math.max(2, cr.height / 20)


                        console.log(" -- added for real -- ", cr, l)
                        setTimeout(() => {
                                domNode.scrollTo(0, domNodeInner.scrollHeight - 41)
                        }, 100)
                        flash(domNodeInner)


                        let zone = {
                                afterLineNumber: belowLine, heightInLines: l, // line height of our Monoco editor?
                                domNode: domNode, marginDomNode: domNodeControl,
                        }


                        // bug — monaco / chrome eats the mousemove on <input slider> so that you can click it, but not drag it
                        for (let i = 0; i < domNodeInner.children.length; i++) {
                                domNodeInner.children[i].addEventListener("mousemove", (e) => {
                                        e.stopPropagation()
                                        e.stopImmediatePropagation()
                                })
                        }

                        const id = changeAccessor.addZone(zone);
                        domNodeControl.innerHTML = `<div class='closeBox hoverable' onclick='removeArea("${id}")'>&#215;</div><div class='toggleBox hoverable' onclick='toggleArea("${id}")'>&varr;</div>`

                        knownOutputs.push({
                                id: id,
                                zone: zone,
                                big: l,
                                small: Math.min(2, l),
                                line: belowLine,
                                domNode: domNode,
                                domNodeInner: domNodeInner,
                                domNodeAnnotation: domNodeAnnotation,
                                insertionOrder: ++order,
                                kind: extraClass
                        })
                });
        }

        debounceTimeout("align", () => {
                clearTabSpans()
                var n = 0
                while (n++ < 10 && alignSpansWithinXPixels(150)) ;
        }, 50)
}

// -- the tab stop, reinvented

function getInsetClientRectByMargin(el) {
        const rect = el.getBoundingClientRect();
        const style = window.getComputedStyle(el);
        const m = {
                top: parseFloat(style.marginTop) || 0,
                right: parseFloat(style.marginRight) || 0,
                bottom: parseFloat(style.marginBottom) || 0,
                left: parseFloat(style.marginLeft) || 0,
        };

        const inset = {
                top: rect.top + m.top,
                left: rect.left + m.left,
                right: rect.right - m.right,
                bottom: rect.bottom - m.bottom,
        };
        inset.width = inset.right - inset.left;
        inset.height = inset.bottom - inset.top;

        return inset;
}

function clearTabSpans(x = 50) {
        const spans = Array.from(document.querySelectorAll("span.tab"));
        spans.forEach(s => s.style = "")
}

function alignSpansWithinXPixels(x = 50) {
        const spans = Array.from(document.querySelectorAll("span.tab"));

        // Step 1: Get bounding boxes and group by horizontal proximity
        const spanData = spans.map(span => {
                const rect = getInsetClientRectByMargin(span);
                return {
                        span, left: rect.left, top: rect.top, height: rect.height, width: rect.width
                };
        });

        function closestTop(items, check) {
                if (items.length == 0) return 100
                const [closestGroup, minDist] = items.reduce(([bestG, bestD], g) => {
                        const d = Math.abs(g.top - check.top);
                        return d < bestD ? [g, d] : [bestG, bestD];
                }, [null, Infinity]);
                return minDist
        }

        // Group spans by their horizontal proximity
        const groups = [];
        spanData.forEach(item => {

                // let group = groups.find(g => Math.abs(g.avgLeft - item.left) <= x);

                const [closestGroup, minDist] = groups.reduce(([bestG, bestD], g) => {
                        const d = Math.abs(g.avgLeft - item.left);
                        return d < bestD ? [g, d] : [bestG, bestD];
                }, [null, Infinity]);

                // console.log("found in merge: ", closestGroup, minDist)
                let group;
                if (closestGroup && minDist <= x && closestTop(closestGroup.items, item) > 3) {
                        group = closestGroup;
                } else {
                        group = {items: [], avgLeft: item.leftp};
                        groups.push(group);
                }

                group.items.push(item);
                // Recompute average left
                group.avgLeft = group.items.reduce((sum, i) => Math.max(sum, i.left), 0);
        });

        // Step 2: For each group, calculate the max left position and pad the others
        var stop = false
        groups.forEach(group => {
                if (stop) return
                const maxLeft = Math.max(...group.items.map(i => i.left));
                // console.log(" looking at group ", group, group.items.length)
                if (group.items.length > 1) group.items.forEach(item => {
                        // console.log(item.span.textContent, item.span.left, maxLeft)
                        const padding = maxLeft - item.left;
                        console.log(" at ", item.span.textContent)
                        if (!item.span.style.marginLeft) {
                                item.span.style.marginLeft = (maxLeft - item.left) + "px"
                                console.log(" first application")
                                stop = true
                        } else if (item.span.style.left.endsWith("px") && Math.abs(parseFloat(item.span.style.marginLeft.replace("px", "") - (maxLeft - item.left))) > 2) {
                                console.log(" correction ", Math.abs(parseFloat(item.span.style.marginLeft.replace("px", "") - (maxLeft - item.left))))
                                item.span.style.marginLeft = (maxLeft - item.left) + "px"
                                stop = true
                        }
                }); //else console.log(" group too small")

        });
        return stop;
}


function appendWithBadge(div, msg) {
        msg = String(msg);

        // Check the last rendered log line (if any)
        const last = div.lastElementChild;
        const isLastLogLine = last && last.classList && last.classList.contains('log-line');

        if (isLastLogLine && last.dataset.msg === msg) {
                // Same message: bump the badge
                const badge = last.querySelector('.badge');
                const next = (Number(badge.dataset.count || 1) + 1);
                badge.dataset.count = String(next);
                badge.textContent = `×${next}`;
                badge.classList.add('show'); // becomes visible on first repeat
                return;
        }

        // Different (or no previous) message: create a new line
        const line = document.createElement('div');
        line.className = 'log-line';
        line.dataset.msg = msg; // store raw message for exact-match comparison

        const text = document.createElement('span');
        text.className = 'log-msg';
        text.innerHTML = msg;

        const badge = document.createElement('span');
        badge.className = 'badge';
        badge.dataset.count = '1'; // starts hidden; only shown on repeat

        line.append(text, badge);
        div.appendChild(line);

        // optional autoscroll
        div.scrollTop = div.scrollHeight;
}
