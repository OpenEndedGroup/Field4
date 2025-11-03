let quickInputCommand = null
let globalIS = null

const initQuickPick = (is) => {
        globalIS = is.IQuickInputService

        window.addEventListener("keydown", (e) => {
                if (document.activeElement == document.body) {
                        console.log(e.key)
                        if (e.key == "?")
                                showCheatsheet()

                        let k = e.key
                        let found = lastActions.find(x => {
                                console.log(" testing ", x)
                                let a = x.canvasKeybinding && x.canvasKeybinding(e)
                                console.log(" got ", a)
                                return a
                        })
                        if (found) {
                                console.log(" running ")
                                found.run()
                        }
                }
        })
}

let allActions = {
        base: []
}

let lastActions = []

const updateActions = () => {
        let all = []
        Object.keys(allActions).forEach(key => {
                all.push(...allActions[key])
        })
        setActions(all)
}

const setActions = (actions) => {

        let add = []
        let remove = []
        for (let a of actions) {
                let found = lastActions.find(x => x.id === a.id)
                if (found) {

                } else {
                        add.push(a)
                }
        }

        for (let a of lastActions) {
                let found = actions.find(x => x.id === a.id)
                if (found) {

                } else {
                        remove.push(a)
                }
        }

        for (let a of remove) {
                if (a.disposer) {
                        a.disposer.dispose()
                }
        }

        if (window.globalEditor) for (let a of add) {
                a.disposer = globalEditor.addAction(a)
        }

        console.log(" all actions are ", actions, " last actions are ", lastActions, "")
        lastActions = actions
}

const globalPickMenu_custom = (options) => {
        if (quickInputCommand === null) quickInputCommand = globalEditor.addCommand(0, (accessor, func) => {
                let quickInputService = accessor.get(globalIS)
                func(quickInputService)
        })

        globalEditor.trigger("", quickInputCommand, (quickInput) => {
                quickInput.pick(options).then((selected) => {
                        console.log("selection has happened, ", selected)
                        selected.run()
                })
        })
}

const globalPickMenu = () => {
        if (quickInputCommand === null) quickInputCommand = globalEditor.addCommand(0, (accessor, func) => {
                let quickInputService = accessor.get(globalIS)
                func(quickInputService)
        })

        globalEditor.trigger("", quickInputCommand, (quickInput) => {
                quickInput.pick(lastActions).then((selected) => {
                        console.log("selection has happened, ", selected)
                        selected.run()
                })
        })
}

