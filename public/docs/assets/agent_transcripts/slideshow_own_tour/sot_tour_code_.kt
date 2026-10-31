val show = slideShow {
    step("Data") {
        markdownNote("## `numbers` makes the data\n[[next]]")
        selectBoxByName("numbers")
    }
    step("The work") {
        markdownNote("## Line 3 squares each number\n[[back]] [[next]]")
        selectBoxByName("squares", line = 3, highlight = 3)
    }
    step("Done") {
        markdownNote("## Run `squares` to see the result\n[[home:Start again]] [[finish]]")
    }
}

runBlocking { show.start() }
