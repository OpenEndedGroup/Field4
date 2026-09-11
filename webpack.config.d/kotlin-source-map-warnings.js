// Kotlin/JS libraries publish useful maps whose source lists include build-machine-only `.kt`
// paths without embedded source text. source-map-loader can still consume the mappings, but emits
// one ENOENT warning per unavailable Kotlin source. Webpack's Kotlin logging plugin hides their
// text yet leaves misleading "[42 warnings]" module counts. Remove only that understood warning
// shape from the compilation; malformed maps and every other webpack warning remain visible.
class IgnoreUnavailableKotlinSourcePlugin {
    apply(compiler) {
        const isUnavailableKotlinSource = warning => {
            const message = String(warning && warning.message || warning);
            return (
                message.includes("Failed to parse source map from") &&
                message.includes("ENOENT") &&
                message.includes(".kt'")
            );
        };
        compiler.hooks.compilation.tap("IgnoreUnavailableKotlinSourcePlugin", compilation => {
            // Filtering only compilation.warnings is too late for webpack's per-module warning
            // counts. Intercept the loader warning before NormalModule records it.
            compiler.webpack.NormalModule.getCompilationHooks(compilation).beforeLoaders.tap(
                "IgnoreUnavailableKotlinSourcePlugin",
                (loaders, module) => {
                    const isSourceMapLoader = loaders.some(loader =>
                        String(loader.loader || loader.path).includes("source-map-loader")
                    );
                    if (!isSourceMapLoader) return;
                    const addWarning = module.addWarning.bind(module);
                    module.addWarning = warning => {
                        if (!isUnavailableKotlinSource(warning)) addWarning(warning);
                    };
                }
            );
            compilation.hooks.processWarnings.tap(
                "IgnoreUnavailableKotlinSourcePlugin",
                warnings => warnings.filter(warning => !isUnavailableKotlinSource(warning))
            );
        });
    }
}

config.plugins.push(new IgnoreUnavailableKotlinSourcePlugin());
