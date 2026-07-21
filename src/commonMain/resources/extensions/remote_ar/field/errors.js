(function (global) {
    "use strict";

    const STACK_LOCATION = /(?:\(|\s|^)((?:box_|field-box:|https?:|file:)[^\s()]*?):(\d+):(\d+)(?:\)|\s|$)/g;

    function messageFor(error) {
        if (error == null) return "Unknown remote JavaScript error";
        if (typeof error === "string") return error;
        return error.message || String(error);
    }

    function locationsFor(error) {
        const stack = error && error.stack ? String(error.stack) : "";
        const locations = [];
        let match;
        STACK_LOCATION.lastIndex = 0;
        while ((match = STACK_LOCATION.exec(stack)) !== null) {
            locations.push({
                filename: match[1],
                line: Number(match[2]),
                column: Number(match[3])
            });
        }
        return locations;
    }

    function describe(error, options) {
        options = options || {};
        const locations = locationsFor(error);
        const wanted = options.codeName;
        const location = locations.find((entry) => wanted && entry.filename.indexOf(wanted) !== -1)
            || locations.find((entry) => entry.filename.indexOf("box_") !== -1 || entry.filename.indexOf("field-box:") !== -1)
            || locations[0];
        const fallbackLine = Number(error && (error.lineNumber || error.line)) || Number(options.fallbackLine) || -1;
        const wrapperLines = Number(options.wrapperLines) || 0;
        const rawLine = location ? location.line : fallbackLine;
        return {
            message: messageFor(error),
            filename: location ? location.filename : (wanted || "unknown"),
            line: rawLine > 0 ? Math.max(1, rawLine - wrapperLines) : -1,
            column: location ? location.column : (Number(error && error.columnNumber) || -1),
            name: error && error.name ? error.name : "Error",
            stack: error && error.stack ? String(error.stack) : ""
        };
    }

    function send(error, returnTo, options) {
        const detail = describe(error, options);
        if (global._field && typeof global._field.send === "function" && returnTo) {
            global._field.send(returnTo, Object.assign({kind: "ERROR"}, detail));
        }
        return detail;
    }

    global.FieldRemoteErrors = {describe, locationsFor, messageFor, send};
})(window);
