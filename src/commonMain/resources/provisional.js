const boxMap = new Map()
const changeListeners = new Set()
let entryCount = 0
let overlayRefreshQueued = false
let overlayObserverInstalled = false

function ensureBox(uid) {
    let map = boxMap.get(uid)
    if (!map) {
        map = new Map()
        boxMap.set(uid, map)
    }
    return map
}

function removeBoxIfEmpty(uid, map) {
    if (map.size === 0) {
        boxMap.delete(uid)
    }
}

function setProvisional(uid, property, contents) {
    const map = ensureBox(uid)
    if (!map.has(property)) entryCount++
    map.set(property, contents)
    notifyChange()
}

function getProvisional(uid, property) {
    const map = boxMap.get(uid)
    return map ? map.get(property) : undefined
}

function hasProvisional(uid, property) {
    const map = boxMap.get(uid)
    return map ? map.has(property) : false
}

function removeProvisional(uid, property) {
    const map = boxMap.get(uid)
    if (!map) return false
    const removed = map.delete(property)
    if (removed) entryCount--
    removeBoxIfEmpty(uid, map)
    if (removed) notifyChange()
    return removed
}

function clearProvisional() {
    boxMap.clear()
    entryCount = 0
    notifyChange()
}

function hasProvisionalBox(uid) {
    const map = boxMap.get(uid)
    return !!(map && map.size)
}

function listBoxes() {
    return Array.from(boxMap.keys())
}

function listProperties(uid) {
    const map = boxMap.get(uid)
    if (!map) return []
    return Array.from(map.entries()).map(([property, contents]) => ({ property, contents }))
}

function listPropertyNames(uid) {
    const map = boxMap.get(uid)
    if (!map) return []
    return Array.from(map.keys())
}

function listEntries() {
    const entries = []
    boxMap.forEach((map, uid) => {
        map.forEach((contents, property) => {
            entries.push({ uid, property, contents })
        })
    })
    return entries
}

function size() {
    return entryCount
}

function onChange(fn) {
    if (typeof fn === "function") {
        changeListeners.add(fn)
    }
    return () => changeListeners.delete(fn)
}

function notifyChange() {
    scheduleOverlayRefresh()
    changeListeners.forEach((fn) => {
        try {
            fn()
        } catch (_) {}
    })
    if (typeof window !== "undefined" && window.dispatchEvent) {
        window.dispatchEvent(new CustomEvent("provisional-change"))
    }
}

function ensureOverlayObserver() {
    if (overlayObserverInstalled) return
    if (typeof MutationObserver === "undefined" || typeof document === "undefined") return
    overlayObserverInstalled = true
    const observer = new MutationObserver((mutations) => {
        for (const mutation of mutations) {
            for (const node of mutation.addedNodes) {
                if (node.nodeType !== 1) continue
                if (node.classList?.contains("box") || node.querySelector?.(".box")) {
                    scheduleOverlayRefresh()
                    return
                }
            }
        }
    })
    observer.observe(document.body, { childList: true, subtree: true })
}

function scheduleOverlayRefresh() {
    if (typeof document === "undefined") return
    ensureOverlayObserver()
    if (overlayRefreshQueued) return
    overlayRefreshQueued = true
    const run = () => {
        overlayRefreshQueued = false
        refreshOverlay()
    }
    if (typeof requestAnimationFrame === "function") {
        requestAnimationFrame(run)
    } else {
        setTimeout(run, 0)
    }
}

function refreshOverlay() {
    if (typeof document === "undefined") return
    const boxes = document.querySelectorAll(".box")
    boxes.forEach((box) => {
        const uid = box.uid || box.getAttribute("data-uid")
        if (!uid) return
        const props = listPropertyNames(uid)
        const hasAny = props.length > 0
        if (hasAny) {
            box.classList.add("provisional")
            let badge = box.querySelector(".provisional-badge")
            if (!badge) {
                badge = document.createElement("div")
                badge.className = "provisional-badge"
                box.appendChild(badge)
            }
            const shown = props.slice(0, 3)
            const tail = props.length > 3 ? ` +${props.length - 3}` : ""
            badge.textContent = `${shown.join(", ")}${tail}`
            badge.title = props.join(", ")
        } else {
            box.classList.remove("provisional")
            const badge = box.querySelector(".provisional-badge")
            if (badge) badge.remove()
        }
    })
}

export const Provisional = {
    set: setProvisional,
    get: getProvisional,
    has: hasProvisional,
    remove: removeProvisional,
    clear: clearProvisional,
    hasBox: hasProvisionalBox,
    listBoxes,
    listProperties,
    listPropertyNames,
    listEntries,
    size,
    onChange
}

if (typeof globalThis !== "undefined") {
    globalThis.Provisional = Provisional
}

export default Provisional
