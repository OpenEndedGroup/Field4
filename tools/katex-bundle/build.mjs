// Build once and commit the result; Field loads it only when an AI4 chat shows math, and works offline.
import { build } from "esbuild";
import { copyFileSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const out = join(here, "../../src/jsMain/resources/katex");
const dist = join(here, "node_modules/katex/dist");
rmSync(out, { recursive: true, force: true });
mkdirSync(join(out, "fonts"), { recursive: true });

await build({
    entryPoints: [join(here, "entry.js")],
    outfile: join(out, "field-katex.js"),
    bundle: true,
    format: "esm",
    minify: true,
    target: "es2020",
    legalComments: "linked",
    logLevel: "info",
});

// Every browser Field runs in reads woff2, so the woff and ttf fallbacks are dropped from the
// stylesheet and never copied.
const css = readFileSync(join(dist, "katex.min.css"), "utf8")
    .replace(/,url\(fonts\/[^)]+\.(woff|ttf)\) format\("(woff|truetype)"\)/g, "");
if (/\.(woff|ttf)\)/.test(css)) throw new Error("KaTeX stylesheet still references woff/ttf fonts");
writeFileSync(join(out, "katex.min.css"), css);
for (const font of readdirSync(join(dist, "fonts")).filter(name => name.endsWith(".woff2"))) {
    copyFileSync(join(dist, "fonts", font), join(out, "fonts", font));
}
copyFileSync(join(here, "node_modules/katex/LICENSE"), join(out, "LICENSE.txt"));
