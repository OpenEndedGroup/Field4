var navigationMist = 0.25
controlGroup("Far controls") {
    slider("Mist density", min = 0.0, max = 1.0, initialValue = navigationMist) {
        navigationMist = it
        display("callback", "<span id='navigation-mist-value'>$it</span>")
    }
    checkbox("Enable drifting", initialValue = true) { }
    stringInput("Weather name", initialValue = "Evening") { }
}
controlGroup("Other controls") {
    slider("Mist density", min = 0.0, max = 1.0, initialValue = 0.75) { }
}
display("callback", "<span id='navigation-mist-value'>$navigationMist</span>")
displayOpen("Far controls")
