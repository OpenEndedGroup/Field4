package afield.python

import afield.*
import afield.logger.lprintln
import afield.python.PythonInterface.Companion.conf
import afield.python.PythonInterface.Companion.deliverErr
import afield.python.PythonInterface.Companion.deliverProgress
import afield.python.PythonInterface.Companion.deliverStdOut
import afield.python.PythonInterface.Companion.EMBED_IFRAME_STYLE
import afield.python.PythonInterface.Companion.resultLineFor
import afield.python.PythonInterface.Companion.formatOutput
import afield.python.PythonInterface.Companion.hasHadOutput
import afield.python.PythonInterface.Companion.truncateOutput
import afield.python.PythonInterface.Companion.inited
import afield.python.PythonInterface.Companion.thisBox
import afield.python.PythonInterface.Companion.thisLineOffset
import afield.runtime.TopologyWatcher
import afield.runtime.MainLanguageRuntime
import afield.runtime.applyRuntimeToggle
import afield.runtime.isMainLanguage
import afield.runtime.registerMainLanguageRuntime
import afield.stdlib.Delegation
import jep.*
import jep.python.PyCallable
import jep.python.PyObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import org.jetbrains.kotlin.util.classNameAndMessage
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.util.Locale
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * Python as a Field main language: a box whose source is Python, executed **in this process** by
 * CPython through JEP.
 *
 * In-process is the whole design and every consequence below follows from it. There is no
 * subprocess and no socket, so a Python box and a Kotlin box exchange objects rather than text; and
 * there is exactly **one interpreter and one namespace per Field process**, so two Python boxes
 * share globals whether or not you wanted them to. That is a deliberate trade — it is what makes a
 * cell in one box see a `DataFrame` another box built — and it is the first thing to know before
 * debugging "why does this variable already exist".
 *
 * Read it in four layers:
 *
 *  - **Configuration.** JEP needs a real CPython to bind against, chosen once and persisted:
 *    [PythonSettingsRequest], [PythonSettingsStatus] and [PythonInstallResult] are that
 *    negotiation, and `restartRequired` is honest — a new interpreter binding cannot be swapped
 *    into a live JVM. [makeInterpreter] runs early and returns silently when nothing is
 *    configured, which is why a checkout with no Python simply has no Python boxes rather than a
 *    broken startup.
 *  - **[PythonExecution]**, the `BoxExecution`. Beyond the usual output proxying it carries the
 *    cooperative event loop: an `asyncio` loop implemented in Python that yields to Field's runloop
 *    instead of blocking it, so `await` inside a box does not stop the editor.
 *  - **Output.** Everything a box prints funnels through one formatter, and three cases are special
 *    on purpose: a `\r` becomes a Field progress bar (which is how `tqdm` renders as a real one),
 *    an object exposing `HTML`/`Image`/`Markdown`/`Math`/`JSON` renders as itself, and a library
 *    that tries to open a browser page — plotly's `fig.show()`, bokeh, folium — is intercepted and
 *    the HTML delivered into the box as an iframe `srcdoc`. Nothing here opens a browser.
 *  - **Registration.** [installPython] and [pythonTopologyNeedsUpdating] are the same two-call
 *    shape every main language uses; see `dev_docs/small_language_anatomy.md`.
 *
 * A large part of this file is Python source held in Kotlin string literals — the event loop, the
 * display types, the output shims. It is code that has to run *inside* the interpreter, so it lives
 * here rather than in a `.py` on disk, where a fresh checkout could be missing it.
 *
 * [Python in Field](../python.md) is the prose.
 */

var Box.isPythonRoot by Delegation<Boolean?>()
var Box._pythonLocals by Delegation<LinkedHashMap<*,*>>(defaultValue = { LinkedHashMap<Any, Any>() })

/**
 * Splits a byte stream from the Python interpreter into frames.
 *
 * A frame terminated by `\n` is a completed line and is handed to [onLine] (with its trailing
 * newline preserved, matching the previous behaviour). A frame terminated by `\r` is an *in-place*
 * update — the signature of a progress bar such as `tqdm` — and is handed to [onProgress]. The final
 * `\n` that closes a carriage-return sequence is also treated as a progress frame rather than a line,
 * so a tqdm bar surfaces live instead of arriving as one giant string (or error) at the very end.
 *
 * Bytes are buffered and decoded as UTF-8 per frame, so multi-byte glyphs (tqdm's block characters)
 * survive intact — the old byte-at-a-time `Char(b)` decode mangled anything outside ASCII.
 */
private class OutputFramer(
    val onLine: (String) -> Unit,
    val onProgress: (String) -> Unit,
) : OutputStream() {
    private val buf = java.io.ByteArrayOutputStream(256)
    private var sawCarriageReturn = false

    override fun write(b: Int) {
        when (b) {
            '\n'.code -> {
                val frame = drain()
                if (sawCarriageReturn) {
                    if (frame.isNotBlank()) onProgress(frame)
                    sawCarriageReturn = false
                } else {
                    onLine(frame + "\n")
                }
            }
            '\r'.code -> {
                val frame = drain()
                if (frame.isNotBlank()) onProgress(frame)
                sawCarriageReturn = true
            }
            else -> buf.write(b)
        }
    }

    private fun drain(): String {
        val s = buf.toString(Charsets.UTF_8)
        buf.reset()
        return s
    }
}

/** tqdm renders `desc: 42%|██▍   | 42/100 [00:01<00:02, 20.1it/s]`; capture desc, percent, and detail. */
private val tqdmBarRegex = Regex("""(?:(.*?):\s*)?(\d{1,3})%\|[^|]*\|(.*)""")

/** Render a carriage-return progress frame as a live Field progress bar (updates in place). */
private fun deliverProgressText(box: Box, raw: String) {
    val text = raw.trim()
    if (text.isEmpty()) return
    val m = tqdmBarRegex.find(text)
    if (m != null) {
        val desc = m.groupValues[1].trim()
        val pct = m.groupValues[2].toIntOrNull()?.coerceIn(0, 100) ?: 0
        val detail = m.groupValues[3].trim()
        val label = listOf(desc, detail).filter { it.isNotEmpty() }.joinToString("  •  ")
        box.progressBar("tqdm", pct / 100.0, label.ifEmpty { null })
    } else {
        // No percentage (e.g. an indeterminate tqdm bar or a bespoke \r spinner): show the raw
        // frame as the detail of an indeterminate bar.
        box.progressBarIndeterminate("tqdm", text)
    }
    PythonInterface.hasHadOutput = true
}

@Serializable
data class PythonSettingsRequest(
    val kind: String = "venv",
    val path: String = "",
)

@Serializable
data class PythonSettingsStatus(
    val configured: Boolean,
    val source: String,
    val kind: String,
    val path: String,
    val pythonExecutable: String? = null,
    val pythonHome: String? = null,
    val jepLibrary: String? = null,
    val sitePackages: String? = null,
    val configPath: String,
    val message: String,
    val restartRequired: Boolean = false,
)

@Serializable
data class PythonInstallResult(
    val success: Boolean,
    val message: String,
    val log: String,
    /** The status after a successful install (settings are persisted so a restart binds JEP). */
    val status: PythonSettingsStatus? = null,
)


@RunsAt(early, priority = -20)
fun makeInterpreter() {
    if (!PythonInterface.pythonConfigAvailable) {
        return
    }
    try {
        PythonExecution().interpreter(null)
    } catch (e: Throwable) {
        lprintln("warning,python", "JEP failed to initialize from ${PythonInterface.pythonConfigSummary()}")
        lprintln("warning,python", "${e.javaClass.simpleName}: ${e.message ?: "unknown error"}")
        lprintln("warning,python", "Use /welcome Python settings to point Field at a venv or interpreter with `pip install jep`, then restart Field.")
        PythonInterface.markPythonUnavailable("Python/JEP was found but failed to initialize: ${e.message ?: e.javaClass.simpleName}. Use /welcome Python settings to choose a working venv or interpreter, then restart Field.")
    }
}

@RunsAt(early, priority = -10)
fun installPython() {
    Box.ioPersistentProperties.add("isPythonRoot")
    if (PythonInterface.pythonConfigAvailable) {
        registerMainLanguageRuntime(
            MainLanguageRuntime(
                id = "python",
                label = "Python",
                runtimeClass = PythonExecution::class,
                setLegacyRoot = { box, enabled -> box.isPythonRoot = enabled },
                applyTopology = ::pythonTopologyNeedsUpdating,
            ),
        )
    }
}

@RunsAt(plugins)
fun install() {
    TopologyWatcher("python") { pythonTopologyNeedsUpdating(it) }.start()
}


fun pythonTopologyNeedsUpdating(values: Collection<Box>) {
    applyRuntimeToggle(
        boxes = values,
        isRoot = { PythonInterface.pythonConfigAvailable && (it.isPythonRoot == true || it.isMainLanguage("python")) },
        runtimeClass = PythonExecution::class,
        newExecution = { _ -> PythonExecution() },
        language = "Python",
        customization = "",
        notifyCategory = "python"
    )
}


var interpretersForBoxes = mutableMapOf<Box, SharedInterpreter>()
var globalInterpreter: SharedInterpreter? = null
/** Venv site-packages path resolved during init, used to fix sys.path before JEP's import */
var resolvedVenvSitePackagesPath: String? = null

@Suppress("UNCHECKED_CAST")
class PythonExecution : BoxExecution {

