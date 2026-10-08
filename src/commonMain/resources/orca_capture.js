'use strict'

// What ORCA plays, as Field records it. Shared by orca_field.js, in the page beside ORCA's own
// client, and by OrcaHeadless, which runs ORCA's operators in the JVM with no page at all.
//
// It wraps the five IO objects ORCA's operators write to (midi, mono, cc, udp, osc) so each message
// is also handed to `record`, and holds the variables a Field box sets (see Orca.variable).

/* exported fieldOrcaCapture, fieldOrcaRectangle, fieldOrcaEval */

function fieldOrcaCapture (client, record) {
  const seen = new WeakSet()

  function wrap (target, name, before) {
    const original = target[name]
    target[name] = function (...args) {
      before(...args)
      return original.apply(this, args)
    }
  }

  function note (mono) {
    return (channel, octave, glyph, velocity, length) => {
      const t = client.io.midi.transpose(glyph, octave)
      record({ kind: mono ? 'mono' : 'note', channel, octave, note: glyph, midi: t ? t.id : -1, velocity, length })
    }
  }

  wrap(client.io.midi, 'push', note(false))
  wrap(client.io.mono, 'push', note(true))
  wrap(client.io.udp, 'push', (message) => record({ kind: 'udp', message: `${message}` }))
  wrap(client.io.osc, 'push', (path, message) => record({
    kind: 'osc', path, message, args: `${message}`.split('').map((g) => client.orca.valueOf(g))
  }))
  // `!` and `?` push onto cc.stack directly, and a forced trigger runs the stack twice in one frame.
  wrap(client.io.cc, 'run', () => {
    for (const message of client.io.cc.stack) {
      if (seen.has(message)) continue
      seen.add(message)
      record(Object.assign({ kind: message.type }, message))
    }
  })

  // Variables the box holds: ORCA clears its variables at the top of every frame, and these go
  // straight back in, so `V` reads them as if a `V` operator on the grid had written them.
  const held = {}
  const release = client.orca.release
  client.orca.release = function () { release.apply(this, arguments); Object.assign(this.variables, held) }

  return {
    hold (name, value) {
      if (value == null) delete held[name]; else held[name] = value
      Object.assign(client.orca.variables, held)
    }
  }
}

// A box's own JavaScript, run at global scope beside ORCA (so `client`, `library` and `fieldOrca`
// are all in reach): read anything, patch anything. A promise is awaited. When the command has an
// id the box is waiting, and the value, as JSON, or the error goes into `results` for the next report.
function fieldOrcaEval (command, results) {
  Promise.resolve()
    .then(() => (0, eval)(`${command.js}`))
    .then((value) => (value === undefined ? null : JSON.parse(JSON.stringify(value))))
    .then((value) => { if (command.id) results.push({ id: command.id, value }) },
      (error) => {
        const message = `${(error && error.stack) || error}`
        if (command.id) results.push({ id: command.id, error: message })
        else console.warn('Field', 'ORCA js failed', message)
      })
}

// ORCA reads every row at the first row's width, so pad ragged rows out with empty cells.
function fieldOrcaRectangle (text) {
  const rows = `${text}`.replace(/^\s*\n|\s+$/g, '').split(/\r?\n/)
  const width = Math.max(...rows.map((row) => row.length))
  return rows.map((row) => row.padEnd(width, '.')).join('\n')
}
