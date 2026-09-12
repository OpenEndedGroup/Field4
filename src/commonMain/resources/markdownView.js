import { aiChatMarkdownToHtml } from "./afield.mjs";
import { classifyGuideLink, guideAssetUrl, headingSlug } from "./documentGuideModel.js";

export const safeMarkdownUrl = (url, allowImageData = false) => {
        const raw = String(url || "").trim()
        if (!raw) return ""
        try {
                const parsed = new URL(raw, window.location.href)
                const protocol = parsed.protocol.toLowerCase()
                if (protocol === "http:" || protocol === "https:" || protocol === "mailto:" || (!allowImageData && protocol === "field:")) {
                        return raw
                }
                if (allowImageData && protocol === "data:" && /^data:image\/(png|jpeg|gif|webp);base64,/i.test(raw)) {
                        return raw
                }
                if (protocol === window.location.protocol && !/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(raw)) {
                        return raw
                }
        } catch (_) {
                if (raw.startsWith("#") || raw.startsWith("/") || raw.startsWith("./") || raw.startsWith("../")) {
                        return raw
                }
        }
        return ""
}

const sanitizeNotesMarkdown = (root) => {
        root.querySelectorAll("a[href]").forEach((link) => {
                const safe = safeMarkdownUrl(link.getAttribute("href"))
                if (safe) {
                        link.setAttribute("href", safe)
                        link.setAttribute("rel", "noopener noreferrer")
                } else {
                        link.removeAttribute("href")
                }
        })
        root.querySelectorAll("img[src]").forEach((img) => {
                const safe = safeMarkdownUrl(img.getAttribute("src"), true)
                if (safe) {
                        img.setAttribute("src", safe)
                        img.setAttribute("loading", "lazy")
                } else {
                        img.remove()
                }
        })
}

const escapeCodeHtml = (value) => String(value ?? "")
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;")

const normalizeCodeFenceLanguage = (language) => {
        const raw = String(language || "").trim().toLowerCase().replace(/^language-/, "")
        if (!raw) return ""
        const aliases = {
                kt: "kotlin",
                kts: "kotlin",
                glsl: "glsl",
                frag: "glsl",
                fragment: "glsl",
                vert: "glsl",
                vertex: "glsl",
                comp: "glsl",
                compute: "glsl",
                shader: "glsl",
                metal: "msl",
                msl: "msl",
                bend: "bend",
                js: "javascript",
                ts: "typescript",
                sh: "bash",
                shell: "bash"
        }
        return aliases[raw] || raw
}

const glslKeywordSet = new Set([
        "attribute", "break", "buffer", "case", "centroid", "coherent", "const", "continue", "default",
        "discard", "do", "else", "flat", "for", "highp", "if", "in", "inout", "invariant", "layout",
        "lowp", "mediump", "noperspective", "out", "patch", "precision", "readonly", "restrict",
        "return", "sample", "shared", "smooth", "struct", "subroutine", "switch", "uniform", "varying",
        "volatile", "while", "writeonly"
])

const glslTypeSet = new Set([
        "void", "bool", "int", "uint", "float", "double",
        "vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4", "uvec2", "uvec3", "uvec4", "bvec2", "bvec3", "bvec4",
        "dvec2", "dvec3", "dvec4", "mat2", "mat3", "mat4", "mat2x2", "mat2x3", "mat2x4", "mat3x2", "mat3x3",
        "mat3x4", "mat4x2", "mat4x3", "mat4x4", "dmat2", "dmat3", "dmat4", "sampler1D", "sampler2D",
        "sampler3D", "samplerCube", "sampler2DArray", "samplerCubeArray", "isampler2D", "usampler2D",
        "image1D", "image2D", "image3D", "imageCube", "atomic_uint"
])

const glslLiteralSet = new Set(["true", "false"])

const glslBuiltinSet = new Set([
        "abs", "acos", "all", "any", "asin", "atan", "ceil", "clamp", "cos", "cross", "degrees", "determinant",
        "distance", "dot", "dFdx", "dFdy", "equal", "exp", "exp2", "faceforward", "floor", "fract", "fwidth",
        "greaterThan", "greaterThanEqual", "inversesqrt", "length", "lessThan", "lessThanEqual", "log", "log2",
        "max", "min", "mix", "mod", "normalize", "not", "notEqual", "pow", "radians", "reflect", "refract",
        "round", "sign", "sin", "smoothstep", "sqrt", "step", "tan", "texture", "textureGrad", "textureLod",
        "transpose", "gl_FragCoord", "gl_FragDepth", "gl_GlobalInvocationID", "gl_InstanceIndex",
        "gl_LocalInvocationID", "gl_Position", "gl_VertexIndex"
])