    fun interpreter(b: Box? = null): SharedInterpreter {
        if (!inited) {
            conf.redirectStdout(OutputFramer(
                onLine = { line ->
                    print(line)
                    deliverStdOut(line)
                },
                onProgress = { frame -> deliverProgress(frame) },
            )).redirectStdErr(OutputFramer(
                onLine = { line ->
                    print("(error) $line")
                    deliverErr(line)
                },
                onProgress = { frame -> deliverProgress(frame) },
            ))
            SharedInterpreter.setConfig(conf);

            // JEP 4.3.1 sets Jep.threadUsed=true during setConfig, which then
            // prevents SharedInterpreter from initializing on this thread.
            // Reset it via reflection so our custom SharedInterpreter can init.
            try {
                val jepClass = Class.forName("jep.Jep")
                val threadUsedField = jepClass.getDeclaredField("threadUsed")
                threadUsedField.isAccessible = true
                val threadLocal = threadUsedField.get(null) as ThreadLocal<*>
                threadLocal.remove()
            } catch (e: Exception) {
                lprintln("warning,python", "could not reset Jep.threadUsed via reflection: $e")
            }

            globalInterpreter = object : SharedInterpreter() {
                @Suppress("OVERRIDE_DEPRECATION")
                override fun isValidThread() {
                    return
                }
                override fun configureInterpreter(config: jep.JepConfig) {
                    // Pre-insert venv site-packages into sys.path and clear any
                    // cached jep module BEFORE JEP's configureInterpreter runs
                    // "import jep".  Without this, Python finds the base
                    // installation's (miniconda) jep 4.2.2 instead of the
                    // venv's 4.3.1, causing a setupImporter() arity mismatch.
                    val venvSP = resolvedVenvSitePackagesPath
                    if (venvSP != null) {
                        val escaped = venvSP.replace("\\", "\\\\").replace("'", "\\'")
                        exec("import sys; sys.path.insert(0, '$escaped')")
                        exec("for k in [k for k in sys.modules if k == 'jep' or k.startswith('jep.')]: del sys.modules[k]")
                    }
                    super.configureInterpreter(config)
                }
            }
            inited = true


            val startupText = """
import os
import sys
import time
import heapq
import collections
import asyncio
import traceback
import weakref
import inspect
import asyncio.events
from typing import Any, Callable, Deque, List, Tuple, Optional
import sys
import contextvars
from collections import defaultdict
import warnings
import linecache
import secrets
import ast

sys.executable = "${PythonInterface.resolvedPythonExecutable?.replace("\\", "\\\\") ?: "/usr/bin/python3"}"
print(" python interpreter is starting up ")
${if (PythonInterface.resolvedVenvPath != null) """
# Activate venv site-packages
_venv_path = "${PythonInterface.resolvedVenvPath!!.replace("\\", "\\\\")}"
import site as _site, glob as _glob
for _sp in _glob.glob(_venv_path + "/lib/python*/site-packages"):
    if _sp not in sys.path:
        sys.path.insert(0, _sp)
        _site.addsitedir(_sp)
sys.prefix = _venv_path
sys.exec_prefix = _venv_path
print(f" venv activated: {_venv_path}")
""" else ""}

class CooperativeEventLoop(asyncio.AbstractEventLoop):
    def __init__(self):
        # — Ready queue of Handle objects —
        self._ready: Deque[asyncio.Handle] = collections.deque()
        # — Scheduled timers: heap of (when, order, TimerHandle) —
        self._scheduled: List[Tuple[float, int, asyncio.TimerHandle]] = []
        self._scheduled_count = 0
        # — Loop state & config —
        self._stopped = False
        self._debug = bool(os.environ.get("PYTHONASYNCIODEBUG"))
        self._exception_handler: Optional[Callable] = None
        self._task_factory: Optional[Callable[[asyncio.AbstractEventLoop, Any], asyncio.Task]] = None

    def get_task_factory(self):
        return self._task_factory

    def set_task_factory(self, factory: Optional[Callable]):
        if factory is not None and not callable(factory):
            raise TypeError("task factory must be a callable or None")
        self._task_factory = factory

    # ─── Core timing and scheduling ────────────────────

    def time(self) -> float:
        return time.monotonic()

    def call_soon(self, callback: Callable, *args: Any, context=None) -> asyncio.Handle:
        handle = asyncio.Handle(callback, args, self, context=context)
        self._ready.append(handle)
        return handle

    def call_later(self, delay: float, callback: Callable, *args: Any, context=None) -> asyncio.Handle:
        return self.call_at(self.time() + delay, callback, *args, context=context)

    def call_at(self, when: float, callback: Callable, *args: Any, context=None) -> asyncio.TimerHandle:
        timer = asyncio.TimerHandle(when, callback, args, self, context=context)
        self._scheduled_count += 1
        heapq.heappush(self._scheduled, (when, self._scheduled_count, timer))
        return timer

    def create_task(self, coro: Any, *, name: Optional[str] = None, context=None, **kwargs) -> asyncio.Task:
        factory = self._task_factory
        if factory is not None:
            task = _call_task_factory(factory, self, coro, context=context)
            if not isinstance(task, asyncio.Task):
                raise TypeError("task factory must return an asyncio.Task")
            if name is not None:
                try:
                    task.set_name(name)
                except Exception:
                    pass
            try:
                if getattr(task, "_loop", self) is not self:
                    warnings.warn("Task factory returned a Task bound to a different loop", RuntimeWarning)
            except Exception:
                pass
            return task

        # Default path (no factory): construct Task directly, being version-tolerant
        try:
            task = asyncio.Task(coro, loop=self, name=name, context=context, **kwargs)
        except TypeError:
            try:
                task = asyncio.Task(coro, loop=self, name=name, context=context)
            except TypeError:
                task = asyncio.Task(coro, loop=self)
                if name is not None:
                    try:
                        task.set_name(name)
                    except Exception:
                        pass
        return task

    def create_future(self) -> asyncio.Future:
        try:
            return asyncio.Future(loop=self)
        except TypeError:
            return asyncio.Future()

    # ─── Exception handling ───────────────────────────

    def set_exception_handler(self, handler: Optional[Callable]):
        if handler is not None and not callable(handler):
            raise TypeError("exception handler must be callable or None")
        self._exception_handler = handler

    def default_exception_handler(self, context: dict):
        msg = context.get("message", "Unhandled exception in event loop")
        exc = context.get("exception")
        sys.stderr.write(f"{msg}\n")
        if exc:
            import traceback
            traceback.print_exception(type(exc), exc, exc.__traceback__)

    def call_exception_handler(self, context: dict):
        if self._exception_handler:
            try:
                self._exception_handler(self, context)
            except Exception as e:
                sys.stderr.write("Error in user exception handler:\n")
                import traceback
                traceback.print_exception(type(e), e, e.__traceback__)
                self.default_exception_handler(context)
        else:
            self.default_exception_handler(context)

    # ─── The execute pump ─────────────────────────────

    def execute(self, *, max_tasks: Optional[int] = None) -> None:
        now = self.time()
        # Move due timers into ready
        while self._scheduled and self._scheduled[0][0] <= now:
            _, _, timer = heapq.heappop(self._scheduled)
            self._ready.append(timer)

        # Run ready handles
        run_count = 0
        while self._ready and (max_tasks is None or run_count < max_tasks):
            handle = self._ready.popleft()
            try:
                handle._run()
            except Exception as exc:
                self.call_exception_handler({
                    "message": "Exception in callback",
                    "exception": exc,
                    "handle": handle,
                })
            run_count += 1

    # ─── Running the loop ─────────────────────────────

    def run_until_complete(self, future: Any):
        task = asyncio.ensure_future(future, loop=self)
        asyncio.events._set_running_loop(self)
        try:
            while not task.done():
                self.execute()
                time.sleep(0)
        finally:
            asyncio.events._set_running_loop(None)
        return task.result()

    def run_forever(self):
        asyncio.events._set_running_loop(self)
        self._stopped = False
        try:
            while not self._stopped:
                self.execute()
                time.sleep(0)
        finally:
            asyncio.events._set_running_loop(None)

    def stop(self):
        self._stopped = True

    def is_running(self) -> bool:
        return not self._stopped

    # ─── Timer cancellation support ───────────────────

    def _timer_handle_cancelled(self, timer_handle: asyncio.TimerHandle):
        self._scheduled = [
            (t, cnt, h) for (t, cnt, h) in self._scheduled
            if h is not timer_handle
        ]
        heapq.heapify(self._scheduled)

    # ─── Debug, executor, cleanup ────────────────────

    def get_debug(self) -> bool:
        return self._debug

    def set_debug(self, enabled: bool):
        self._debug = enabled

    def default_executor(self):
        return None

    def close(self):
        self._ready.clear()
        self._scheduled.clear()

from afield.python import PythonInterface

_cel = CooperativeEventLoop()
PythonInterface.integrateEventLoop(lambda: _cel.execute())
asyncio.events._set_running_loop(_cel)

class CelEventLoopPolicy(asyncio.DefaultEventLoopPolicy):
    def new_event_loop(self, __cel=_cel):
        return __cel

policy = CelEventLoopPolicy()
asyncio.set_event_loop_policy(policy)

# ─────────────────────────────────────────────────────────────────────
#                       REPL "standard library"
# ─────────────────────────────────────────────────────────────────────

isolatedNamespace = dict()

# --- debug util ---
def _dbg(msg: str):
    print(f"[repl-debug] {msg}", file=sys.stderr)

# --- Context tag for provenance (e.g., file/buffer id) ---
REPL_SOURCE_ID = contextvars.ContextVar("repl_source_id", default=None)

# --- Registry of tasks by source id ---
_TASKS_BY_SOURCE: dict[str, set[asyncio.Task]] = defaultdict(set)

# Track which loops we’ve already patched with our factory (weakly to avoid leaks)
_INSTALLED_ON_LOOPS = weakref.WeakKeyDictionary()

def _call_task_factory(factory, loop, coro, context=None):
    ""${'"'}
    Call a task factory with a flexible signature.
    Tries (loop, coro, context) → (loop, coro) → (coro) and kwarg forms.
    ""${'"'}
    attempts = (
        lambda: factory(loop, coro, context),
        lambda: factory(loop, coro),
        lambda: factory(coro),
        lambda: factory(loop=loop, coro=coro, context=context),
        lambda: factory(loop=loop, coro=coro),
    )
    last = None
    for fn in attempts:
        try:
            return fn()
        except Exception as e:
            msg = str(e)
            if isinstance(e, TypeError) or "Invalid number of arguments" in msg:
                last = e
                continue
            raise
    raise RuntimeError("Incompatible task factory signature") from last


def _install_repl_task_factory(loop: asyncio.AbstractEventLoop):
    if loop in _INSTALLED_ON_LOOPS:
        return

    prev_factory = loop.get_task_factory()

    def factory(*args, **kwargs):
        # Accept (loop, coro) or (loop, coro, context)
        if not args:
            raise TypeError("task factory requires at least (loop, coro)")
        loop = args[0]
        coro = args[1] if len(args) >= 2 else kwargs.get("coro")
        context = args[2] if len(args) >= 3 else kwargs.get("context")
    
        # Delegate to previous factory if present, else create a Task directly
        if prev_factory is not None:
            task = _call_task_factory(prev_factory, loop, coro, context=context)
            if not isinstance(task, asyncio.Task):
                raise TypeError("previous task factory returned non-Task")
        else:
            try:
                task = asyncio.tasks.Task(coro, loop=loop)
            except TypeError:
                task = asyncio.tasks.Task(coro)
    
        src = REPL_SOURCE_ID.get()
        if src is not None:
            try:
                task.set_name(f"repl:{src}")
            except Exception:
                pass
            _TASKS_BY_SOURCE[src].add(task)
    
            def _cleanup(t: asyncio.Task, _src=src):
                _TASKS_BY_SOURCE[_src].discard(t)
                if not _TASKS_BY_SOURCE[_src]:
                    _TASKS_BY_SOURCE.pop(_src, None)
            task.add_done_callback(_cleanup)
    
        return task

    loop.set_task_factory(factory)
    _INSTALLED_ON_LOOPS[loop] = True

# ---------- linecache helper ----------
def _ensure_linecache(filename: str, src: str):
    ""${'"'}Make traceback source lookups show the exact user code.""${'"'}
    lines = src.splitlines(keepends=True)
    linecache.cache[filename] = (len(src), None, lines, filename)

# ---------- AST helpers (no unparse) ----------
def _as_load(node: ast.AST) -> ast.expr:
    ""${'"'}Convert an assignable target into a loadable expression (first target for tuples/lists).""${'"'}
    if isinstance(node, ast.Name):
        return ast.copy_location(ast.Name(id=node.id, ctx=ast.Load()), node)
    if isinstance(node, ast.Attribute):
        return ast.copy_location(ast.Attribute(value=_as_load(node.value), attr=node.attr, ctx=ast.Load()), node)
    if isinstance(node, ast.Subscript):
        return ast.copy_location(ast.Subscript(value=_as_load(node.value), slice=node.slice, ctx=ast.Load()), node)
    if isinstance(node, (ast.Tuple, ast.List)) and node.elts:
        return _as_load(node.elts[0])
    return ast.copy_location(ast.Name(id="_", ctx=ast.Load()), node)

def _capture_last_value_inplace(mod: ast.Module, capture_name: str) -> bool:
    ""${'"'}
    Rewrites the module so that the last statement stores its value into `capture_name`.
    Returns True if we inserted capture code, else False.
    ""${'"'}
    if not mod.body:
        return False

    last = mod.body[-1]

    if isinstance(last, ast.Expr):
        assign = ast.Assign(
            targets=[ast.Name(id=capture_name, ctx=ast.Store())],
            value=last.value,
        )
        ast.copy_location(assign, last)
        mod.body[-1] = assign
        ast.fix_missing_locations(assign)
        return True

    if isinstance(last, ast.Assign):
        tgt = _as_load(last.targets[0])
    elif isinstance(last, (ast.AnnAssign, ast.AugAssign)):
        tgt = _as_load(last.target)
    else:
        return False

    assign2 = ast.Assign(
        targets=[ast.Name(id=capture_name, ctx=ast.Store())],
        value=tgt,
    )
    ast.copy_location(assign2, last)  # same line to avoid skew
    mod.body.append(assign2)
    ast.fix_missing_locations(assign2)
    return True

def _looks_like_top_level_await_syntax(err: SyntaxError) -> bool:
    msg = (getattr(err, "msg", "") or str(err)).lower()
    needles = ("'await' outside function", "async for outside async function", "async with outside async function")
    return any(n in msg for n in needles)

# ---------- async core with TLA ----------
async def aexec_with_return(code: str, globals: dict, locals=isolatedNamespace, *, debug: bool = False, filename: str = "<repl>"):
    ""${'"'}
    Async path: always allows top-level await. Preserves original source in tracebacks.
    ""${'"'}
    capture_name = f"__repl_value_{secrets.token_hex(4)}__"
    try:
        mod = ast.parse(code, mode="exec")
        captured = _capture_last_value_inplace(mod, capture_name)
        ast.fix_missing_locations(mod)
        _ensure_linecache(filename, code)

        flags = ast.PyCF_ALLOW_TOP_LEVEL_AWAIT
        code_obj = compile(mod, filename, "exec", flags=flags)
        maybe_coro = eval(code_obj, globals, locals)
        if inspect.isawaitable(maybe_coro):
            if debug: _dbg("awaiting module body (TLA)")
            await maybe_coro
        if not captured:
            return None
        out = locals.get(capture_name, None)
        locals.pop(capture_name, None)
        if inspect.isawaitable(out):
            if debug: _dbg("awaiting captured last value")
            out = await out
        return out
    except Exception as e:
        if debug:
            _dbg("Exception in aexec_with_return; traceback follows:")
            traceback.print_exception(type(e), e, e.__traceback__, file=sys.stderr)
        raise

# ---------- smart sync/async wrapper with tracking ----------
def _exec_with_return2(code: str, globals: dict, locals=isolatedNamespace, *, debug: bool = False, source_id: str | None = None):
    ""${'"'}
    - No running loop: run to completion (supports TLA).
    - Running loop: try sync fast-path (no TLA) so pure sync code returns immediately.
      If TLA is required, schedule the async path and return a Task.
      REPL_SOURCE_ID is set so any create_task inside code gets tagged & tracked.
    ""${'"'}
    filename = f"<repl:{source_id or 'cell'}>"

    # No running loop → simple: run async core to completion
    try:
        loop = asyncio.get_running_loop()
        has_running_loop = True
    except RuntimeError:
        has_running_loop = False

    if not has_running_loop:
        if debug: _dbg("No running loop → asyncio.run(aexec_with_return)")
        return asyncio.run(aexec_with_return(code, globals, locals, debug=debug, filename=filename))

    # Running loop: install tracking and tag tasks
    _install_repl_task_factory(loop)
    token = REPL_SOURCE_ID.set(source_id)
    try:
        # Sync fast-path: compile AST without TLA, capturing last value
        capture_name = f"__repl_value_{secrets.token_hex(4)}__"
        try:
            mod = ast.parse(code, mode="exec")
            captured = _capture_last_value_inplace(mod, capture_name)
            ast.fix_missing_locations(mod)
            _ensure_linecache(filename, code)
            code_obj = compile(mod, filename, "exec")  # no TLA
        except SyntaxError as syn:
            if debug: _dbg(f"sync fast-path failed with SyntaxError: {syn.msg!r}")
            if _looks_like_top_level_await_syntax(syn):
                if debug: _dbg("Detected TLA → returning Task for async execution")
                return asyncio.create_task(aexec_with_return(code, globals, locals, debug=debug, filename=filename))
            raise
        except Exception as e:
            if debug:
                _dbg("Unexpected error compiling sync fast-path; traceback follows:")
                traceback.print_exception(type(e), e, e.__traceback__, file=sys.stderr)
            raise

        # Execute synchronously (CPU-only) even though a loop is running
        try:
            eval(code_obj, globals, locals)
        except Exception as e:
            if debug:
                _dbg("Exception during sync fast-path exec; traceback follows:")
                traceback.print_exception(type(e), e, e.__traceback__, file=sys.stderr)
            raise

        if not captured:
            return None

        out = locals.get(capture_name, None)
        locals.pop(capture_name, None)

        # If last value is awaitable but user didn't write 'await', return Task to await just that value
        if inspect.isawaitable(out):
            if debug: _dbg("last value is awaitable → returning Task that awaits it")
            async def _await_value(x): return await x
            return asyncio.create_task(_await_value(out))

        # Purely sync → return immediately
        return out

    finally:
        REPL_SOURCE_ID.reset(token)

def _print(*a):
    line = sys._getframe(1).f_lineno
    from afield.python import PythonInterface
    if PythonInterface.thisBox:
        PythonInterface.thisBox.getOutput().getProxy()(" ".join([str(x) for x in a]), False, line+PythonInterface.thisLineOffset)
        PythonInterface.hasHadOutput = True
    else:
        print(" ".join([str(x) for x in a]))

# ---------------------------
# Display API (IPython-compatible)
# ---------------------------

import html as _html_mod
import base64 as _base64_mod

class HTML:
    ""${'"'}Raw HTML display object.""${'"'}
    def __init__(self, data=""):
        self.data = str(data)
    def _repr_html_(self):
        return self.data

class Image:
    ""${'"'}Image display object. Supports data (bytes), url, or filename.""${'"'}
    def __init__(self, data=None, url=None, filename=None, width=None, height=None, format=None):
        self._data = data
        self.url = url
        self.filename = filename
        self.width = width
        self.height = height
        self.format = format
    def _repr_html_(self):
        style = ""
        if self.width:
            style += f"width:{self.width}px;" if isinstance(self.width, (int, float)) else f"width:{self.width};"
        if self.height:
            style += f"height:{self.height}px;" if isinstance(self.height, (int, float)) else f"height:{self.height};"
        style_attr = f' style="{style}"' if style else ""
        if self.url:
            return f'<img src="{_html_mod.escape(self.url)}"{style_attr}>'
        if self.filename:
            import mimetypes
            mime = mimetypes.guess_type(self.filename)[0] or "image/png"
            with open(self.filename, "rb") as f:
                b64 = _base64_mod.b64encode(f.read()).decode("ascii")
            return f'<img src="data:{mime};base64,{b64}"{style_attr}>'
        if self._data:
            fmt = self.format or "png"
            mime = f"image/{fmt}"
            if isinstance(self._data, bytes):
                b64 = _base64_mod.b64encode(self._data).decode("ascii")
            else:
                b64 = _base64_mod.b64encode(bytes(self._data)).decode("ascii")
            return f'<img src="data:{mime};base64,{b64}"{style_attr}>'
        return ""

class Markdown:
    ""${'"'}Markdown display object — rendered as a preformatted block unless a markdown library is available.""${'"'}
    def __init__(self, data=""):
        self.data = str(data)
    def _repr_html_(self):
        try:
            import markdown as _md
            return _md.markdown(self.data, extensions=["fenced_code", "tables"])
        except ImportError:
            return "<pre>" + _html_mod.escape(self.data) + "</pre>"

class Math:
    ""${'"'}LaTeX math display object.""${'"'}
    def __init__(self, data=""):
        self.data = str(data)
    def _repr_html_(self):
        escaped = _html_mod.escape(self.data)
        return '<div class="math">' + escaped + '</div>'

class JSON:
    ""${'"'}JSON display object — pretty-printed in a <pre> block.""${'"'}
    def __init__(self, data=None):
        self.data = data
    def _repr_html_(self):
        import json as _json
        txt = _json.dumps(self.data, indent=2, default=str)
        return "<pre>" + _html_mod.escape(txt) + "</pre>"

def _to_html(obj):
    ""${'"'}Convert a display object to HTML. Handles IPython display protocol, PIL images, matplotlib figures, and plain objects.""${'"'}
    # PIL / Pillow Image
    try:
        import PIL.Image
        if isinstance(obj, PIL.Image.Image):
            import io as _io
            buf = _io.BytesIO()
            fmt = "PNG" if obj.mode in ("RGBA", "LA", "PA", "P") else "JPEG"
            obj.save(buf, format=fmt)
            b64 = _base64_mod.b64encode(buf.getvalue()).decode("ascii")
            mime = "image/png" if fmt == "PNG" else "image/jpeg"
            return f'<img src="data:{mime};base64,{b64}">'
    except ImportError:
        pass
    # matplotlib Figure
    try:
        import matplotlib.figure
        if isinstance(obj, matplotlib.figure.Figure):
            import io as _io
            buf = _io.BytesIO()
            obj.savefig(buf, format="png", bbox_inches="tight")
            buf.seek(0)
            b64 = _base64_mod.b64encode(buf.getvalue()).decode("ascii")
            return f'<img src="data:image/png;base64,{b64}">'
    except ImportError:
        pass
    # IPython-style _repr_ protocol
    if hasattr(obj, "_repr_html_"):
        h = obj._repr_html_()
        if h is not None:
            return h
    if hasattr(obj, "_repr_svg_"):
        s = obj._repr_svg_()
        if s is not None:
            return s
    if hasattr(obj, "_repr_png_"):
        png = obj._repr_png_()
        if png is not None:
            b64 = _base64_mod.b64encode(png if isinstance(png, bytes) else bytes(png)).decode("ascii")
            return f'<img src="data:image/png;base64,{b64}">'
    if hasattr(obj, "_repr_jpeg_"):
        jpg = obj._repr_jpeg_()
        if jpg is not None:
            b64 = _base64_mod.b64encode(jpg if isinstance(jpg, bytes) else bytes(jpg)).decode("ascii")
            return f'<img src="data:image/jpeg;base64,{b64}">'
    return "<pre>" + _html_mod.escape(str(obj)) + "</pre>"

def display(*objs, channel="main"):
    ""${'"'}Display objects as HTML in a Field output area. Compatible with IPython display objects.""${'"'}
    from afield.python import PythonInterface
    box = PythonInterface.thisBox
    if not box:
        for o in objs:
            print(o)
        return
    for obj in objs:
        box.display(channel, _to_html(obj))
    PythonInterface.hasHadOutput = True

def clear_output(wait=False, channel=None):
    ""${'"'}Clear the output area. If channel is None, clears all channels.""${'"'}
    from afield.python import PythonInterface
    box = PythonInterface.thisBox
    if not box:
        return
    from afield import OutputsKt
    if channel:
        OutputsKt.displayClear(box, channel)
    else:
        OutputsKt.displayClearAll(box)

# ---------------------------
# FileUpload widget
# ---------------------------

class FileUpload:
    ""${'"'}File upload widget compatible with ipywidgets.FileUpload API.

    Usage:
        uploader = FileUpload(accept='.csv', multiple=False)
        display(uploader)
        # After user selects file(s), uploader.value is populated.
        # Access uploader.value in a later cell or execution:
        for f in uploader.value:
            print(f['name'], len(f['content']), 'bytes')
    ""${'"'}
    def __init__(self, accept='', multiple=False, description='Upload'):
        self.accept = accept
        self.multiple = multiple
        self.description = description
        self._value = ()
        self._last_seen = ()
        self._callbacks = []
        self._control_id = None

    @property
    def value(self):
        ""${'"'}Returns uploaded files as a tuple of dicts with keys: name, type, size, content (bytes).""${'"'}
        if self._control_id:
            from afield import OutputsKt
            entries = OutputsKt.getFileUploadData(self._control_id)
            if entries is not None:
                result = []
                for i in range(entries.size()):
                    entry = entries.get(i)
                    result.append({
                        'name': str(entry.getName()),
                        'type': str(entry.getType()),
                        'size': int(entry.getSize()),
                        'content': _base64_mod.b64decode(entry.getDataBase64())
                    })
                new_value = tuple(result)
                if new_value != self._last_seen:
                    old = self._value
                    self._value = new_value
                    self._last_seen = new_value
                    change = {'new': new_value, 'old': old, 'owner': self, 'name': 'value', 'type': 'change'}
                    for cb in self._callbacks:
                        try:
                            cb(change)
                        except Exception as e:
                            print(f"FileUpload callback error: {e}")
        return self._value

    def observe(self, handler, names=None):
        ""${'"'}Register a callback for when files are uploaded. Fires on next .value access.""${'"'}
        self._callbacks.append(handler)

    def _repr_html_(self):
        import uuid as _uuid
        self._control_id = "file-upload-" + str(_uuid.uuid4())
        accept_attr = f'accept="{_html_mod.escape(self.accept)}"' if self.accept else ''
        multiple_attr = 'multiple' if self.multiple else ''
        return (
            '<div class="field-control field-file-upload">'
            f'<label class="field-label" style="flex:0 0 auto;">{_html_mod.escape(self.description)}</label>'
            f'<input type="file" id="{self._control_id}" {accept_attr} {multiple_attr}'
            f' onchange="handleFileUploadInput(this)" style="flex:1 1 auto;" />'
            f'<span class="field-value" id="{self._control_id}-status"></span>'
            '</div>'
        )

# ---------------------------
# Monitoring & control API
# ---------------------------

def tasks_for(source_id: str) -> list[asyncio.Task]:
    return [t for t in _TASKS_BY_SOURCE.get(source_id, set()) if not t.done()]

def any_running(source_id: str) -> bool:
    return any(not t.done() for t in _TASKS_BY_SOURCE.get(source_id, set()))

def describe_tasks(source_id: str) -> list[dict]:
    out = []
    for t in list(_TASKS_BY_SOURCE.get(source_id, set())):
        try:
            coro = t.get_coro()
        except Exception:
            coro = None
        d = {
            "name": t.get_name() if hasattr(t, "get_name") else str(t),
            "done": t.done(),
            "cancelled": t.cancelled(),
            "repr": repr(t),
            "coro": repr(coro),
        }
        if t.done() and not t.cancelled():
            try:
                d["exception"] = repr(t.exception())
            except Exception:
                d["exception"] = None
        out.append(d)
    return out

async def cancel_tasks(source_id: str, *, timeout: float = 2.0) -> dict:
    tasks = list(_TASKS_BY_SOURCE.get(source_id, set()))
    if not tasks:
        return {"requested": 0, "done": 0, "pending": 0}

    for t in tasks:
        t.cancel()

    done, pending = await asyncio.wait(tasks, timeout=timeout, return_when=asyncio.ALL_COMPLETED)
    _TASKS_BY_SOURCE[source_id] -= done
    return {"requested": len(tasks), "done": len(done), "pending": len(pending)}

# ---------------------------
# print override
# ---------------------------
# Route print() through Field so its output is formatted once, centrally: multi-line values
# (2-D numpy arrays, DataFrames) render in a faithful monospace block instead of being torn
# line-by-line by the stdout redirect, and enormous output is truncated rather than shipped
# whole to the browser (which used to lock up the front end).
import builtins as _builtins
_orig_print = _builtins.print

# Which line of the cell is printing. `_exec_with_return2` compiles a fragment under the filename
# "<repl:{box id}>", so the innermost frame carrying that filename is the cell, and its f_lineno is
# 1-based within the fragment; thisLineOffset carries the fragment's own start line in the box.
# Every print used to be published at line -1, which the output area resolves to the END of the
# fragment: a one-line print appeared beside the cell's last line rather than beside the statement
# that made it, and two such prints landed on the same line and overwrote one another. A print from
# library code has no "<repl:" frame and keeps the old -1.
def _field_source_line(_PythonInterface):
    try:
        frame = sys._getframe(2)
    except ValueError:
        return -1
    while frame is not None:
        if frame.f_code.co_filename.startswith("<repl:"):
            return _PythonInterface.thisLineOffset + frame.f_lineno
        frame = frame.f_back
    return -1

def _field_print(*args, sep=' ', end='\n', file=None, flush=False):
    from afield.python import PythonInterface
    box = PythonInterface.thisBox
    if box is None or (file is not None and file is not sys.stdout):
        return _orig_print(*args, sep=sep, end=end, file=file, flush=flush)
    text = sep.join(str(a) for a in args)
    box.getOutput().getProxy()(PythonInterface.formatOutput(text), False, _field_source_line(PythonInterface))
    PythonInterface.hasHadOutput = True

_builtins.print = _field_print

# ---------------------------
# Embedded browser output
# ---------------------------
# When a library (plotly's fig.show(), bokeh, folium, ...) tries to open a page in an external
# web browser, embed that page in an output-area iframe instead. plotly's default renderer runs a
# one-shot local HTTP server and calls webbrowser.get(...).open(url); we intercept both the module
# function and the controller returned by webbrowser.get so either path lands in Field.
import webbrowser as _webbrowser
import urllib.parse as _urllib_parse
import urllib.request as _urllib_request
import threading as _threading

_FIELD_EMBED_CHANNEL = "browser"

def _field_embed_show(box, iframe_html):
    box.display(_FIELD_EMBED_CHANNEL, iframe_html)
    try:
        from afield import OutputsKt
        OutputsKt.displayOpen(box, _FIELD_EMBED_CHANNEL)
    except Exception:
        pass
    from afield.python import PythonInterface
    PythonInterface.hasHadOutput = True

# Both axes are in px, deliberately not "100%". PythonInterface.EMBED_IFRAME_STYLE carries the
# reason; a test holds it to pixels. It is NOT the only place the frame is styled -- field.css's
# `.afield-embed-frame` is the other half, and the background has to agree in both.
_FIELD_IFRAME_STYLE = "${EMBED_IFRAME_STYLE}"

# The embedded document paints its own background, and a full plotly/bokeh page defaults to a
# white body -- which lands as a bright slab inside a dark box no matter what the IFRAME is set to.
# `plotly_dark` colours the figure's paper, not the page around it. So neutralise the page: only the
# backdrop, so anything the document actually draws still wins.
#
# The trade is real and one-directional: a document that assumes a white page and draws dark text on
# it (a folium legend, a light-theme bokeh toolbar) now draws that dark text over Field's dark box.
# Pass `template="plotly_dark"` or the equivalent, as this page's example does.
_FIELD_EMBED_RESET = "<style>html,body{background:transparent !important;margin:0;padding:0;}</style>"

def _field_embed_document(box, full_html):
    lowered = full_html.lower()
    head = lowered.find("<head>")
    if head >= 0:
        full_html = full_html[:head + 6] + _FIELD_EMBED_RESET + full_html[head + 6:]
    else:
        full_html = _FIELD_EMBED_RESET + full_html
    srcdoc = full_html.replace("&", "&amp;").replace('"', "&quot;")
    iframe = '<iframe class="afield-embed-frame" style="' + _FIELD_IFRAME_STYLE + '" srcdoc="' + srcdoc + '"></iframe>'
    _field_embed_show(box, iframe)

def _field_embed_src(box, url):
    iframe = '<iframe class="afield-embed-frame" style="' + _FIELD_IFRAME_STYLE + '" src="' + _html_mod.escape(url, quote=True) + '"></iframe>'
    _field_embed_show(box, iframe)

def _field_open_url(url, new=0, autoraise=True):
    from afield.python import PythonInterface
    box = PythonInterface.thisBox
    if box is None or not isinstance(url, str):
        return _orig_webbrowser_open(url, new, autoraise)
    try:
        parsed = _urllib_parse.urlparse(url)
        if parsed.scheme == "file":
            path = _urllib_request.url2pathname(parsed.path)
            with open(path, "r", encoding="utf-8", errors="replace") as fh:
                _field_embed_document(box, fh.read())
            return True
        if parsed.scheme in ("http", "https") and parsed.hostname in ("127.0.0.1", "localhost", "::1"):
            # plotly/bokeh started a one-shot local server and will block in handle_request()
            # until this URL is fetched. Fetch it ourselves on a daemon thread: our GET satisfies
            # the server (so the cell never hangs — no dependency on a front end being present)
            # and hands us the whole document, which we embed as a self-contained srcdoc iframe.
            def _worker(b=box, u=url):
                try:
                    with _urllib_request.urlopen(u, timeout=20) as resp:
                        data = resp.read().decode("utf-8", "replace")
                    _field_embed_document(b, data)
                except Exception:
                    pass
            _threading.Thread(target=_worker, name="field-embed-fetch", daemon=True).start()
            PythonInterface.hasHadOutput = True
            return True
        _field_embed_src(box, url)
        return True
    except Exception:
        return _orig_webbrowser_open(url, new, autoraise)

class _FieldBrowser:
    def open(self, url, new=0, autoraise=True):
        return _field_open_url(url, new, autoraise)
    def open_new(self, url):
        return _field_open_url(url, 1)
    def open_new_tab(self, url):
        return _field_open_url(url, 2)

_orig_webbrowser_open = _webbrowser.open
_webbrowser.open = _field_open_url
_webbrowser.open_new = lambda url: _field_open_url(url, 1)
_webbrowser.open_new_tab = lambda url: _field_open_url(url, 2)
_webbrowser.get = lambda using=None: _FieldBrowser()

_orig_print(" python interpreter has finished starting up ")

            """.trimIndent()

            lprintln("info,python", "calling startup text for python interpreter")
            globalInterpreter!!.exec(startupText)
            lprintln("info,python", "finished calling startup text for python interpreter")
        }
        return globalInterpreter ?: throw IllegalStateException("Python/JEP interpreter failed to initialize. Check the startup log for details.")
    }

