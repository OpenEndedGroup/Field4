// Loads an FM-1 simulator module for FM1Sim (Field, AGPL-3.0-or-later).
//
// The shipped firmware imports nothing. A firmware built by FM1Hooks.firmware() imports field.track,
// which it calls with one track's block of samples after the track's voice renders (only for tracks
// whose bit is set with sim_field_hooks). This glue answers that call by copying the block into the
// track's hook module, a separate WebAssembly instance with its own memory, running it, and copying
// the block back. A hook that traps is dropped and its error kept for FM1Sim.hookError; the firmware
// itself never sees the failure.
(function (bytes) {
  var module = new WebAssembly.Module(new Uint8Array(Java.from(bytes)));
  var hookable = WebAssembly.Module.imports(module).some(function (i) { return i.module === "field"; });
  var hooks = [], errors = [], firmware = null;
  var imports = {};
  if (hookable) {
    imports.field = {
      track: function (t, left, right, n, clock) {
        var h = hooks[t];
        if (!h) return;
        try {
          var fm = firmware.memory.buffer;
          var hb = new Int32Array(h.exports.memory.buffer, h.buf, 2 * n);
          hb.set(new Int32Array(fm, left, n), 0);
          if (right) hb.set(new Int32Array(fm, right, n), n);
          h.exports.track(n, t, clock, right ? 1 : 0);
          new Int32Array(fm, left, n).set(hb.subarray(0, n));
          if (right) new Int32Array(fm, right, n).set(hb.subarray(n, 2 * n));
        } catch (e) {
          hooks[t] = null;
          errors[t] = String(e);
        }
      }
    };
  }
  firmware = new WebAssembly.Instance(module, imports).exports;
  return {
    exports: firmware,
    hookable: hookable,
    setHook: function (t, hookBytes) {
      var m = new WebAssembly.Module(new Uint8Array(Java.from(hookBytes)));
      var e = new WebAssembly.Instance(m, {}).exports;
      if (e._initialize) e._initialize();
      hooks[t] = { exports: e, buf: e.field_buf() };
      errors[t] = null;
    },
    clearHook: function (t) { hooks[t] = null; errors[t] = null; },
    error: function (t) { return errors[t] || null; },
    evalHook: function (t, x, y, z, w) {
      var h = hooks[t];
      if (!h) return null;
      var p = h.exports.eval(x, y, z, w);
      var f = new Float32Array(h.exports.memory.buffer, p, 4);
      return [f[0], f[1], f[2], f[3]];
    }
  };
})(__fm1Bytes)
