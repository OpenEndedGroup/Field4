package afield.python

import afield.*
import afield.python.PythonInterface.Companion.conf
import afield.python.PythonInterface.Companion.deliverErr
import afield.python.PythonInterface.Companion.deliverStdOut
import afield.python.PythonInterface.Companion.hasHadOutput
import afield.python.PythonInterface.Companion.inited
import afield.python.PythonInterface.Companion.thisBox
import afield.python.PythonInterface.Companion.thisLineOffset
import afield.runtime.TopologyWatcher
import afield.runtime.applyRuntimeToggle
import afield.stdlib.Delegation
import jep.*
import jep.python.PyCallable
import jep.python.PyObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.jetbrains.kotlin.util.classNameAndMessage
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.FloatBuffer

var Box.isPythonRoot by Delegation<Boolean?>()
var Box._pythonLocals by Delegation<LinkedHashMap<*,*>>(defaultValue = { LinkedHashMap<Any, Any>() })


@RunsAt(early)
fun makeInterpreter() {
    try {
        PythonExecution().interpreter(null)
    } catch (e: Throwable) {
        println(" - jep failed to initialize, this more of a warning than an error, unless you are planning to use Python support")
        println(e.message)
    }
}

@RunsAt(early)
fun installPython() {
    Box.ioPersistentProperties.add("isPythonRoot")
}

@RunsAt(plugins)
fun install() {
    TopologyWatcher("python") { pythonTopologyNeedsUpdating(it) }.start()
}


fun pythonTopologyNeedsUpdating(values: Collection<Box>) {
    applyRuntimeToggle(
        boxes = values,
        isRoot = { it.isPythonRoot },
        runtimeClass = PythonExecution::class,
        newExecution = { _ -> PythonExecution() },
        language = "Python",
        customization = """this.style.border = "1px dashed #40008B"; this.style.margin = "-5px" """,
        notifyCategory = "python"
    )
}


var interpretersForBoxes = mutableMapOf<Box, SharedInterpreter>()
lateinit var globalInterpreter: SharedInterpreter

@Suppress("UNCHECKED_CAST")
class PythonExecution : BoxExecution {

    fun interpreter(b: Box? = null): SharedInterpreter {
        if (!inited) {
            conf.redirectStdout(object : OutputStream() {
                var line = ""
                override fun write(b: Int) {
                    line += Char(b).toString()
                    if (line.endsWith('\n')) {
                        print(line)
                        deliverStdOut(line)
                        line = ""
                    }
                }
            }).redirectStdErr(object : OutputStream() {
                var line = ""
                override fun write(b: Int) {
                    line += Char(b).toString()
                    if (line.endsWith('\n')) {
                        print("(error) $line")
                        deliverErr(line)
                        line = ""
                    }
                }
            })
            SharedInterpreter.setConfig(conf);
            inited = true
            globalInterpreter = SharedInterpreter()


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

sys.executable = "/opt/homebrew/Caskroom/miniconda/base/bin/python"
print(" python interpreter is starting up ")

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

print(" python interpreter has finished starting up ")

            """.trimIndent()

            println(" -- calling startup text for python interpreter --")
            globalInterpreter.exec(startupText)
            println(" -- finished calling startup text for python interpreter --")
        }
        return globalInterpreter
    }

    val syntaxError = Regex("<class\\s+'([^']+)'>:\\s+\\('([^']+)',\\s+\\('<string>',\\s*(\\d+),")

    val otherError = Regex("<class\\s+'([^']+)'>:(.*)")

    override fun executeFragment(inside: Box, execute: Execute, reply: (Message) -> Unit) {
        inMain {
            var withholdEndNotification = false
            configureOutputProxies(inside, reply)
            hasHadOutput = false
            deliverStdOut = {
                inside.print(it)
                hasHadOutput = true
            }
            deliverErr = {
                inside.error(it)
                hasHadOutput = true
            }
            var c = execute.code
//            for (n in 0 until execute.line) c = "\n" + c
            val wm = ++inside.fragmentGeneration
            send(Message("is-running").payload(IsRunning(execute.uid, true, wm, "__fragment__")))
            try {

                val i = interpreter(inside)
                println("--code is\n${c}\n--end code")
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
                                "[task launched]", false, execute.line + execute.code.split("\n").count()
                            )
                            withholdEndNotification = true
                            launch {
                                println(" -- running notification for box has started  ${wm}")
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
                                println(" -- running notification for box has ended ${wm}")
                            }
                        }

                    } else {
                        if (!hasHadOutput) {
                            inside.output.proxy(
                                ret.toString(), false, execute.line + execute.code.split("\n").count()
                            )
                        }
                    }
                } else if (!hasHadOutput) {
                    inside.output.proxy("ok", false, execute.line + execute.code.split("\n").count())
                }
            } catch (j: JepException) {
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
                if (!hasHadOutput) Message("output").payload(
                    Output(
                        line = execute.line + execute.code.split("\n").count(),
                        kind = "ok",
                        html = "ok",
                        frame = Runloop.runLoop.frame.toInt()
                    )
                )
            }
        }
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

                println("metadata: $metadata")
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

        var inited = false

        var conf: JepConfig = JepConfig()

        @JvmField
        var thisBox : Box? = null

        @JvmField
        var thisLineOffset = 0

        @JvmField
        var hasHadOutput = false

        init {
            println(" -- set python config")
            //TODO!! — needs to be configurable, and configurable really early I suppose
            MainInterpreter.setInitParams(PyConfig().setPythonHome("/opt/homebrew/Caskroom/miniconda/base/"))
            MainInterpreter.setJepLibraryPath("/opt/homebrew/Caskroom/miniconda/base/lib/python3.11/site-packages/jep/libjep.jnilib")
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
                    println("error, exception ${ex.classNameAndMessage} thrown inside Python event loop, the python event loop is now dead, and you'll need to restart")
                    ex.printStackTrace()
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
