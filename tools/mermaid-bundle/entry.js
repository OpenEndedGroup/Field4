import mermaid from "mermaid";

mermaid.initialize({
    startOnLoad: false,
    securityLevel: "strict",
    suppressErrorRendering: true,
    maxTextSize: 20000,
    maxEdges: 300,
    htmlLabels: false,
    fontFamily: "Iosevka, monospace",
    theme: "base",
    themeVariables: {
        darkMode: true,
        background: "#080a0c",
        primaryColor: "#11161a",
        primaryTextColor: "#e4e8eb",
        primaryBorderColor: "#a4aeb6",
        secondaryColor: "#141a1e",
        tertiaryColor: "#0d1216",
        lineColor: "#a4aeb6",
        textColor: "#e4e8eb",
    },
});

// Mermaid uses a shared rendering container, so queue diagrams from both AI4 chat views.
let queue = Promise.resolve();

export function renderMermaid(source, id) {
    if (source.length > 20000) return Promise.reject(new Error("diagram exceeds 20,000 characters"));
    const result = queue.then(async () => {
        await mermaid.parse(source);
        const { svg } = await mermaid.render(id, source);
        const node = document.createElement("div");
        node.innerHTML = svg;
        if (!node.querySelector("svg")) throw new Error("renderer produced no SVG");
        return node;
    });
    queue = result.then(() => {}, () => {});
    return result;
}