const highlightGlslCode = (source) => {
        let out = ""
        let i = 0
        let lineStart = true
        const text = String(source || "")

        const push = (className, value) => {
                out += `<span class="${className}">${escapeCodeHtml(value)}</span>`
        }

        while (i < text.length) {
                const ch = text[i]
                const next = text[i + 1]

                if (lineStart && ch === "#") {
                        const end = text.indexOf("\n", i)
                        const stop = end === -1 ? text.length : end
                        push("hljs-meta", text.slice(i, stop))
                        i = stop
                        lineStart = false
                        continue
                }

                if (ch === "/" && next === "/") {
                        const end = text.indexOf("\n", i)
                        const stop = end === -1 ? text.length : end
                        push("hljs-comment", text.slice(i, stop))
                        i = stop
                        lineStart = false
                        continue
                }

                if (ch === "/" && next === "*") {
                        const end = text.indexOf("*/", i + 2)
                        const stop = end === -1 ? text.length : end + 2
                        push("hljs-comment", text.slice(i, stop))
                        lineStart = text.slice(i, stop).endsWith("\n")
                        i = stop
                        continue
                }

                if (ch === "\"" || ch === "'") {
                        const quote = ch
                        let j = i + 1
                        while (j < text.length) {
                                if (text[j] === "\\" && j + 1 < text.length) {
                                        j += 2
                                        continue
                                }
                                if (text[j] === quote) {
                                        j++
                                        break
                                }
                                j++
                        }
                        push("hljs-string", text.slice(i, j))
                        lineStart = false
                        i = j
                        continue
                }

                const number = text.slice(i).match(/^(?:0x[0-9a-fA-F]+|(?:\d+\.\d*|\.\d+|\d+)(?:[eE][+-]?\d+)?)[uUlLfF]?/)
                if (number) {
                        push("hljs-number", number[0])
                        i += number[0].length
                        lineStart = false
                        continue
                }

                const ident = text.slice(i).match(/^[A-Za-z_][A-Za-z0-9_]*/)
                if (ident) {
                        const word = ident[0]
                        if (glslKeywordSet.has(word)) push("hljs-keyword", word)
                        else if (glslTypeSet.has(word)) push("hljs-type", word)
                        else if (glslLiteralSet.has(word)) push("hljs-literal", word)
                        else if (glslBuiltinSet.has(word)) push("hljs-built_in", word)
                        else out += escapeCodeHtml(word)
                        i += word.length
                        lineStart = false
                        continue
                }

                out += escapeCodeHtml(ch)
                lineStart = ch === "\n" || (lineStart && /\s/.test(ch))
                i++
        }

        return out
}

const highlightNotesMarkdown = (root) => {
        root.querySelectorAll("pre code").forEach((block) => {
                const rawLanguage = block.className.match(/(?:^|\s)language-([A-Za-z0-9_+-]+)/)?.[1] || block.closest(".ai-chat-code-block")?.dataset?.lang || ""
                const language = normalizeCodeFenceLanguage(rawLanguage)
                block.className = language ? `language-${language}` : ""
                if (language === "glsl") {
                        block.innerHTML = highlightGlslCode(block.textContent || "")
                        block.classList.add("hljs", "language-glsl")
                        return
                }
                if (!window.hljs || typeof window.hljs.highlightElement !== "function") return
                try {
                        if (language && window.hljs.getLanguage && !window.hljs.getLanguage(language)) {
                                block.className = "language-plaintext"
                        }
                        window.hljs.highlightElement(block)
                } catch (_) {
                }
        })
}


/** The shared reader for box notes, Markdown proxies and the document guide panel. */
export function renderFieldMarkdown(root, markdown, options = {}) {
    root.innerHTML = aiChatMarkdownToHtml(markdown);
    sanitizeNotesMarkdown(root);
    highlightNotesMarkdown(root);
    const used = new Map();
    const headings = [...root.querySelectorAll('h1,h2,h3,h4,h5,h6')].map(node => {
        const base = headingSlug(node.textContent), count = used.get(base) || 0;
        used.set(base, count + 1);
        const id = count ? `${base}-${count}` : base;
        // Scoped data anchors avoid duplicate DOM ids when several readers show the same file.
        node.dataset.guideHeading = id;
        return {id, title: node.textContent, level: Number(node.tagName.slice(1)), node};
    });
    root.querySelectorAll('a[href]').forEach(anchor => {
        const link = classifyGuideLink(anchor.getAttribute('href'), options.path);
        if (link.kind === 'external') return;
        if (link.kind === 'invalid') { anchor.removeAttribute('href'); return; }
        anchor.removeAttribute('target');
        // Page-level Field links work in every Markdown surface, including readers without callbacks.
        if (anchor.getAttribute('href').startsWith('field:')) return;
        anchor.addEventListener('click', e => {
            e.preventDefault(); e.stopPropagation();
            if (options.onLink) options.onLink(link);
            else if (link.kind === 'box') options.onBox?.(link);
            else if (link.kind === 'guide') {
                if (anchor.getAttribute('href').startsWith('#')) headings.find(h => h.id === link.hash)?.node.scrollIntoView({block: 'start'});
                else options.onGuide?.(link);
            }
        });
    });
    if (options.documentKey && options.path) root.querySelectorAll('img[src]').forEach(img => {
        const src = img.getAttribute('src');
        if (/^(https?:|data:)/i.test(src)) return;
        const link = classifyGuideLink(src, options.path);
        if (link.kind === 'asset') img.src = guideAssetUrl(options.documentKey, link.path);
        else img.remove();
    });
    root.querySelectorAll('.ai-chat-copy').forEach(button => {
        button.textContent = 'Copy';
        button.addEventListener('click', async e => {
            e.preventDefault(); e.stopPropagation();
            try {
                await navigator.clipboard.writeText(button.closest('.ai-chat-code-block').querySelector('code').textContent);
                button.textContent = 'Copied';
                setTimeout(() => button.textContent = 'Copy', 1200);
            } catch (_) { button.textContent = 'Copy unavailable'; }
        });
    });
    return headings;
}
