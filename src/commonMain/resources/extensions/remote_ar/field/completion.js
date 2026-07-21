(function (global) {
    "use strict";

    const KEYWORDS = [
        "async", "await", "break", "case", "catch", "class", "const", "continue", "debugger",
        "default", "delete", "do", "else", "export", "extends", "false", "finally", "for", "from",
        "function", "get", "if", "import", "in", "instanceof", "let", "new", "null", "of", "return",
        "set", "static", "super", "switch", "this", "throw", "true", "try", "typeof", "undefined",
        "var", "void", "while", "with", "yield"
    ];

    function propertyNames(value) {
        const names = new Set();
        let cursor = value;
        let depth = 0;
        while (cursor != null && depth < 8) {
            try {
                Object.getOwnPropertyNames(cursor).forEach((name) => names.add(name));
                cursor = Object.getPrototypeOf(cursor);
            } catch (_) {
                break;
            }
            depth += 1;
        }
        return Array.from(names);
    }

    function valueAt(root, key) {
        try {
            return root == null ? undefined : root[key];
        } catch (_) {
            return undefined;
        }
    }

    function rootValue(name) {
        const sandbox = global.__sandbox__;
        let value = valueAt(sandbox, name);
        if (typeof value !== "undefined") return value;
        value = valueAt(global.__superglobal, name);
        if (typeof value !== "undefined") return value;
        return valueAt(global, name);
    }

    function resolvePath(path) {
        if (!/^[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*|\[(?:\d+|"[^"]*"|'[^']*')\])*$/.test(path)) {
            return undefined;
        }
        const tokens = [];
        path.replace(/^[A-Za-z_$][\w$]*|\.([A-Za-z_$][\w$]*)|\[(\d+)\]|\["([^"]*)"\]|\['([^']*)'\]/g,
            (match, dotted, numeric, doubleQuoted, singleQuoted) => {
                tokens.push(dotted || numeric || doubleQuoted || singleQuoted || match);
                return match;
            });
        let value = rootValue(tokens.shift());
        for (const token of tokens) value = valueAt(value, token);
        return value;
    }

    function describe(value) {
        if (value == null) return String(value);
        if (typeof value === "function") {
            const source = Function.prototype.toString.call(value);
            const firstLine = source.split("\n", 1)[0].replace(/\s*\{\s*$/, "");
            return value.__doc__ || firstLine.substring(0, 240);
        }
        if (value && typeof value.__doc__ === "string") return value.__doc__;
        if (value && value.constructor && value.constructor.name) return value.constructor.name;
        return typeof value;
    }

    function completionFor(name, prefix, owner) {
        const value = valueAt(owner, name);
        return {
            title: name,
            add: name.substring(prefix.length),
            from: describe(value),
            doc: 0,
            extraDoc: value && value.__doc__ ? String(value.__doc__) : ""
        };
    }

    function globalCompletions(prefix) {
        const owners = [global.__sandbox__, global.__superglobal, global];
        const names = new Set(KEYWORDS);
        owners.forEach((owner) => propertyNames(owner).forEach((name) => names.add(name)));
        return Array.from(names)
            .filter((name) => name.startsWith(prefix))
            .sort()
            .map((name) => {
                const owner = owners.find((candidate) => typeof valueAt(candidate, name) !== "undefined");
                return completionFor(name, prefix, owner || {});
            });
    }

    function completeMe(at, filetext) {
        const before = String(filetext || "").slice(0, Math.max(0, at));
        const member = before.match(/([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*|\[(?:\d+|"[^"]*"|'[^']*')\])*)\.([A-Za-z_$][\w$]*)?$/);
        if (member) {
            const owner = resolvePath(member[1]);
            const prefix = member[2] || "";
            if (owner == null) return [];
            return propertyNames(owner)
                .filter((name) => name.startsWith(prefix))
                .sort()
                .map((name) => completionFor(name, prefix, owner));
        }
        const identifier = before.match(/([A-Za-z_$][\w$]*)$/);
        return globalCompletions(identifier ? identifier[1] : "");
    }

    global.completeMe = completeMe;
    global.FieldRemoteCompletion = {completeMe, propertyNames, resolvePath};
})(window);
