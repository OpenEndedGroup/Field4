// Build once and commit the result; Field loads it only for a Mermaid fence and works offline.
import { build } from "esbuild";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
await build({
    entryPoints: [join(here, "entry.js")],
    outfile: join(here, "../../src/jsMain/resources/mermaid/field-mermaid.js"),
    bundle: true,
    format: "esm",
    minify: true,
    target: "es2020",
    // Monaco installs a global AMD `define`. Some Mermaid dependencies are UMD and otherwise
    // try to register with Monaco instead of using the CommonJS wrapper esbuild bundled.
    define: { define: "undefined" },
    legalComments: "linked",
    logLevel: "info",
});