    val syntaxError = Regex("<class\\s+'([^']+)'>:\\s+\\('([^']+)',\\s+\\('<string>',\\s*(\\d+),")

    val otherError = Regex("<class\\s+'([^']+)'>:(.*)")

    override fun executeFragment(inside: Box, execute: Execute, reply: (Message) -> Unit) {
        val originSession = thisSession.get()
        inMain {

            hackJepThreadContext()
            var withholdEndNotification = false
            configureOutputProxies(inside, reply, originSession)
            hasHadOutput = false
            deliverStdOut = {
                inside.print(formatOutput(it))
                hasHadOutput = true
            }
            deliverErr = {
                // Errors are HTML-escaped downstream by errorProxy, so only truncate here.
                inside.error(truncateOutput(it.trimEnd('\n')))
                hasHadOutput = true
            }
            deliverProgress = { deliverProgressText(inside, it) }
            var c = execute.code
//            for (n in 0 until execute.line) c = "\n" + c
            val resultLine = resultLineFor(execute.line, execute.code)
            val wm = ++inside.fragmentGeneration
            send(Message("is-running").payload(IsRunning(execute.uid, true, wm, "__fragment__")))
            try {

                val i = interpreter(inside)
                lprintln("debug,python", "--code is\n${c}\n--end code")
                i.set("_code", c.trimIndent())
                i.set("_local_storage_", inside._pythonLocals)
                thisBox = inside
                thisLineOffset = execute.line
//                i.exec("_ret = _exec_with_return2(_code, globals(), locals=_local_storage_, source_id='${inside.id}')")
                i.exec("_ret = _exec_with_return2(_code, globals(), locals=globals(), source_id='${inside.id}')")
                val ret = i.getValue("_ret")
                if (ret != null) {
                    i.exec("_isawait = inspect.isawaitable(_ret)")
                    val awaitable = i.getValue("_isawait") as Boolean
                    if (awaitable) {
                        if (!hasHadOutput) {
                            inside.output.proxy(
                                "[task launched]", false, resultLine
                            )
                            // Same reason as the bare-expression branch below: `withholdEndNotification`
                            // suppresses the is-running message but not the `finally`'s "ok".
                            hasHadOutput = true
                            withholdEndNotification = true
                            launch {
                                lprintln("info,python", "running notification for box has started $wm")
                                i.exec("_stillRunning = any_running('${inside.id}')")
                                var stillRunning = i.getValue("_stillRunning") as Boolean
                                send(
                                    Message("is-running").payload(
                                        IsRunning(
                                            execute.uid, stillRunning, wm, "__fragment__"
                                        )
                                    )
                                )

                                while (stillRunning) {
                                    i.exec("_stillRunning = any_running('${inside.id}')")
                                    stillRunning = i.getValue("_stillRunning") as Boolean
                                    delay(100)
                                }
                                send(Message("is-running").payload(IsRunning(execute.uid, false, wm, "__fragment__")))
                                lprintln("info,python", "running notification for box has ended $wm")
                            }
                        }

                    } else {
                        if (!hasHadOutput) {
                            inside.output.proxy(
                                formatOutput(ret.toString()), false, resultLine
                            )
                            // Claim the output slot. Without this the `finally` below still believes
                            // the cell produced nothing and writes "ok" over the value, at the same
                            // line — so a cell whose last line is a bare expression displayed "ok"
                            // rather than its result.
                            hasHadOutput = true
                        }
                    }
                } else if (!hasHadOutput) {
                    inside.output.proxy("ok", false, resultLine)
                    hasHadOutput = true
                }
            } catch (j: JepException) {
                j.printStackTrace()
                hasHadOutput = true
                val n = j.message!!
                val f = syntaxError.find(n)
                if (f != null) {
                    inside.error(f.groupValues[1] + ":" + f.groupValues[2], "code", f.groupValues[3].toInt())
                } else {
                    val f2 = otherError.find(n)

                    //j.stackTrace[0].lineNumber appears to always be 1 when using exec?? — disappointing
                    if (f2 != null) {
                        inside.error(
                            f2.groupValues[1] + ":" + f2.groupValues[2],
                            "code",
                            j.stackTrace[0].lineNumber + execute.line
                        )
                    } else {
                        inside.error(j.message!!, "code", -1)
                    }
                }
            } finally {
                if (!withholdEndNotification) send(
                    Message("is-running").payload(
                        IsRunning(
                            execute.uid,
                            false,
                            wm,
                            "__fragment__"
                        )
                    )
                )

                // just send an ok if we need to
                if (!hasHadOutput) {
                    dispatchExecutionOutput(
                        inside,
                        reply,
                        Output(
                            uid = inside.id,
                            line = resultLine,
                            kind = "ok",
                            html = "ok",
                            frame = Runloop.runLoop.frame.toInt()
                        ),
                        originSession = originSession
                    )
                }
            }
        }
    }

