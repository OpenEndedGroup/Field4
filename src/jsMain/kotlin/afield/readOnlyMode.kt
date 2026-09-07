package afield

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/**
 * Read-only sessions, as the user experiences them.
 *
 * Field can open a document read-only — `./field.sh --read-only`, `AFIELD_READ_ONLY=1`,
 * `-Dafield.readOnly=true`, or the agent backdoor's `open --read-only`. Until 2026-09-07 the client
 * was never told: `git grep -c 'readOnly' -- 'src/jsMain/**/*.kt'` matched nothing across all 21
 * files in the source set, and `SessionInformation.readOnly`'s own KDoc said a read-only session
 * "looks normal to the client".
 *
 * That is worse in Field than it would be in most editors, because **there is no explicit save**.
 * The only write path is the autosave loop, so a user gets no dialog, no dirty marker and no
 * failed-save error. They work for an hour, close the window, and everything is gone with nothing
 * having said a word.
 *
 * ### Three surfaces, deliberately, because a badge alone is too weak
 *
 * 1. **A banner**, from the moment the session attaches, in plain words: nothing will be saved. It
 *    is `pointer-events: none` so it can never eat a click meant for the canvas.
 * 2. **A live count of what is being thrown away.** The banner turns from a statement into a
 *    running total — "12 changes will be discarded" — because a number the user watches grow is
 *    much harder to keep ignoring than a label that never changes.
 * 3. **A confirmation at the moment the work would actually be lost.** `beforeunload` is the only
 *    hook that fires before a close or a reload, and the browser turns it into a native "leave
 *    site?" dialog. Registered only once at least one change has been made, because a page that
 *    always argues about being closed trains the user to click through the dialog — which would
 *    make it useless on the one occasion it matters.
 *
 * A one-shot `notify` on the first edit as well, so the news arrives at the moment it becomes
 * relevant rather than only at the end.
 *
 * ### On counting edits
 *
 * [MUTATING_MESSAGES] is the *unambiguous* set of outgoing messages that change a document, not an
 * exhaustive one. That direction is chosen on purpose: a name missing from the list means the
 * counter under-reports and the `beforeunload` dialog might not arm, whereas guessing the other way
 * — treating everything except a denylist as an edit — would arm it after a pan or a zoom and make
 * the dialog noise. The banner is always visible regardless, so the floor is never "no warning at
 * all". You cannot do real work in Field without typing (`set-string`) or moving something
 * (`set-rect`/`set-rects`), so the common cases are covered.
 */
object ReadOnlyMode {

    private const val BANNER_ID = "afield-read-only-banner"
    private const val STYLE_ID = "afield-read-only-style"

    /**
     * Outgoing message names that mean the user changed the document. See the class KDoc for why
     * this is a curated allowlist rather than a denylist.
     */
    private val MUTATING_MESSAGES = setOf(
        "set-string",
        "set-rect",
        "set-rects",
        "set-boolean",
        "set-null",
        "set-animated-rect",
        "paste-boxes",
        "insert-template",
        "import-metadata-edn",
        "import-metadata-edn-nested",
        "import-ipynb",
        "create-file-proxy-box",
        "set-visible-layers",
        "set-active-layer",
        "macro-transcript-command"
    )

    /** Nothing this session does will be kept. Cleared if a reconnect lands on a writable session. */
    var readOnly: Boolean = false
        private set

    private var reason: String = ""
    private var discardedChanges: Int = 0
    private var unloadGuardInstalled = false
    private var toldTheUser = false

    /**
     * Register the handler for the server's per-attach status message.
     *
     * Called from `main` alongside the other installers. The message arrives once per attach, and
     * again on every reconnect, which is what lets [apply] clear the state as well as set it.
     */
    fun install() {
        handlers.put("set-session-write-status") { message, _ ->
            val status = Json.decodeFromJsonElement<SessionWriteStatus>(message.payload!!)
            apply(status)
            Handled.yes
        }
    }

    private fun apply(status: SessionWriteStatus) {
        val changed = status.readOnly != readOnly
        readOnly = status.readOnly
        reason = status.reason.ifBlank {
            "This document is open read-only. Nothing you change here will be saved."
        }
        // Mirrors `window.currentDocumentScratch`, which is how the client already publishes
        // `ephemeral`. Two jobs: it is the hook a headless check or a Playwright assertion can read,
        // and it lets non-Kotlin client code (the Electron shell, the docs capture harness) see the
        // state without a message of its own.
        window.asDynamic().currentDocumentReadOnly = readOnly
        if (changed && !readOnly) {
            discardedChanges = 0
            toldTheUser = false
        }
        render()
        if (readOnly) {
            console.info("[read-only] $reason")
        }
    }

