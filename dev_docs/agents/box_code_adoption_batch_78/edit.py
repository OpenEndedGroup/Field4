from pathlib import Path
import json,subprocess
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
rows=[]
def edit(path,fn):
 p=R/path;before=p.read_text() if p.exists() else '';after=fn(before);assert before!=after,path;p.write_text(after);rows.append({'path':path,'before':before,'after':after})
edit('src/jvmMain/kotlin/afield/runtime/lifecycle/RunOwnedListener.kt',lambda _:'''package afield.runtime.lifecycle

import afield.document.graph.Box
import java.util.concurrent.ConcurrentHashMap

/** A named callback slot on one object; removal always compares the exact subscription. */
private class ListenerKey(val owner: Any, val name: String) {
    override fun equals(other: Any?) = other is ListenerKey && owner === other.owner && name == other.name
    override fun hashCode() = 31 * System.identityHashCode(owner) + name.hashCode()
}

private val runListeners = ConcurrentHashMap<ListenerKey, AutoCloseable>()

/** Replace a named listener and release it on Stop, rerun or retirement of the registering run. */
internal fun <T> runOwnedListener(
    owner: Any,
    name: String,
    subscribe: ((T) -> Unit) -> (() -> Unit),
    callback: (T) -> Unit,
): () -> Unit {
    require(name.isNotBlank()) { "A listener needs a name" }
    val scope = requireNotNull(RunScope.current()) { "Register named listeners from a box run" }
    val key = ListenerKey(owner, name)
    val gate = Any()
    var closed = false
    var remove: (() -> Unit)? = null
    val registration = object : AutoCloseable {
        override fun close() = synchronized(gate) {
            if (!closed) {
                closed = true
                runListeners.remove(key, this)
                remove?.invoke()
                remove = null
            }
        }
    }
    runListeners.put(key, registration)?.close()
    try {
        synchronized(gate) {
            remove = subscribe { event ->
                synchronized(gate) {
                    if (!closed && !scope.isClosed && !scope.isStopped && runListeners[key] === registration) {
                        Box.withBoxContextNow(scope.box) {
                            RunScope.withCurrent(scope) { callback(event) }
                        }
                    }
                }
            }
        }
        scope.own(registration)
    } catch (failure: Throwable) {
        registration.close()
        throw failure
    }
    return registration::close
}
''')
edit('src/jvmMain/kotlin/afield/audio/synth/Orca.kt',lambda s:s.replace('    fun onCC(callback:', '''    /** Replace this named note callback; Stop or rerun of its registering box removes it. */
    fun onNote(name: String, callback: (OrcaNote) -> Unit) = apply {
        afield.runtime.lifecycle.runOwnedListener(this, "note:$name", { listener: (OrcaNote) -> Unit ->
            notes.add(listener)
            { notes.remove(listener); Unit }
        }, callback)
    }

    fun onCC(callback:''',1).replace('    /** [onChange], for one cell:', '''    /** Replace this named change callback; owned by the registering box run. */
    fun onChange(name: String, callback: (OrcaCell) -> Unit) = apply {
        afield.runtime.lifecycle.runOwnedListener(this, "change:$name", { listener: (OrcaCell) -> Unit ->
            changes.add(listener)
            { changes.remove(listener); Unit }
        }, callback)
    }

    /** [onChange], for one cell:''',1).replace('    /**\n     * Suspend until ORCA', '''    /** A named, run-owned cell callback; replacing it does not accumulate listeners. */
    fun onCell(x: Int, y: Int, name: String, callback: (Char) -> Unit) =
        onChange("cell:$x:$y:$name") { if (it.x == x && it.y == y) callback(it.after) }

    /**
     * Suspend until ORCA''',1))
edit('src/jvmMain/kotlin/afield/media/browser/PuppetBrowser.kt',lambda s:s+'''
/**
 * Replace the event listener under [name] on this browser. Stop or rerun of the registering box
 * removes it; callbacks retain that box's context. The returned function removes this listener only.
 */
fun PuppetBrowserControl.addEventListener(name: String, listener: (PuppetBrowserEvent) -> Unit): () -> Unit =
    afield.runtime.lifecycle.runOwnedListener(this, "puppet:$name", { addEventListener(it) }, listener)
''')
edit('src/jvmMain/kotlin/afield/ai/inference/onnx/DepthAnythingWorker.kt',lambda s:s.replace('    /**\n     * Offer a frame.', '''    init {
        // A worker made by live box code belongs to that run, including setup failures and Stop.
        afield.runtime.lifecycle.RunScope.current()?.own(this)
    }

    /**
     * Offer a frame.''',1))
edit('src/jvmMain/kotlin/afield/media/stream/FieldRTTStreamer.kt',lambda s:s.replace('renderer.renderList.others.remove(name)','renderer.renderList.others.remove(name, this)'))
edit('public/docs/onnx.md',lambda s:s.replace('// once\nval depth = DepthAnythingWorker','// Construct once per box run; Stop or rerun closes its worker thread.\nval depth = DepthAnythingWorker',1))
edit('public/docs/orca.md',lambda s:s.replace('o.onNote {','o.onNote("sound") {',1).replace('o.onNote { if','o.onNote("choose melody") { if',1).replace('o.onCell(4, 4) {','o.onCell(4, 4, "bass harmony") {',1).replace('lead.onNote {','lead.onNote("remember lead") {',1).replace('echo.onNote {','echo.onNote("voice echo") {',1).replace('### ','''Named callbacks replace the previous callback with that name on the same ORCA. They belong to
 the registering box run: Stop or rerun removes them. Use a different name for each independent
 part. Unnamed callbacks remain registered until you remove or discard their ORCA.

### ''',1))
edit('public/docs/puppet_browser.md',lambda s:s.replace('browserControl.addEventListener { event ->','browserControl.addEventListener("console diagnostics") { event ->',1).replace('The normal event stream already includes','The named listener replaces its predecessor on this browser; Stop or rerun of the registering\nbox removes it.\n\nThe normal event stream already includes',1).replace('    }\n```\n\n```diffstatic {data-lang=javascript}', '    }\nrequireNotNull(RunScope.current()).own { removeBridge() }\n```\n\n```diffstatic {data-lang=javascript}',1))
(H/'changes.json').write_text(json.dumps(rows,indent=2)+'\n')
print(len(rows),'paths')
