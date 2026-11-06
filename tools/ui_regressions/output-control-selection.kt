var outputSelectionValue = 0.25
controlGroup("controls") {
    slider("gain", min = 0.0, max = 1.0, initialValue = outputSelectionValue) {
        outputSelectionValue = it
        display("value", "<span id='output-selection-value'>$it</span>")
    }
}
display("value", "<span id='output-selection-value'>$outputSelectionValue</span>")
display("text", "<p id='output-selection-text'>Reading an output should leave the editor alone.</p>")
displayOpen("controls")
displayOpen("value")
displayOpen("text")