    private fun hackJepThreadContext() {

        // we have to, via reflection, set Jep
    }

    override fun executeAll(inside: Box, execute: Execute, reply: (Message) -> Unit) {
        executeFragment(inside, execute, reply)
    }

    override fun launchAll(
        inside: Box, execute: Execute, code: String, strand: String, reply: (Message) -> Unit
    ) {
        // for python this is the same as execute all,
        executeFragment(inside, execute, reply)
    }

    override fun stop(inside: Box) {
        val i = interpreter(inside)

        inMain {
            hackJepThreadContext()
            val code = "_ret = await cancel_tasks('${inside.id}', timeout=1.5)"
            i.set("_code", code)
            val locals = inside._pythonLocals
//            i.exec("_ret = _exec_with_return2(_code, globals(), locals=${locals}, source_id='${inside.id}')")
            i.exec("_ret = _exec_with_return2(_code, globals(), locals=globals(), source_id='${inside.id}')")
            val ret = i.getValue("_ret")
        }
    }

    override fun complete(
        inside: Box, completionRequest: CompletionRequest, reply: (Message) -> Unit
    ) {
        inMain {
            hackJepThreadContext()
            val line = completionRequest.line
            val col = completionRequest.column - 1

            try {
                val ii = interpreter(inside)
                ii.set("_interpreter_code", inside.code)
                val bootstrap = """import jedi 
_interpreter = jedi.Interpreter(_interpreter_code, [locals()])
_interpreter_output = _interpreter.complete($line, $col)
"""
                ii.exec(bootstrap)
                val res: ArrayList<PyObject> = ii.getValue("_interpreter_output") as ArrayList<PyObject>
                val metadata = res.map {
                    val complete = it.getAttr("complete") as String
                    val metadata = Metadata(
                        icon = it.getAttr("type") as String,
                        tail = it.getAttr("type") as String,
                        documentation = (it.getAttr("docstring") as PyCallable).call() as String,
                        text = complete,
                        displayText = it.getAttr("name_with_symbols") as String
                    )
                    metadata
                }

                lprintln("debug,python", "metadata: $metadata")
                reply(
                    Message("complete").payload(

                        Completion(
                            boundsStart = -1,
                            boundsEnd = -1,
                            lineStart = line,
                            columnsStart = col,
                            useLineColumns = true,
                            metadata = metadata
                        )
                    )
                )
            } catch (j: JepException) {
                j.printStackTrace()
            }
        }
    }

