/**
 * Field Documentation Iframe API
 * 
 * This module provides a bidirectional API between Field and documentation pages loaded in the iframe.
 * Documentation pages can call these methods to control Field's behavior.
 */

// API for iframe contents to control Field
const FieldAPI = {
    /**
     * Run a command on the server
     * @param {string} command - The command to execute
     * @param {object} args - Arguments for the command
     */
    runCommand(command, args = {}) {
        window.parent.postMessage({
            type: 'field-command',
            command: command,
            args: args
        }, '*');
    },

    /**
     * Run an action by id (from the Field actions palette)
     * @param {string} actionId - The action id (e.g., 'new-box')
     * @param {object} args - Optional argument payload
     */
    runAction(actionId, args = {}) {
        window.parent.postMessage({
            type: 'field-run-action',
            actionId: actionId,
            args: args
        }, '*');
    },

    /**
     * Execute code in a box
     * @param {string} uid - Box UID (optional, uses selected if not provided)
     * @param {string} code - Code to execute (optional, uses box's code if not provided)
     */
    executeBox(uid = null, code = null) {
        window.parent.postMessage({
            type: 'field-execute-box',
            uid: uid,
            code: code
        }, '*');
    },

    /**
     * Begin execution of boxes (equivalent to Alt+click or launch)
     * @param {string|string[]} uids - Single UID or array of UIDs
     */
    beginBoxes(uids) {
        const uidArray = Array.isArray(uids) ? uids : [uids];
        window.parent.postMessage({
            type: 'field-begin-boxes',
            uids: uidArray
        }, '*');
    },

    /**
     * Stop execution of boxes
     * @param {string|string[]} uids - Single UID or array of UIDs
     */
    endBoxes(uids) {
        const uidArray = Array.isArray(uids) ? uids : [uids];
        window.parent.postMessage({
            type: 'field-end-boxes',
            uids: uidArray
        }, '*');
    },

    /**
     * Change box selection
     * @param {string|string[]} uids - Single UID or array of UIDs to select
     * @param {boolean} add - If true, adds to selection; if false, replaces selection
     */
    selectBoxes(uids, add = false) {
        const uidArray = Array.isArray(uids) ? uids : [uids];
        window.parent.postMessage({
            type: 'field-select-boxes',
            uids: uidArray,
            add: add
        }, '*');
    },

    /**
     * Switch to editing a specific property
     * @param {string} property - Property name (e.g., 'code', 'vertex', 'fragment')
     */
    switchToProperty(property) {
        window.parent.postMessage({
            type: 'field-switch-property',
            property: property
        }, '*');
    },

    /**
     * Switch to Monaco editor
     */
    switchToEditor() {
        window.parent.postMessage({
            type: 'field-switch-to-editor'
        }, '*');
    },

    /**
     * Create a new box
     * @param {object} options - Box creation options (name, frame, code, etc.)
     */
    createBox(options = {}) {
        // Prefer to route through the actions palette
        this.runAction('new-box', options);
    },

    /**
     * Get information about the current selection
     * @param {function} callback - Callback to receive selection info
     */
    getSelection(callback) {
        const messageId = 'selection-' + Date.now() + '-' + Math.random();
        
        const handler = (event) => {
            if (event.data.type === 'field-selection-response' && event.data.messageId === messageId) {
                window.removeEventListener('message', handler);
                callback(event.data.selection);
            }
        };
        
        window.addEventListener('message', handler);
        
        window.parent.postMessage({
            type: 'field-get-selection',
            messageId: messageId
        }, '*');
    },

    /**
     * Navigate to a specific box (scroll to and optionally select)
     * @param {string} uid - Box UID
     * @param {boolean} select - Whether to select the box
     */
    navigateToBox(uid, select = true) {
        window.parent.postMessage({
            type: 'field-navigate-to-box',
            uid: uid,
            select: select
        }, '*');
    }
};

// Make API available globally for iframe contents
if (window.parent !== window) {
    // We're in an iframe
    window.FieldAPI = FieldAPI;
    const forwardCommandPaletteShortcut = (event) => {
        const isCtrlOrCmdP = (event.ctrlKey || event.metaKey) && event.key && event.key.toLowerCase() === 'p';
        if (event.key === 'F1' || isCtrlOrCmdP) {
            event.preventDefault();
            event.stopPropagation();
            window.parent.postMessage({ type: 'field-open-command-palette' }, '*');
        }
    };

    window.addEventListener('keydown', forwardCommandPaletteShortcut, true);
    window.dispatchEvent(new Event('field-api-ready'));
    console.info('Field Documentation API initialized');
}

