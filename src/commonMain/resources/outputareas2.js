
import { aiChatMarkdownToHtml, aiChatHistoryToHtml, send } from "./afield.mjs";

// helper: try to resize iframe to its content, call cb({ width, height }) if it works
function sizeIframeToContent(iframe, cb) {
        function measure() {
                let doc;
                try {
                        doc = iframe.contentDocument || iframe.contentWindow.document;
                } catch (e) {
                        // cross-origin: we can't inspect it
                        cb(null);
                        return;
                }
                if (!doc) {
                        cb(null);
                        return;
                }

                const body = doc.body;
                const html = doc.documentElement;

                const height = Math.max(body.scrollHeight, html.scrollHeight);
                const width  = Math.max(body.scrollWidth,  html.scrollWidth)-5;

                iframe.style.height = height + 'px';
                iframe.style.width  = "100%";//width  + 'px';

                cb({ width, height });
        }

        // if it's already loaded, measure immediately, otherwise wait
        try {
                const doc = iframe.contentDocument || iframe.contentWindow.document;
                if (doc && doc.readyState === 'complete') {
                        measure();
                } else {
                        iframe.addEventListener('load', () => measure(), { once: true });
                }
        } catch (e) {
                // cross-origin: can't even touch contentDocument
                cb(null);
        }
}


function disclosureInterface(container) {
        if (container.__disclosureAPI) return container.__disclosureAPI;
        const disclosures = {};

        function animateOpen(d) {
                const { wrap, cw } = d;

                wrap.style.width = 'auto';
                cw.style.display = 'block';

                const onlyChild =
                    cw.children.length === 1 ? cw.children[0] : null;

                // if single child is an iframe, try to use its "natural" size
                if (onlyChild && onlyChild.tagName === 'IFRAME') {
                        const iframe = onlyChild;

                        sizeIframeToContent(iframe, (size) => {
                                // if we couldn't measure (cross-origin), fall back to normal behavior
                                const targetHeight = size
                                    ? size.height + 'px'
                                    : cw.scrollHeight + 'px';

                                // start collapsed
                                cw.style.height = '0px';

                                // force reflow so the transition will apply
                                void cw.offsetHeight;

                                cw.style.transition = 'height 0.2s ease';

                                requestAnimationFrame(() => {
                                        cw.style.height = targetHeight;
                                        // width based on content width
                                        // wrap.style.width = (cw.scrollWidth + 2) + 'px';
                                        wrap.style.width = "100%"
                                });

                                // when done, set height to auto so it can grow/shrink later
                                cw.addEventListener('transitionend', function once(e) {
                                        if (e.target !== cw || e.propertyName !== 'height') return;
                                        cw.removeEventListener('transitionend', once);
                                        cw.style.height = 'auto';
                                        cw.style.transition = '';
                                });
                        });

                        return; // don't run the fallback below
                }

                // === fallback: original behavior when not a single iframe ===

                const height = cw.scrollHeight + 'px';
                cw.style.height = '0px';
                void cw.offsetHeight; // force reflow

                cw.style.transition = 'height 0.2s ease';

                requestAnimationFrame(() => {
                        cw.style.height = height;
                        wrap.style.width = "100%";//(cw.scrollWidth + 2) + 'px';
                });

                cw.addEventListener('transitionend', function once(e) {
                        if (e.target !== cw || e.propertyName !== 'height') return;
                        cw.removeEventListener('transitionend', once);
                        cw.style.height = 'auto';
                        cw.style.transition = '';
                });
        }

        function animateClose(d) {
                const {wrap, cw} = d;
                const height = cw.scrollHeight + 'px';
                cw.style.height = height;
                requestAnimationFrame(() => {
                        // cw.style.transition = 'height 0.2s ease';
                        cw.style.height = '0px';
                        cw.style.display = 'none';
                        cw.style.transition = '';
                        wrap.style.width = '200px';
                });
                // cw.addEventListener('transitionend', function once() {
                //         cw.removeEventListener('transitionend', once);
                // });
        }

        function upsertDisclosure(id, title, content) {
                let d = disclosures[id];
                if (!d) {
                        const wrap = document.createElement('div');
                        wrap.className = 'disclosure';
                        const hdr = document.createElement('div');
                        hdr.className = 'disclosure-header';
                        const arr = document.createElement('div');
                        arr.className = 'arrow';
                        const ttl = document.createElement('div');
                        ttl.className = 'title';
                        ttl.textContent = title;
                        hdr.append(arr, ttl);
                        if (content === null) hdr.classList.add('hide-arrow', 'no-hover');
                        const cw = document.createElement('div');
                        cw.className = 'disclosure-content';

                        cw.innerHTML = content || '';

                        hdr.addEventListener('mouseenter', () => wrap.classList.add('hover'));
                        hdr.addEventListener('mouseleave', () => wrap.classList.remove('hover'));
                        hdr.addEventListener('mousedown', (e) => {
                                if (!e.target.closest('input, label') && !hdr.classList.contains('no-hover')) toggle(id);
                        });
                        hdr.addEventListener('click', e => {
                                // if (!e.target.closest('input, label') && !hdr.classList.contains('no-hover')) toggle(id);
                        });
                        wrap.append(hdr, cw);
                        container.appendChild(wrap);
                        d = {wrap, hdr, cw, arr};
                        disclosures[id] = d;
                        return cw
                } else {
                        const {wrap, hdr, cw} = d;
                        hdr.querySelector('.title').textContent = title;
                        if (content === null) {
                                hdr.classList.add('hide-arrow', 'no-hover');
                                cw.innerHTML = '';
                                close(id);
                        } else {
                                hdr.classList.remove('hide-arrow', 'no-hover');
                                cw.innerHTML = content;
                                // if (wrap.classList.contains('open')) wrap.style.width = (cw.scrollWidth -2) + 'px';
                        }
                        return cw
                }
        }

        function setContent(id, content) {
                const d = disclosures[id];
                if (!d) return;
                return upsertDisclosure(id, d.hdr.querySelector('.title').textContent, content);
        }

        function toggle(id) {
                const d = disclosures[id];
                if (!d) return;
                d.wrap.classList.contains('open') ? close(id) : open(id);
        }

        function open(id) {
                const d = disclosures[id];
                if (d.hdr.classList.contains('no-hover')) return;
                if (d.wrap.classList.contains('open')) {
                        const width = d.cw.scrollWidth + 2;
                        d.wrap.style.width = width + 'px';
                        return;
                }
                d.wrap.classList.add('open');
                animateOpen(d);
        }

        function close(id) {
                const d = disclosures[id];
                d.wrap.classList.remove('open');
                animateClose(d);
        }

        const api = {upsertDisclosure, setContent, toggle, open, close};
        container.__disclosureAPI = api;
        return api;
}