    override fun executeSymbol(inside: Box, executeSymbol: ExecuteSymbol, reply: (Message) -> Unit) {
        // For Python, construct a function call: symbolName()
        val functionCall = "${executeSymbol.symbol}()"

        // Create an Execute object for executeFragment
        val exec = Execute(
            uid = executeSymbol.uid,
            type = "fragment",
            line = executeSymbol.line,
            code = functionCall,
            singleLine = true
        )

        // Execute as a fragment
        executeFragment(inside, exec, reply)
    }

    override val languageName: String = "Python"

}

class PythonInterface {

    companion object {
        var deliverStdOut: (String) -> Unit = {}
        var deliverErr: (String) -> Unit = {}
        var deliverProgress: (String) -> Unit = {}

        /** Cap on the characters / lines a single output event may carry before it is truncated. */
        const val MAX_OUTPUT_CHARS = 40_000
        const val MAX_OUTPUT_LINES = 400

        /**
         * The inline style on an embedded page's iframe (plotly's `fig.show()`, bokeh, folium).
         *
         * **Both dimensions must stay in `px`.** The output area sizes its panel from the iframe's
         * own declared size and reads it with a `parseFloat`-based parser
         * (`src/commonMain/resources/outputareas2.js:278-287`); a percentage parses as zero, so it
         * falls back to measuring an iframe that is itself waiting to be sized, and every embed
         * ends up in a ~480px column. Held to that by
         * [afield.python.PythonOutputContractTest.theEmbedIframeDeclaresBothAxesInPixels].
         *
         * **The background is `transparent`, not `#fff`.** A white frame around a `plotly_dark`
         * figure is a bright slab in the middle of a dark box, and it is the frame showing through
         * rather than anything the figure drew: plotly paints its own `paper_bgcolor` and leaves the
         * rest of the page to whatever is underneath. Transparent lets the box's own background be
         * that. A document that genuinely needs a light backdrop still paints one — every library
         * that assumes white sets `body{background:#fff}` in the HTML it hands us — so this changes
         * the default rather than removing the option.
         */
        const val EMBED_IFRAME_STYLE =
            "width:960px;height:640px;border:none;background:transparent;border-radius:8px;"

        /**
         * The document line a fragment's result, `[task launched]`, or `ok` is attached to.
         *
         * [startLine] is the fragment's own offset in the box; the result belongs on the fragment's
         * **last** line. A fragment almost always ends in a newline, and `"a\nb\n".split("\n")`
         * yields a trailing `""` — counting that put the result one line past the end of the
         * document, where the output area drops the decoration and the cell looked as though it had
         * produced nothing at all.
         */
        @JvmStatic
        fun resultLineFor(startLine: Int, code: String): Int =
            startLine + code.trimEnd('\n').split("\n").count()

        /**
         * Truncate, escape, and (for multi-line text) wrap output for display in the editor.
         *
         * Called from both Kotlin (the last-expression repr, raw stdout lines) and the Python `print`
         * override, so all printed text funnels through one place. Multi-line text — a 2-D numpy array,
         * a DataFrame, a formatted dict — is wrapped in a monospace, whitespace-preserving `<pre>` so it
         * renders faithfully (bracket-balanced, column-aligned) instead of being flattened into `<br>`s.
         */
        @JvmStatic
        fun formatOutput(text: String): String {
            val truncated = truncateOutput(text.trimEnd('\n'))
            val escaped = escapeOutputHtml(truncated)
            return if (truncated.contains('\n')) {
                "<pre class=\"afield-python-output afield-appendable-output\">$escaped</pre>"
            } else {
                escaped
            }
        }

        /** Clip [text] to [MAX_OUTPUT_CHARS]/[MAX_OUTPUT_LINES], keeping the head and tail with a marker. */
        @JvmStatic
        fun truncateOutput(text: String): String {
            var t = text
            if (t.length > MAX_OUTPUT_CHARS) {
                val half = MAX_OUTPUT_CHARS / 2
                val removed = t.length - 2 * half
                t = t.substring(0, half) + "\n… [$removed characters truncated] …\n" + t.substring(t.length - half)
            }
            val lines = t.split('\n')
            if (lines.size > MAX_OUTPUT_LINES) {
                val head = lines.take(MAX_OUTPUT_LINES / 2)
                val tail = lines.takeLast(MAX_OUTPUT_LINES / 2)
                val removed = lines.size - head.size - tail.size
                t = (head + "… [$removed lines truncated] …" + tail).joinToString("\n")
            }
            return t
        }

        private fun escapeOutputHtml(text: String): String = buildString(text.length) {
            text.forEach {
                when (it) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    else -> append(it)
                }
            }
        }