// Message handler on the parent (Field) side
if (typeof window.handleDocsIframeMessage === 'undefined') {
    const collectActions = () => {
        const result = [];
        const actionsObj = window.allActions || {};
        Object.keys(actionsObj).forEach(key => {
            const value = actionsObj[key];
            if (Array.isArray(value)) {
                result.push(...value);
            }
        });
        const last = window.lastActions;
        if (Array.isArray(last)) {
            result.push(...last);
        }
        return result;
    };

    const runActionById = (actionId, args) => {
        const all = collectActions().filter(Boolean);
        const action = all.find(a => a && a.id === actionId);
        if (action && typeof action.run === 'function') {
            action.run(args);
            return true;
        }
        return false;
    };

    window.handleDocsIframeMessage = function(event) {
        // Only process messages from our iframe
        const iframe = document.getElementById('docs-iframe');
        if (!iframe || event.source !== iframe.contentWindow) {
            return;
        }

        const { type, ...data } = event.data;

        try {
            switch (type) {
                case 'field-command':
                    // Execute a Field command
                    if (window.allActions && window.allActions[data.command]) {
                        window.allActions[data.command](data.args);
                    }
                    break;

                case 'field-run-action':
                    if (!runActionById(data.actionId, data.args)) {
                        console.warn('Action not found for id', data.actionId);
                    }
                    break;

                case 'field-execute-box':
                    // Execute code in a box
                    if (data.uid && data.code) {
                        window.launchAll(data.uid, data.code);
                    } else if (data.uid) {
                        // Get box and execute its code
                        const boxes = document.body.getElementsByClassName('box');
                        const box = Array.from(boxes).find(b => b.uid === data.uid);
                        if (box && box.code) {
                            window.launchAll(data.uid, box.code);
                        }
                    }
                    break;

                case 'field-begin-boxes':
                    // Launch boxes
                    data.uids.forEach(uid => {
                        const boxes = document.body.getElementsByClassName('box');
                        const box = Array.from(boxes).find(b => b.uid === uid);
                        if (box && box.code) {
                            window.launchAll(uid, box.code);
                        }
                    });
                    break;

                case 'field-end-boxes':
                    // Stop boxes
                    data.uids.forEach(uid => {
                        // Use the stopBox function if available
                        if (typeof window.stopBox === 'function') {
                            window.stopBox(uid);
                        }
                    });
                    break;

                case 'field-select-boxes':
                    // Change selection
                    if (!data.add) {
                        // Clear current selection
                        const currentlySelected = Array.from(window.selected || []);
                        currentlySelected.forEach(box => {
                            if (window.deselectBox) window.deselectBox(box);
                        });
                    }
                    
                    // Select requested boxes
                    data.uids.forEach(uid => {
                        const boxes = document.body.getElementsByClassName('box');
                        const box = Array.from(boxes).find(b => b.uid === uid);
                        if (box && window.selectBox) {
                            window.selectBox(box);
                        }
                    });
                    break;

                case 'field-switch-property':
                    // Switch to a different property
                    window.selectedProperty = data.property;
                    if (window.selectionContextChanged) {
                        window.selectionContextChanged();
                    }
                    break;

                case 'field-switch-to-editor':
                    // Switch back to Monaco editor
                    if (window.exitDocsIframe) {
                        window.exitDocsIframe();
                    } else if (window.switchToMonaco) {
                        window.switchToMonaco();
                    }
                    break;

                case 'field-open-command-palette':
                    if (typeof window.openQuickCommandPalette === 'function') {
                        window.openQuickCommandPalette();
                    } else if (typeof globalPickMenu === 'function') {
                        globalPickMenu();
                    }
                    break;

                case 'field-create-box':
                    if (!runActionById('new-box', data.options)) {
                        if (typeof window.newBox === 'function') {
                            window.newBox();
                        } else {
                            console.warn('createBox API called but no new-box action or newBox() found. Options requested:', data.options);
                        }
                    }
                    break;

                case 'field-get-selection':
                    // Return current selection info
                    const boxesSource = Array.isArray(window.selectedList) ? window.selectedList : Array.from(window.selected || []);
                    const selection = {
                        boxes: boxesSource.map(box => ({
                            uid: box.uid,
                            name: box.name,
                            code: box.code,
                            frame: {
                                x: box.data_x,
                                y: box.data_y,
                                w: box.data_w,
                                h: box.data_h
                            }
                        })),
                        property: window.selectedProperty
                    };
                    
                    const iframeElement = document.getElementById('docs-iframe');
                    if (iframeElement && iframeElement.contentWindow) {
                        iframeElement.contentWindow.postMessage({
                            type: 'field-selection-response',
                            messageId: data.messageId,
                            selection: selection
                        }, '*');
                    }
                    break;

                case 'field-navigate-to-box':
                    // Navigate to a box
                    const boxes = document.body.getElementsByClassName('box');
                    const targetBox = Array.from(boxes).find(b => b.uid === data.uid);
                    if (targetBox) {
                        // Scroll to box
                        targetBox.scrollIntoView({ behavior: 'smooth', block: 'center' });
                        
                        // Select if requested
                        if (data.select && window.selectBox) {
                            // Clear current selection
                            const currentlySelected = Array.from(window.selected || []);
                            currentlySelected.forEach(box => {
                                if (window.deselectBox) window.deselectBox(box);
                            });
                            window.selectBox(targetBox);
                        }
                    }
                    break;
            }
        } catch (error) {
            console.error('Error handling docs iframe message:', error);
        }
    };

    // Register the message handler
    window.addEventListener('message', window.handleDocsIframeMessage);
}
