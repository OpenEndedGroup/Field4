'use strict'

// Field's half of an embedded ORCA (afield/four/synth/Orca.kt is the other half).
//
// ORCA runs unmodified. orca_capture.js records every message its operators emit; after each frame
// this ships that frame's messages, and the grid if it changed, to the box through its
// output-request binding. The reply carries whatever the box asked for since (cells, writes, whole
// grids, variables, JavaScript to run, commander strings, play/stop), which is applied straight
// away. WebMIDI output
// still works as it does in upstream ORCA, if the browser has a device.
//
// window.fieldOrca is the same surface for anything evaluating JavaScript in this frame
// (Field's live-iframe bridge, AI4's iframe tools, the devtools console).

/* global performance, fieldOrcaCapture, fieldOrcaRectangle, fieldOrcaEval */

function installFieldOrca (client) {
  const params = new URLSearchParams(location.search)
  const binding = params.get('binding')
  const status = document.getElementById('field-orca-status')
  const events = []
  const recent = []
  const results = [] // answers to the box's eval commands, sent with the next report
  // This page among any others showing the same output (another tab, AI4's capture browser).
  const page = Math.random().toString(36).slice(2)
  let requests = null
  let inFlight = false
  let dead = false
  let edited = false
  let lastGrid = null
  let sent = 0

  const capture = fieldOrcaCapture(client, (event) => {
    event.frame = client.orca.f
    event.at = performance.now()
    if (events.length < 2048) events.push(event)
    recent.push(event)
    if (recent.length > 64) recent.shift()
  })

  function after (target, name, then) {
    const original = target[name]
    target[name] = function () { const value = original.apply(this, arguments); then(); return value }
  }
  // Every keyboard edit, paste, undo and redo goes through history; operators writing outputs do not.
  after(client.history, 'record', () => { edited = true })
  after(client.history, 'undo', () => { edited = true })
  after(client.history, 'redo', () => { edited = true })
  after(client, 'run', () => flush())
  // Resizing the output re-lays the grid at a new width, but this frame's cell locks stay indexed by
  // the old one, so until the next frame ORCA draws stray dots and unlocked comments. Carry them over.
  const crop = client.crop
  client.crop = function () {
    const orca = client.orca, w = orca.w, locked = []
    orca.locks.forEach((on, i) => { if (on) locked.push([i % w, Math.floor(i / w)]) })
    const value = crop.apply(this, arguments)
    orca.locks = new Array(orca.w * orca.h)
    for (const [x, y] of locked) orca.lock(x, y)
    return value
  }

  function grid () { return `${client.orca}` }

  function apply (commands) {
    for (const c of commands || []) {
      try {
        switch (c.op) {
          case 'load': client.whenOpen(null, fieldOrcaRectangle(c.grid || '.')); break
          case 'write':
            client.orca.writeBlock(c.x, c.y, c.text)
            client.history.record(client.orca.s)
            break
          // One cell, kept out of ORCA's undo history: a box may set cells every frame.
          case 'set': client.orca.write(c.x, c.y, c.glyph); break
          case 'variable': capture.hold(c.name, c.value); break
          case 'eval': fieldOrcaEval(c, results); break
          case 'command': client.commander.trigger(c.text, null, false); break
          case 'play': client.clock.play(); break
          case 'stop': client.clock.stop(); client.io.silence(); break
          case 'bpm': client.clock.setSpeed(c.value, c.value, true); break
          case 'frame': client.clock.setFrame(c.value); break
          case 'guide': client.toggleGuide(!!c.on); break
          default: console.warn('Field', 'Unknown ORCA command', c)
        }
      } catch (error) { console.warn('Field', 'ORCA command failed', c, error) }
    }
    if (commands && commands.length) client.update()
  }

  function disconnect (reason) {
    dead = true
    client.clock.stop()
    client.io.silence()
    status.textContent = `${reason}: run the box again`
    status.style.display = 'block'
  }

  function flush () {
    if (!requests || inFlight || dead) return
    const s = grid()
    const payload = {
      page,
      frame: client.orca.f,
      bpm: client.clock.speed.value,
      paused: client.clock.isPaused,
      t: performance.now(),
      events: events.splice(0),
      cursor: { x: client.cursor.x, y: client.cursor.y }
    }
    if (sent === 0) { payload.hello = true; payload.operators = client.docs() }
    if (s !== lastGrid) { payload.grid = s; payload.w = client.orca.w; payload.h = client.orca.h }
    if (edited) payload.edited = true
    if (results.length) payload.results = results.splice(0)
    const variables = client.orca.variables
    if (variables && Object.keys(variables).length) payload.variables = variables
    inFlight = true
    edited = false
    lastGrid = s
    sent++
    requests.request(payload).then((reply) => {
      status.style.display = 'none'
      apply(reply && reply.commands)
    }, (error) => {
      const message = `${error && error.message}`
      if (/unavailable|no longer live|binding closed|read-only/i.test(message)) return disconnect('Disconnected from its Field box')
      // Nothing is replayed after a lost request; the next one resends the grid and carries on.
      lastGrid = null
      status.textContent = message
      status.style.display = 'block'
    }).finally(() => { inFlight = false })
  }

  window.fieldOrca = {
    client,
    grid,
    recent: () => recent.slice(),
    write: (x, y, text) => apply([{ op: 'write', x, y, text }]),
    load: (text) => apply([{ op: 'load', grid: text }]),
    command: (text) => apply([{ op: 'command', text }]),
    operators: () => client.docs(),
    get connected () { return !!requests && !dead }
  }

  // Opened on its own (/orca.html), ORCA simply runs.
  if (!binding) return
  import('/output_requests.js').then(({ outputRequests }) => {
    requests = outputRequests(binding)
    flush()
    // Frames flush themselves; this carries edits and the box's commands while the clock is paused.
    setInterval(() => { if (client.clock.isPaused) flush() }, 150)
  })
}