        var inited = false

        var conf: JepConfig = JepConfig()

        @JvmField
        var thisBox : Box? = null

        @JvmField
        var thisLineOffset = 0

        @JvmField
        var hasHadOutput = false

        /** The resolved venv path (if any), exposed so startup code can configure sys.prefix etc. */
        var resolvedVenvPath: String? = null
        /** The resolved python executable path, used by startup code for sys.executable */
        var resolvedPythonExecutable: String? = null
        var pythonConfigAvailable: Boolean = false
            private set
        var pythonConfigMessage: String = "Python/JEP is not configured. Use /welcome Python settings to choose a venv or interpreter with `pip install jep`, then restart Field."
            private set
        private var resolvedPythonHome: String? = null
        private var resolvedJepLibrary: String? = null
        private var resolvedPythonSource: String = "none"

        private data class PythonConfigChoice(
            val kind: String,
            val path: String,
            val source: String,
        )

        private data class ResolvedPythonConfig(
            val source: String,
            val kind: String,
            val path: String,
            val pythonExecutable: String,
            val pythonHome: String,
            val jepLibrary: String,
            val sitePackages: String?,
            val venvPath: String?,
        )

        fun pythonConfigSummary(): String =
            listOfNotNull(
                "source=$resolvedPythonSource",
                resolvedPythonExecutable?.let { "python=$it" },
                resolvedPythonHome?.let { "home=$it" },
                resolvedJepLibrary?.let { "jep=$it" },
            ).joinToString(", ")

