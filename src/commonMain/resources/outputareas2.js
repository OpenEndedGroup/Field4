import { aiChatMarkdownToHtml, aiChatHistoryToHtml, send } from "./afield.mjs";

const DEFAULT_W = 480;
const DEFAULT_H = 240;
const CLOSED_W = 200;

// --- Disclosure handling (new behavior) ---
function disclosureInterface(container) {
        if (container.__disclosureAPI) return container.__disclosureAPI;

        const disclosures = new Map(); // id -> { wrap, hdr, cw, arr }
        const states = new Map(); // id -> { open, width, height, scrollTop, scrollLeft }

        function ensureState(id) {
            if (!states.has(id)) {
                states.set(id, { open: false, width: DEFAULT_W, height: DEFAULT_H, scrollTop: 0, scrollLeft: 0 });
            }
            return states.get(id);
        }

        function saveScroll(id, d) {
                const st = ensureState(id);
                st.scrollTop = d.cw.scrollTop;
                st.scrollLeft = d.cw.scrollLeft;
                states.set(id, st);
        }

        function rememberSize(id, d) {
                if (d.cw.style.display === "none") return;
                const st = ensureState(id);
                const rect = d.cw.getBoundingClientRect();
                st.width = Math.max(rect.width, 50);
                st.height = Math.max(d.cw.clientHeight, 20);
                states.set(id, st);
                d.wrap.style.width = `${st.width}px`;
        }

        function applyClosed(d) {
                d.wrap.classList.remove("open");
                d.cw.style.display = "none";
                d.cw.style.height = "0px";
                d.cw.style.padding = "0px";
                d.wrap.style.width = `${CLOSED_W}px`;
        }

        function applyOpen(id, d) {
                const st = ensureState(id);
                const w = st.width || DEFAULT_W;
                const h = st.height || DEFAULT_H;
                d.wrap.classList.add("open");
                d.cw.style.display = "block";
                d.cw.style.padding = d.cw.dataset.origPadding || "0px";
                d.cw.style.width = `${w}px`;
                d.cw.style.height = `${h}px`;
                d.cw.scrollTop = st.scrollTop || 0;
                d.cw.scrollLeft = st.scrollLeft || 0;
                d.wrap.style.width = `${w}px`;
        }

        function ensureBehaviors(id, d) {
                d.cw.style.overflow = "auto";
                d.cw.style.resize = "both";
                d.cw.style.boxSizing = "border-box";
                d.cw.style.maxWidth = "none";
                d.cw.style.minHeight = "0px";

                if (!d.cw.dataset.origPadding) {
                        const cs = getComputedStyle(d.cw);
                        d.cw.dataset.origPadding = cs.padding;
                }

                d.cw.addEventListener("scroll", () => saveScroll(id, d));
                d.cw.addEventListener("mouseup", () => rememberSize(id, d));
                d.cw.addEventListener("mouseleave", () => rememberSize(id, d));
        }

        function open(id) {
                const d = disclosures.get(id);
                if (!d) return;
                const st = ensureState(id);
                if (!st.width) st.width = DEFAULT_W;
                if (!st.height) st.height = DEFAULT_H;
                applyOpen(id, d);
                st.open = true;
                states.set(id, st);
        }

        function close(id) {
                const d = disclosures.get(id);
                if (!d) return;
                rememberSize(id, d);
                saveScroll(id, d);
                const st = ensureState(id);
                st.open = false;
                states.set(id, st);
                applyClosed(d);
        }

        function toggle(id) {
                const st = ensureState(id);
                st.open ? close(id) : open(id);
        }

        function remove(id) {
                const d = disclosures.get(id);
                if (!d) return;
                d.wrap.remove();
                disclosures.delete(id);
                states.delete(id);
        }

        function closeAll() {
                for (const id of disclosures.keys()) {
                        close(id);
                }
        }

        function solo(id) {
                const st = ensureState(id);
                const wasOpen = st.open;
                closeAll();
                if (!wasOpen) {
                        open(id);
                }
        }

        function upsertDisclosure(id, title, content) {
                let d = disclosures.get(id);
                if (!d) {
                        const wrap = document.createElement("div");
                        wrap.className = "disclosure";
                        wrap.__disclosureId = id;
                        const hdr = document.createElement("div");
                        hdr.className = "disclosure-header";
                        const arr = document.createElement("div");
                        arr.className = "arrow";
                        const ttl = document.createElement("div");
                        ttl.className = "title";
                        ttl.textContent = title;
                        hdr.append(arr, ttl);
                        const cw = document.createElement("div");
                        cw.className = "disclosure-content";
                        cw.innerHTML = content || "";

                        // Handle arrow hover effects for alt key
                        arr.addEventListener("mouseenter", (e) => {
                                if (e.altKey) {
                                        arr.classList.add("remove-mode");
                                }
                        });

                        arr.addEventListener("mouseleave", (e) => {
                                arr.classList.remove("remove-mode");
                        });

                        // Track alt key state during hover
                        arr.addEventListener("mousemove", (e) => {
                                if (e.altKey) {
                                        arr.classList.add("remove-mode");
                                } else {
                                        arr.classList.remove("remove-mode");
                                }
                        });

                        hdr.addEventListener("click", (e) => {
                                if (!e.target.closest("input, label")) {
                                        if (e.altKey) {
                                                // Alt+click removes the disclosure
                                                remove(id);
                                        } else if (e.shiftKey) {
                                                // Shift+click solos this disclosure
                                                solo(id);
                                        } else {
                                                // Normal click toggles
                                                toggle(id);
                                        }
                                }
                        });

                        wrap.append(hdr, cw);
                        container.appendChild(wrap);
                        d = { wrap, hdr, cw, arr };
                        disclosures.set(id, d);
                        ensureBehaviors(id, d);
                        applyClosed(d);
                } else {
                        const { hdr, cw } = d;
                        hdr.querySelector(".title").textContent = title;
                        cw.innerHTML = content || "";
                }

                return disclosures.get(id).cw;
        }

        function setContent(id, content) {
                const d = disclosures.get(id);
                if (!d) return;
                d.cw.innerHTML = content || "";
                const st = ensureState(id);
                if (st.open) {
                        const rect = d.cw.getBoundingClientRect();
                        st.width = Math.max(rect.width, 50);
                        st.height = Math.max(d.cw.scrollHeight, 20);
                        states.set(id, st);
                        applyOpen(id, d);
                }
        }

        const api = { upsertDisclosure, setContent, toggle, open, close, remove, closeAll, solo };
        container.__disclosureAPI = api;
        return api;
}

window.disclosureInterface = disclosureInterface;
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

window.aiChatUpdate = aiChatUpdate;
window.openAiChatForBox = openAiChatForBox;
