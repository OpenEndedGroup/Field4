import { aiChatMarkdownToHtml, aiChatHistoryToHtml, send } from "./afield.mjs";

const DEFAULT_W = 480;
const DEFAULT_H = 240;
const CLOSED_W = 200;
const INLINE_CHANNEL_PREFIX = "*";

// --- Disclosure handling (new behavior) ---
function disclosureInterface(container) {
        if (container.__disclosureAPI) return container.__disclosureAPI;

        const disclosures = new Map(); // id -> { wrap, hdr, cw, arr }
        const states = new Map(); // id -> { open, width, height, scrollTop, scrollLeft }

        function isInlineId(id) {
                return typeof id === "string" && id.startsWith(INLINE_CHANNEL_PREFIX);
        }

        function inlineTitle(title) {
                if (typeof title !== "string") return "";
                const cleaned = title.replace(/^\*+/, "").trim();
                return cleaned || title;
        }

        function ensureState(id) {
            if (!states.has(id)) {
                states.set(id, { open: false, width: DEFAULT_W, height: DEFAULT_H, scrollTop: 0, scrollLeft: 0 });
            }
            return states.get(id);
        }

        function saveScroll(id, d) {
                if (d.inline) return;
                const st = ensureState(id);
                st.scrollTop = d.cw.scrollTop;
                st.scrollLeft = d.cw.scrollLeft;
                states.set(id, st);
        }

        function rememberSize(id, d) {
                if (d.inline) return;
                if (d.cw.style.display === "none") return;
                const st = ensureState(id);
                const rect = d.cw.getBoundingClientRect();
                st.width = Math.max(rect.width, 50);
                st.height = Math.max(d.cw.clientHeight, 20);
                states.set(id, st);
                d.wrap.style.width = `${st.width}px`;
        }

        function applyClosed(d) {
                if (d.inline) return;
                d.wrap.classList.remove("open");
                d.cw.style.display = "none";
                d.cw.style.height = "0px";
                d.cw.style.padding = "0px";
                d.wrap.style.width = `${CLOSED_W}px`;
        }

        function applyOpen(id, d) {
                if (d.inline) return;
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
                if (d.inline) return;
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
                if (d.inline) {
                        const st = ensureState(id);
                        st.open = true;
                        states.set(id, st);
                        return;
                }
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
                if (d.inline) {
                        const st = ensureState(id);
                        st.open = false;
                        states.set(id, st);
                        return;
                }
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

        function removeAllExcept(id) {
                const ids = Array.from(disclosures.keys());
                for (const otherId of ids) {
                        if (otherId !== id) {
                                remove(otherId);
                        }
                }
        }

        function clearRemoveIndicators() {
                for (const d of disclosures.values()) {
                        if (d.inline) continue;
                        d.arr.classList.remove("remove-mode");
                }
        }

        function updateRemoveIndicators(id, event) {
                const current = disclosures.get(id);
                if (!current || current.inline) return;
                if (event.altKey && event.shiftKey) {
                        for (const [otherId, d] of disclosures.entries()) {
                                if (d.inline) continue;
                                d.arr.classList.toggle("remove-mode", otherId !== id);
                        }
                        return;
                }
                if (event.altKey) {
                        for (const [otherId, d] of disclosures.entries()) {
                                if (d.inline) continue;
                                d.arr.classList.toggle("remove-mode", otherId === id);
                        }
                        return;
                }
                clearRemoveIndicators();
        }

        function upsertDisclosure(id, title, content) {
                let d = disclosures.get(id);
                if (!d) {
                        const inline = isInlineId(id);
                        const wrap = document.createElement("div");
                        wrap.className = inline ? "disclosure disclosure-inline" : "disclosure";
                        wrap.__disclosureId = id;
                        const hdr = document.createElement("div");
                        hdr.className = inline ? "disclosure-header disclosure-header-inline no-hover" : "disclosure-header";
                        const arr = document.createElement("div");
                        arr.className = inline ? "inline-channel" : "arrow";
                        const ttl = document.createElement("div");
                        ttl.className = inline ? "inline-value" : "title";
                        if (inline) {
                                arr.textContent = inlineTitle(title);
                                ttl.innerHTML = content || "";
                        } else {
                                ttl.textContent = title;
                        }
                        hdr.append(arr, ttl);
                        const cw = document.createElement("div");
                        cw.className = "disclosure-content";
                        cw.innerHTML = content || "";

                        if (!inline) {
                                const updateIndicators = (e) => {
                                        updateRemoveIndicators(id, e);
                                };

                                arr.addEventListener("mouseenter", updateIndicators);
                                arr.addEventListener("mousemove", updateIndicators);
                                arr.addEventListener("mousedown", updateIndicators);
                                arr.addEventListener("mouseleave", () => clearRemoveIndicators());
                        }

                        hdr.addEventListener("click", (e) => {
                                if (!e.target.closest("input, label")) {
                                        if (inline) {
                                                if (e.altKey && e.shiftKey) {
                                                        removeAllExcept(id);
                                                } else if (e.altKey) {
                                                        remove(id);
                                                }
                                                return;
                                        }
                                        if (e.altKey && e.shiftKey) {
                                                // Alt+Shift+click removes all other disclosures
                                                removeAllExcept(id);
                                                clearRemoveIndicators();
                                        } else if (e.altKey) {
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
                        d = { wrap, hdr, cw, arr, inline, inlineValue: ttl };
                        disclosures.set(id, d);
                        ensureBehaviors(id, d);
                        if (!inline) {
                                applyClosed(d);
                        }
                } else {
                        const { hdr, cw, inline, arr, inlineValue } = d;
                        if (inline) {
                                arr.textContent = inlineTitle(title);
                                inlineValue.innerHTML = content || "";
                        } else {
                                hdr.querySelector(".title").textContent = title;
                                cw.innerHTML = content || "";
                        }
                }

                const current = disclosures.get(id);
                return current.inline ? current.inlineValue : current.cw;
        }

        function setContent(id, content) {
                const d = disclosures.get(id);
                if (!d) return;
                if (d.inline) {
                        d.inlineValue.innerHTML = content || "";
                        return;
                }
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

function buildAiChatPayload(uid, property, prompt, images = []) {
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
                kind: "general",
                images: images || []
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

        const preview = document.createElement("div");
        preview.className = "ai-chat-preview";
        preview.style.display = "none";
        preview.style.gap = "8px";
        preview.style.flexWrap = "wrap";
        preview.style.marginBottom = "6px";
        preview.style.alignItems = "center";

        const textarea = document.createElement("textarea");
        textarea.rows = 4;
        textarea.placeholder = "Ask about this box...";

        const sendButton = document.createElement("button");
        sendButton.textContent = "Send";
        sendButton.style.display = "none";

        function sendAiChat(payload) {
                send("ai-assist", payload);
        }

        const attachments = [];

        function renderPreview() {
                preview.innerHTML = "";
                if (attachments.length === 0) {
                        preview.style.display = "none";
                        return;
                }
                preview.style.display = "flex";
                attachments.forEach((src, idx) => {
                        const wrap = document.createElement("div");
                        wrap.className = "ai-chat-thumb";
                        wrap.style.position = "relative";

                        const img = document.createElement("img");
                        img.src = src;
                        img.alt = `attachment ${idx + 1}`;
                        img.style.maxWidth = "72px";
                        img.style.maxHeight = "72px";
                        img.style.borderRadius = "4px";
                        img.style.border = "1px solid rgba(255,255,255,0.25)";
                        img.style.display = "block";

                        const remove = document.createElement("button");
                        remove.textContent = "x";
                        remove.title = "Remove image";
                        remove.style.position = "absolute";
                        remove.style.top = "-6px";
                        remove.style.right = "-6px";
                        remove.style.width = "20px";
                        remove.style.height = "20px";
                        remove.style.borderRadius = "50%";
                        remove.style.border = "none";
                        remove.style.background = "#333";
                        remove.style.color = "#fff";
                        remove.style.cursor = "pointer";
                        remove.addEventListener("click", (e) => {
                                e.preventDefault();
                                attachments.splice(idx, 1);
                                renderPreview();
                        });

                        wrap.appendChild(img);
                        wrap.appendChild(remove);
                        preview.appendChild(wrap);
                });
        }

        function addImageAttachment(file) {
                const reader = new FileReader();
                reader.onload = (e) => {
                        const result = e.target?.result;
                        if (typeof result === "string") {
                                attachments.push(result);
                                renderPreview();
                        }
                };
                reader.readAsDataURL(file);
        }

        function submitPrompt() {
                const text = textarea.value.trim();
                if (!text && attachments.length === 0) return;
                textarea.value = "";
                const history = container.__aiChatHistory || "";
                const nextHistory = history ? history + "\n" : "";
                const imageMarkdown = attachments.map((src, idx) => `![image ${idx + 1}](${src})`).join("\n");
                const withImages = imageMarkdown
                        ? [text, imageMarkdown].filter((part) => part && part.length > 0).join("\n")
                        : text;
                container.__aiChatHistory = nextHistory + "<<you>>\n" + withImages;
                messages.innerHTML = aiChatHistoryToHtml(container.__aiChatHistory);
                applyCodeHighlight(messages);
                messages.scrollTop = messages.scrollHeight;
                const images = attachments.slice();
                sendAiChat(buildAiChatPayload(uid, root.dataset.property, text, images));
                attachments.length = 0;
                renderPreview();
        }

        textarea.addEventListener("keydown", (event) => {
            if (event.key === "Enter" && !event.shiftKey) {
                    event.preventDefault();
                    submitPrompt();
            }
        });
        sendButton.addEventListener("click", submitPrompt);

        textarea.addEventListener("paste", (event) => {
                const items = event.clipboardData?.items;
                if (!items || items.length === 0) return;
                let handled = false;
                for (let i = 0; i < items.length; i++) {
                        const item = items[i];
                        if (item && item.type && item.type.startsWith("image/")) {
                                const file = item.getAsFile();
                                if (file) {
                                        addImageAttachment(file);
                                        handled = true;
                                }
                        }
                }
                if (handled) {
                        event.preventDefault();
                }
        });

        inputWrap.appendChild(preview);
        inputWrap.appendChild(textarea);
        inputWrap.appendChild(sendButton);

        root.appendChild(messages);
        root.appendChild(inputWrap);
        container.innerHTML = "";
        container.appendChild(root);

        container.__aiChat = {root, messages, textarea, preview, attachments, uid, property: property || "code"};
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