        fun currentPythonSettingsStatus(): PythonSettingsStatus {
            val persisted = readPersistedPythonChoice()
            val configPath = pythonSettingsFile().absolutePath

            // The live JVM binds JEP once at startup and can't rebind. If the persisted choice
            // differs from the interpreter currently bound (e.g. right after Save/Install JEP),
            // report the *saved* target with a "restart required" note instead of parroting the
            // stale live state — otherwise a working install looks like it failed.
            if (persisted != null) {
                val expectedExe = when (persisted.kind) {
                    "venv" -> pythonExecutableForVenv(File(persisted.path))?.path
                    else -> persisted.path
                }?.let { File(it).absolutePath }
                val liveExe = resolvedPythonExecutable?.let { File(it).absolutePath }
                if (expectedExe != null && expectedExe != liveExe) {
                    val resolved = runCatching { resolveChoice(persisted) }.getOrNull()
                    if (resolved != null) {
                        return PythonSettingsStatus(
                            configured = true,
                            source = "saved (restart required)",
                            kind = persisted.kind,
                            path = persisted.path,
                            pythonExecutable = resolved.pythonExecutable,
                            pythonHome = resolved.pythonHome,
                            jepLibrary = resolved.jepLibrary,
                            sitePackages = resolved.sitePackages,
                            configPath = configPath,
                            message = "Saved Python: ${persisted.path}. Restart Field for JEP to bind this interpreter.",
                            restartRequired = true,
                        )
                    }
                }
            }

            return PythonSettingsStatus(
                configured = pythonConfigAvailable,
                source = resolvedPythonSource,
                kind = persisted?.kind ?: "",
                path = persisted?.path.orEmpty(),
                pythonExecutable = resolvedPythonExecutable,
                pythonHome = resolvedPythonHome,
                jepLibrary = resolvedJepLibrary,
                sitePackages = resolvedVenvSitePackagesPath,
                configPath = configPath,
                message = pythonConfigMessage,
                restartRequired = false,
            )
        }

        fun markPythonUnavailable(message: String) {
            pythonConfigAvailable = false
            pythonConfigMessage = message
            resolvedPythonSource = "failed"
        }

        fun savePythonSettings(request: PythonSettingsRequest): PythonSettingsStatus {
            val kind = request.kind.trim().lowercase(Locale.US).ifBlank { "venv" }
            require(kind in setOf("venv", "executable")) { "Python kind must be `venv` or `executable`." }
            val path = request.path.trim()
            require(path.isNotBlank()) { "Python path is blank." }

            val choice = PythonConfigChoice(kind, expandPath(path), "welcome")
            val resolved = resolveChoice(choice)
                ?: throw IllegalArgumentException("Could not resolve Python/JEP from $path. Choose a venv or interpreter where `pip install jep` has been run.")

            val file = pythonSettingsFile()
            file.parentFile?.mkdirs()
            Properties().apply {
                setProperty("kind", kind)
                setProperty("path", choice.path)
            }.store(file.outputStream(), "Field Python/JEP settings")

            return PythonSettingsStatus(
                configured = true,
                source = "saved",
                kind = kind,
                path = choice.path,
                pythonExecutable = resolved.pythonExecutable,
                pythonHome = resolved.pythonHome,
                jepLibrary = resolved.jepLibrary,
                sitePackages = resolved.sitePackages,
                configPath = file.absolutePath,
                message = "Python settings saved. Restart Field for JEP to use this interpreter.",
                restartRequired = true,
            )
        }

        /**
         * Bootstrap `pip` (via `ensurepip`) and `pip install jep` into the chosen venv/interpreter,
         * then — if that leaves a working JEP — persist the choice so a restart binds it.
         *
         * This is the "effectively `<venv>/bin/python -m pip install ensurepip; ... install jep`"
         * action offered in /welcome, done correctly: pip is bootstrapped with the stdlib
         * `ensurepip` module rather than the (nonexistent) PyPI `ensurepip` package.
         */
        fun installJep(request: PythonSettingsRequest): PythonInstallResult {
            val kind = request.kind.trim().lowercase(Locale.US).ifBlank { "venv" }
            require(kind in setOf("venv", "executable")) { "Python kind must be `venv` or `executable`." }
            val rawPath = request.path.trim()
            require(rawPath.isNotBlank()) { "Python path is blank." }
            val path = expandPath(rawPath)

            val executable = when (kind) {
                "venv" -> pythonExecutableForVenv(File(path))
                    ?: throw IllegalArgumentException("No Python executable under $rawPath. Point at a venv directory created with `python -m venv`.")
                "executable" -> File(path).takeIf { it.exists() && it.canExecute() }
                    ?: throw IllegalArgumentException("$rawPath is not an executable Python interpreter.")
                else -> throw IllegalArgumentException("Python kind must be `venv` or `executable`.")
            }

            val log = StringBuilder()
            // jep is a C/JNI extension; its build needs JAVA_HOME to locate jni.h.
            val javaHome = System.getProperty("java.home")

            fun step(label: String, vararg args: String): Boolean {
                log.append("$ ").append(args.joinToString(" ")).append('\n')
                val (code, out) = runPythonCommand(javaHome, args.toList())
                if (out.isNotBlank()) log.append(out).append(if (out.endsWith("\n")) "" else "\n")
                log.append("[$label exit ").append(code).append("]\n\n")
                return code == 0
            }

            // 1. Make sure pip exists (no-op if it already does).
            step("ensurepip", executable.path, "-m", "ensurepip", "--upgrade")
            // 2. Install jep.
            val installed = step("pip install jep", executable.path, "-m", "pip", "install", "--upgrade", "jep")
            if (!installed) {
                return PythonInstallResult(
                    success = false,
                    message = "`pip install jep` failed. See the log below (a C compiler and JDK headers are required to build jep).",
                    log = log.toString(),
                )
            }

            // 3. Confirm the native library is now present, and persist the choice on success.
            if (resolveChoice(PythonConfigChoice(kind, path, "welcome install")) == null) {
                return PythonInstallResult(
                    success = false,
                    message = "jep installed but its native library could not be located. See the log below.",
                    log = log.toString(),
                )
            }

            val status = savePythonSettings(PythonSettingsRequest(kind = kind, path = path))
            log.append("Saved Python settings to ${status.configPath}\n")
            return PythonInstallResult(
                success = true,
                message = "jep installed. Restart Field for JEP to bind the new native library.",
                log = log.toString(),
                status = status,
            )
        }

