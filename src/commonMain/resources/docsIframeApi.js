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
        window.parent.postMessage({
            type: 'field-create-box',
            options: options
        }, '*');
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
    console.log('Field Documentation API initialized');
}

// Message handler on the parent (Field) side
if (typeof window.handleDocsIframeMessage === 'undefined') {
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
                    if (window.switchToMonaco) {
                        window.switchToMonaco();
                    }
                    break;

                case 'field-create-box':
                    // Create a new box - not yet implemented server-side
                    const workaround = typeof window.newBox === 'function' 
                        ? 'Use window.newBox() as a workaround.' 
                        : 'No workaround available - newBox() function not found.';
                    console.warn(
                        'createBox API called but server-side implementation pending. ' +
                        workaround + ' Options requested: ' + 
                        JSON.stringify(data.options)
                    );
                    // Future: Send message to server to create box
                    // if (window.send) {
                    //     window.send(Message("create-box").payload(data.options));
                    // }
                    break;

                case 'field-get-selection':
                    // Return current selection info
                    const selection = {
                        boxes: Array.from(window.selected || []).map(box => ({
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