    /**
     * Count an outgoing message, and arm the close confirmation the first time it is an edit.
     *
     * Called from `_send`, the single choke point for everything the client sends.
     */
    fun noteOutgoing(message: Message) {
        if (!readOnly || message.name !in MUTATING_MESSAGES) return
        if (isEditorViewState(message)) return
        discardedChanges++
        if (!toldTheUser) {
            toldTheUser = true
            // `notify` is the client's existing transient-message channel; "error" is the loudest
            // category it has, and losing an hour of work earns it.
            runCatching { notify("Read-only — this change will NOT be saved", "error") }
        }
        installUnloadGuard()
        render()
    }

    /**
     * True for a `set-string` that is only carrying Monaco's cursor/scroll position.
     *
     * These travel as ordinary `set-string`s but are **not** part of the document — see
     * `Box.ioNeverPersistedProperties` and `dev_docs/document_write_back.md`. Counting them
     * inflated the number badly: measured against a live read-only session on 2026-09-07, three
     * keystrokes in one box reported **10 changes** before this filter and **3** after it. A
     * number the user is meant to trust cannot be four times the truth, and cursor movement alone
     * would otherwise arm the close dialog in a session where nothing was actually edited.
     */
    private fun isEditorViewState(message: Message): Boolean {
        if (message.name != "set-string") return false
        val property = runCatching {
            message.payload?.jsonObject?.get("property")?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: return false
        return property.endsWith("_editorState")
    }

    /**
     * Ask the browser to confirm a close or reload while there is unsaved work.
     *
     * The wording is not ours to choose: browsers replaced custom `beforeunload` text with a generic
     * "Leave site?" years ago. What we control is *whether* the dialog appears, so the banner and
     * the notification carry the actual explanation and this is the last-moment stop.
     */
    private fun installUnloadGuard() {
        if (unloadGuardInstalled) return
        unloadGuardInstalled = true
        window.addEventListener("beforeunload", { event: Event ->
            if (readOnly && discardedChanges > 0) {
                event.preventDefault()
                // Both spellings: `preventDefault` is the modern contract, a non-empty
                // `returnValue` the one older engines and Electron's Chromium still honour.
                event.asDynamic().returnValue = "Changes in this read-only document will be lost."
            }
        })
    }

    private fun ensureStyle() {
        if (document.getElementById(STYLE_ID) != null) return
        val style = document.createElement("style") as HTMLElement
        style.id = STYLE_ID
        // Injected rather than added to `field.css` so the whole feature is one reviewable file, and
        // so a Field serving a cached stylesheet cannot show a half-applied version of it.
        style.textContent = """
            #$BANNER_ID {
                position: fixed;
                top: 0;
                left: 50%;
                transform: translateX(-50%);
                z-index: 100000;
                display: none;
                align-items: baseline;
                gap: 0.6em;
                max-width: 92vw;
                padding: 0.35em 0.9em 0.4em 0.9em;
                border: 1px solid var(--field-warning-outline, rgba(235, 150, 70, 0.85));
                border-top: none;
                border-radius: 0 0 6px 6px;
                background: var(--field-warning-fill, rgba(235, 150, 70, 0.95));
                color: var(--field-fg-on-warning, #1a1205);
                font-family: var(--field-font, sans-serif);
                font-size: 12px;
                font-weight: 500;
                letter-spacing: 0.02em;
                white-space: nowrap;
                overflow: hidden;
                text-overflow: ellipsis;
                /* Never intercept a click meant for the canvas underneath. */
                pointer-events: none;
                user-select: none;
            }
            #$BANNER_ID.afield-read-only-visible { display: flex; }
            #$BANNER_ID .afield-read-only-label {
                text-transform: uppercase;
                font-weight: 700;
            }
            #$BANNER_ID .afield-read-only-count { font-variant-numeric: tabular-nums; }
        """.trimIndent()
        document.head?.appendChild(style)
    }

    private fun render() {
        ensureStyle()
        val banner = (document.getElementById(BANNER_ID) as? HTMLElement)
            ?: (document.createElement("div") as HTMLElement).also {
                it.id = BANNER_ID
                document.body?.appendChild(it)
            }

        if (!readOnly) {
            banner.classList.remove("afield-read-only-visible")
            return
        }

        val tail = when (discardedChanges) {
            0 -> "Nothing you change here will be saved."
            1 -> "1 change so far will be discarded."
            else -> "$discardedChanges changes so far will be discarded."
        }
        banner.clear()
        banner.appendChild((document.createElement("span") as HTMLElement).also {
            it.className = "afield-read-only-label"
            it.textContent = "Read-only"
        })
        banner.appendChild((document.createElement("span") as HTMLElement).also {
            it.className = "afield-read-only-count"
            it.textContent = tail
        })
        banner.classList.add("afield-read-only-visible")
    }

    private fun HTMLElement.clear() {
        while (firstChild != null) removeChild(firstChild!!)
    }

    /** Test/diagnostic view of the state, for the headless check and the backdoor. */
    fun diagnosticState(): String =
        "readOnly=$readOnly discardedChanges=$discardedChanges unloadGuard=$unloadGuardInstalled"
}
