import {
        clear,
        clearErrors,
        clearEditorOutputsForBox,
        clearEditorOutputsForBoxBeforeLaunch,
        removeNewestOutput,
        removeArea,
        toggleArea,
        addInlineOutput,
        addExecutionMarker,
        setStatusForExecutionMarker,
        clearAllDecorations,
        setOutputScrollTail,
        nudgeOutputScrollTail,
        getOutputScrollTail,
        setOutputMaxHeight,
        getOutputMaxHeight,
        chatKeyboardCallback,
        addPromptBox,
        addOutput
} from "./afield.mjs";

window.clear = clear;
window.clearErrors = clearErrors;
window.clearEditorOutputsForBox = clearEditorOutputsForBox;
window.clearEditorOutputsForBoxBeforeLaunch = clearEditorOutputsForBoxBeforeLaunch;
window.removeNewestOutput = removeNewestOutput;
window.removeArea = removeArea;
window.toggleArea = toggleArea;
window.addInlineOutput = addInlineOutput;
window.addExecutionMarker = addExecutionMarker;
window.setStatusForExecutionMarker = setStatusForExecutionMarker;
window.clearAllDecorations = clearAllDecorations;
window.setOutputScrollTail = setOutputScrollTail;
window.nudgeOutputScrollTail = nudgeOutputScrollTail;
window.getOutputScrollTail = getOutputScrollTail;
window.setOutputMaxHeight = setOutputMaxHeight;
window.getOutputMaxHeight = getOutputMaxHeight;
window.chatKeyboardCallback = chatKeyboardCallback;
window.addPromptBox = addPromptBox;
window.addOutput = addOutput;