window.aiChatMarkdownToHtml = aiChatMarkdownToHtml;
window.aiChatHistoryToHtml = aiChatHistoryToHtml;

function findBoxByUid(uid) {
        const boxes = document.getElementsByClassName("box");
        for (let i = 0; i < boxes.length; i++) {
                const box = boxes[i];
                if (box.uid === uid) return box;
        }
        return null;
}

function readBoxProperty(box, property) {
        if (!box) return "";
        if (property && typeof box[property] === "string") return box[property];
        if (typeof box.code === "string") return box.code;
        return "";
}

function buildAiChatPayload(uid, property, prompt) {
        let code = "";
        if (window.globalEditor && window.currentSelectedCode && window.currentSelectedCode.length === 1) {
                const currentUid = window.currentSelectedCode[0].uid;
                if (currentUid === uid && window.currentSelectedProperty === property) {
                        const model = window.globalEditor.getModel && window.globalEditor.getModel();
                        if (model) code = model.getValue();
                }
        }
        if (!code) {
                const box = findBoxByUid(uid);
                code = readBoxProperty(box, property);
        }
        return {
                uid: uid,
                prompt: code || "",
                startLine: 0,
                extraPrompt: prompt,
                property: property || "code",
                kind: "general"
        };
}

function ensureAiChat(container, uid, property) {
        if (container.__aiChat) {
                const existing = container.__aiChat;
                if (existing.root && container.contains(existing.root)) {
                        existing.uid = uid;
                        existing.property = property || existing.property;
                        return existing;
                }
        }

        const root = document.createElement("div");
        root.className = "ai-chat-window";
        root.dataset.uid = uid;
        root.dataset.property = property || "code";

        const messages = document.createElement("div");
        messages.className = "ai-chat-messages";
        messages.addEventListener("click", (event) => {
                const target = event.target;
                if (!(target instanceof HTMLElement)) return;
                const button = target.closest(".ai-chat-copy");
                if (!button) return;
                const block = button.closest(".ai-chat-code-block");
                if (!block) return;
                const code = block.querySelector("code");
                if (!code) return;
                const text = code.textContent || "";
                if (navigator.clipboard && navigator.clipboard.writeText) {
                        navigator.clipboard.writeText(text).then(() => {
                                if (typeof window.notify === "function") {
                                        window.notify("Copied to clipboard", "clipboard");
                                }
                        });
                } else {
                        const textarea = document.createElement("textarea");
                        textarea.value = text;
                        document.body.appendChild(textarea);
                        textarea.select();
                        document.execCommand("copy");
                        textarea.remove();
                        if (typeof window.notify === "function") {
                                window.notify("Copied to clipboard", "clipboard");
                        }
                }
        });

        const inputWrap = document.createElement("div");
        inputWrap.className = "ai-chat-input";

        const textarea = document.createElement("textarea");
        textarea.rows = 4;
        textarea.placeholder = "Ask about this box...";

        const sendButton = document.createElement("button");
        sendButton.textContent = "Send";
        sendButton.style.display = "none";

        function sendAiChat(payload) {
                send("ai-assist", payload);
        }

        function submitPrompt() {
                const text = textarea.value.trim();
                if (!text) return;
                textarea.value = "";
                const history = container.__aiChatHistory || "";
                const nextHistory = history ? history + "\n" : "";
                container.__aiChatHistory = nextHistory + "<<you>>\n" + text;
                messages.innerHTML = aiChatHistoryToHtml(container.__aiChatHistory);
                applyCodeHighlight(messages);
                messages.scrollTop = messages.scrollHeight;
                sendAiChat(buildAiChatPayload(uid, root.dataset.property, text));
        }

        textarea.addEventListener("keydown", (event) => {
            if (event.key === "Enter" && !event.shiftKey) {
                    event.preventDefault();
                    submitPrompt();
            }
        });
        sendButton.addEventListener("click", submitPrompt);

        inputWrap.appendChild(textarea);
        inputWrap.appendChild(sendButton);

        root.appendChild(messages);
        root.appendChild(inputWrap);
        container.innerHTML = "";
        container.appendChild(root);

        container.__aiChat = {root, messages, textarea, uid, property: property || "code"};
        return container.__aiChat;
}