        private fun runPythonCommand(javaHome: String?, args: List<String>): Pair<Int, String> {
            val process = ProcessBuilder(args)
                .redirectErrorStream(true)
                .apply {
                    if (javaHome != null) environment()["JAVA_HOME"] = javaHome
                }
                .start()
            val output = process.inputStream.bufferedReader().readText()
            // jep builds a C extension from source; allow generous time.
            if (!process.waitFor(6, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                return -1 to (output + "\n[timed out after 6 minutes]")
            }
            return process.exitValue() to output
        }

        // Once, at class-init: JEP's library path and init params are process-global and have to be set
        // before the first interpreter exists.
        init {
            configurePythonFromBestCandidate()
        }

        /**
         * Points JEP at the first Python in [pythonConfigChoices] order that actually has a `libjep`
         * inside it.
         *
         * For a venv root (`/path/to/project/.venv`) that means locating the `libjep` native library in
         * the venv's site-packages, finding the interpreter beside it, and configuring
         * [MainInterpreter] accordingly. The two explicit overrides, tried first:
         * - system property `-Dafield.python.venv=/path/to/.venv`
         * - env var `FIELD_PYTHON_VENV=/path/to/.venv`
         *
         * Never throws. Field has to start without Python, so a total failure only leaves
         * `pythonConfigAvailable` false and a `pythonConfigMessage` naming what was tried.
         */
        private fun configurePythonFromBestCandidate() {
            val attempts = pythonConfigChoices()
            val failures = mutableListOf<String>()
            for (choice in attempts) {
                val resolved = runCatching { resolveChoice(choice) }
                    .onFailure { failures += "${choice.source}: ${it.message ?: it.javaClass.simpleName}" }
                    .getOrNull()
                if (resolved != null) {
                    applyResolvedPython(resolved)
                    pythonConfigAvailable = true
                    pythonConfigMessage = "Python/JEP configured from ${resolved.source}: ${resolved.pythonExecutable}"
                    lprintln("info,python", pythonConfigMessage)
                    return
                }
                failures += "${choice.source}: no JEP library found for ${choice.path}"
            }
            pythonConfigAvailable = false
            pythonConfigMessage = buildString {
                append("Python/JEP is not configured. Use /welcome Python settings to choose a venv or interpreter with `pip install jep`, then restart Field.")
                if (failures.isNotEmpty()) append(" Tried: ").append(failures.take(4).joinToString("; "))
            }
            resolvedPythonSource = "none"
            lprintln("info,python", pythonConfigMessage)
        }

        private fun applyResolvedPython(resolved: ResolvedPythonConfig) {
            @Suppress("DEPRECATION")
            MainInterpreter.setInitParams(PyConfig().setPythonHome(resolved.pythonHome))
            MainInterpreter.setJepLibraryPath(resolved.jepLibrary)
            resolved.sitePackages?.let {
                conf.addIncludePaths(it)
                resolvedVenvSitePackagesPath = it
            }
            resolvedVenvPath = resolved.venvPath
            resolvedPythonExecutable = resolved.pythonExecutable
            resolvedPythonHome = resolved.pythonHome
            resolvedJepLibrary = resolved.jepLibrary
            resolvedPythonSource = resolved.source
        }

        private fun pythonConfigChoices(): List<PythonConfigChoice> {
            val choices = mutableListOf<PythonConfigChoice>()
            fun add(kind: String, path: String?, source: String) {
                val normalized = path?.trim()?.takeIf { it.isNotBlank() } ?: return
                choices += PythonConfigChoice(kind, expandPath(normalized), source)
            }
            add("venv", System.getProperty("afield.python.venv"), "system property afield.python.venv")
            add("executable", System.getProperty("afield.python.executable"), "system property afield.python.executable")
            add("venv", System.getenv("FIELD_PYTHON_VENV"), "env FIELD_PYTHON_VENV")
            add("executable", System.getenv("FIELD_PYTHON_EXECUTABLE"), "env FIELD_PYTHON_EXECUTABLE")
            readPersistedPythonChoice()?.let { choices += it.copy(source = "welcome settings") }
            add("venv", System.getenv("VIRTUAL_ENV"), "env VIRTUAL_ENV")
            add("venv", System.getenv("CONDA_PREFIX"), "env CONDA_PREFIX")
            add("executable", "python3", "PATH python3")
            add("executable", "python", "PATH python")
            return choices.distinctBy { it.kind to it.path }
        }

        private fun resolveChoice(choice: PythonConfigChoice): ResolvedPythonConfig? {
            val python = when (choice.kind) {
                "venv" -> pythonExecutableForVenv(File(choice.path)) ?: return null
                "executable" -> File(choice.path)
                else -> return null
            }
            return inspectPython(python.path, choice)
        }

        private fun pythonExecutableForVenv(venv: File): File? {
            if (!venv.isDirectory) return null
            val isWindows = System.getProperty("os.name").lowercase(Locale.US).contains("windows")
            val bin = File(venv, if (isWindows) "Scripts" else "bin")
            val names = if (isWindows) listOf("python.exe", "python3.exe") else listOf("python3", "python")
            return names.asSequence().map { File(bin, it) }.firstOrNull { it.exists() && it.canExecute() }
        }

        private fun inspectPython(executable: String, choice: PythonConfigChoice): ResolvedPythonConfig? {
            val script = """
                import glob, os, site, sys, sysconfig
                def emit(k, v):
                    if v:
                        print(k + "=" + str(v))
                prefixes = []
                for p in [getattr(sys, "base_prefix", None), getattr(sys, "prefix", None), sysconfig.get_config_var("base"), sysconfig.get_config_var("prefix")]:
                    if p and p not in prefixes:
                        prefixes.append(p)
                site_paths = []
                for fn in [getattr(site, "getsitepackages", None)]:
                    if fn:
                        try:
                            site_paths.extend(fn())
                        except Exception:
                            pass
                try:
                    site_paths.append(site.getusersitepackages())
                except Exception:
                    pass
                for p in list(sys.path):
                    if p and ("site-packages" in p or "dist-packages" in p):
                        site_paths.append(p)
                libs = []
                for sp in site_paths:
                    for pat in ["libjep*", "jep*.dll"]:
                        libs.extend(glob.glob(os.path.join(sp, "jep", pat)))
                libs = [p for p in libs if os.path.isfile(p)]
                jep_site = os.path.dirname(os.path.dirname(libs[0])) if libs else ""
                emit("executable", sys.executable)
                emit("home", prefixes[0] if prefixes else "")
                emit("prefix", sys.prefix)
                emit("basePrefix", getattr(sys, "base_prefix", ""))
                emit("sitePackages", jep_site or (site_paths[0] if site_paths else ""))
                emit("jepLibrary", libs[0] if libs else "")
            """.trimIndent()
            val process = ProcessBuilder(executable, "-c", script)
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(6, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            if (process.exitValue() != 0) return null
            val values = process.inputStream.bufferedReader().readLines()
                .mapNotNull { line ->
                    val idx = line.indexOf('=')
                    if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
                }
                .toMap()
            val jep = values["jepLibrary"]?.takeIf { it.isNotBlank() } ?: return null
            val home = values["home"]?.takeIf { it.isNotBlank() } ?: return null
            val py = values["executable"]?.takeIf { it.isNotBlank() } ?: executable
            val prefix = values["prefix"]?.takeIf { it.isNotBlank() }
            val basePrefix = values["basePrefix"]?.takeIf { it.isNotBlank() }
            val venvPath = when {
                choice.kind == "venv" -> File(choice.path).absolutePath
                prefix != null && basePrefix != null && prefix != basePrefix -> prefix
                else -> null
            }
            return ResolvedPythonConfig(
                source = choice.source,
                kind = choice.kind,
                path = choice.path,
                pythonExecutable = py,
                pythonHome = home,
                jepLibrary = jep,
                sitePackages = values["sitePackages"]?.takeIf { it.isNotBlank() },
                venvPath = venvPath,
            )
        }

        private fun readPersistedPythonChoice(): PythonConfigChoice? {
            val file = pythonSettingsFile()
            if (!file.exists()) return null
            return runCatching {
                val props = Properties().apply { file.inputStream().use(::load) }
                val kind = props.getProperty("kind")?.trim()?.lowercase(Locale.US) ?: "venv"
                val path = props.getProperty("path")?.trim()?.takeIf { it.isNotBlank() } ?: return null
                PythonConfigChoice(kind, expandPath(path), "welcome settings")
            }.getOrNull()
        }

        private fun pythonSettingsFile(): File =
            File(fieldPythonConfigDir(), "python.properties")

        private fun fieldPythonConfigDir(): File {
            val osName = System.getProperty("os.name").lowercase(Locale.US)
            return if (osName.contains("windows")) {
                val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                    ?: File(System.getProperty("user.home"), "AppData/Roaming").absolutePath
                File(appData, "AField")
            } else {
                File(System.getProperty("user.home"), ".afield")
            }
        }

        private fun expandPath(path: String): String {
            if (path == "~") return System.getProperty("user.home")
            if (path.startsWith("~/") || path.startsWith("~\\")) {
                return File(System.getProperty("user.home"), path.substring(2)).path
            }
            return path
        }

        @JvmStatic
        fun floatArray(vararg dims : Int) : DirectNDArray<FloatBuffer>{
            val o = ByteBuffer.allocateDirect(4*dims.reduce { a, b -> a*b }).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
            return DirectNDArray(o, *dims)
        }

        @JvmStatic
        fun integrateEventLoop(a: () -> Unit) {
            inMain {
                try {
                    while (true) {
                        a()
                        yield()
                    }
                } catch (ex: Throwable) {
                    lprintln("error,python", "exception ${ex.classNameAndMessage} thrown inside Python event loop; the loop is now dead, restart required")
                    lprintln("error,python", ex.stackTraceToString())
                }
            }
        }
    }


    fun helloWorld() {
        SharedInterpreter().use { interp ->
            interp.exec("from java.lang import System")
            interp.exec("s = 'Hello World'")
            interp.exec("System.out.println(s)")
            interp.exec("print(s)")
            interp.exec("print(s[1:-1])")
        }
    }
}
