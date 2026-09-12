// Kept independent of the DOM so paths, fragments and stable box links have one interpretation.
export function headingSlug(text) {
    return String(text).toLowerCase().trim().replace(/[^\p{L}\p{N}\s_-]/gu, "").replace(/\s/g, "-") || "section";
}

export function classifyGuideLink(href, currentPath = "README.md") {
    const raw = String(href || "").trim();
    if (!raw) return { kind: "invalid" };
    try {
        if (raw.startsWith("field://guide/")) {
            const url = new URL(raw);
            return classifyGuideLink(url.pathname.slice(1) + url.hash, "README.md");
        }
        if (raw.startsWith("field://box/")) {
            const url = new URL(raw), uid = decodeURIComponent(raw.slice('field://box/'.length).split(/[?#]/, 1)[0]);
            const line = Number(url.searchParams.get("line"));
            return uid && !/[\x00-\x1f/\\]/.test(uid) ? { kind: "box", uid, property: url.searchParams.get("property") || "code",
                line: Number.isInteger(line) && line > 0 ? line : null } : { kind: "invalid" };
        }
        if (raw.startsWith("field://macro/")) {
            const url = new URL(raw), name = decodeURIComponent(raw.slice('field://macro/'.length).split(/[?#]/, 1)[0]);
            const scope = url.searchParams.get("scope") || "document";
            const project = url.searchParams.get("project") || "";
            if (!name.trim() || /[\x00-\x1f/\\]/.test(name) || name === "." || name === ".." ||
                !["document", "project", "workspace"].includes(scope) || (project && scope !== "project")) return {kind:"invalid"};
            return {kind:"macro", name, scope, project};
        }
        if (/^(https?:|mailto:)/i.test(raw)) return { kind: "external", href: raw };
        if (/^[a-z][a-z\d+.-]*:/i.test(raw) || raw.startsWith("/") || raw.includes("\\")) return { kind: "invalid" };
        const [location, hash = ""] = raw.split("#", 2);
        const path = location.split("?", 1)[0];
        const segments = path ? currentPath.split("/").slice(0, -1) : [];
        for (const segment of path.split("/")) {
            const part = decodeURIComponent(segment);
            if (!part || part === ".") continue;
            if (part.includes("/") || part.includes("\\") || part.includes("\0")) return { kind: "invalid" };
            if (part === "..") { if (!segments.length) return { kind: "invalid" }; segments.pop(); }
            else segments.push(part);
        }
        const resolved = path ? segments.join("/") : currentPath;
        return { kind: /\.(md|markdown)$/i.test(resolved) ? "guide" : "asset", path: resolved, hash: decodeURIComponent(hash) };
    } catch (_) { return { kind: "invalid" }; }
}

export function guideAssetUrl(documentKey, path) {
    return `/api/document-guide-asset?${new URLSearchParams({document: documentKey, path})}`;
}

export function boxGuideLink(uid, property = "code", line = null) {
    const params = new URLSearchParams({property});
    if (Number.isInteger(line) && line > 0) params.set("line", line);
    return `field://box/${encodeURIComponent(uid)}?${params}`;
}

export function macroGuideLink(name, scope = "document", project = "") {
    const params = new URLSearchParams({scope});
    if (scope === "project" && project) params.set("project", project);
    return `field://macro/${encodeURIComponent(name)}?${params}`;
}