function applyCodeHighlight(container) {
        if (!container) return;
        if (!window.hljs || typeof window.hljs.highlightElement !== "function") return;
        const blocks = container.querySelectorAll("pre code");
        blocks.forEach((block) => window.hljs.highlightElement(block));
}

function aiChatUpdate(container, uid, markdown) {
        const chat = ensureAiChat(container, uid, container.__aiChat?.property || "code");
        container.__aiChatHistory = markdown || "";
        chat.messages.innerHTML = aiChatHistoryToHtml(container.__aiChatHistory);
        applyCodeHighlight(chat.messages);
        chat.messages.scrollTop = chat.messages.scrollHeight;
}

function openAiChatForBox(uid, property) {
        const box = findBoxByUid(uid);
        if (!box) return;
        const outputArea = box.querySelector(".outputArea");
        if (!outputArea) return;
        outputArea.style.visibility = "visible";
        const contain = outputArea.querySelector(".disclosure-group");
        if (!contain) return;
        const inter = disclosureInterface(contain);
        const content = inter.upsertDisclosure("ai-chat", "AI Chat", "");
        const chat = ensureAiChat(content, uid, property || "code");
        inter.open("ai-chat");
        setTimeout(() => {
                chat.textarea.focus();
        }, 0);
}

window.disclosureInterface = disclosureInterface;
window.aiChatUpdate = aiChatUpdate;
window.openAiChatForBox = openAiChatForBox;
