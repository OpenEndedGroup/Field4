/*
 * Field's one time-axis viewer. Plotly draws the lanes; this file owns the view window, the data
 * sources and the shared time bus. Four sources feed one lane model:
 *   range     — functions evaluated per visible window by /signal-viewer/{id} (SignalViewer)
 *   embedded  — a static SignalWindow in the page (Fn.plot); optionally resampled live when zoomed
 *   audio     — min/max envelope from a waveform URL or in-browser decode, with an <audio> transport
 *   analysis  — a Field Audio analysis preview (beats / pitch / notes), embedded or fetched once
 * The playhead and cursors are DOM overlay lines, never a Plotly relayout per frame.
 * Kotlin side: afield.stdlib.TimeViewerShell. Design notes: dev_docs/SIGNAL_VIEWER.md.
 */
(() => {
  const root = document.currentScript.closest('.field-signal-viewer');
  if (!root || root.dataset.ready) return;
  root.dataset.ready = 'true'; root.dataset.renderer = 'plotly';
  const config = JSON.parse(root.querySelector('.fsv-config').textContent);
  const $ = selector => root.querySelector(selector);
  const stage = $('.fsv-stage'), status = $('.fsv-status'), range = $('.fsv-range'), readout = $('.fsv-readout');
  const title = $('.fsv-title'), audio = $('audio'), busy = $('.fsv-busy');
  title.textContent = config.label || ''; title.title = config.label || '';
  const source = config.source || 'range', interactive = config.interactive !== false;
  const xUnit = config.xUnit ?? 's', colors = config.colors || {};
  const plot = document.createElement('div'); plot.className = 'fsv-plot'; plot.tabIndex = interactive ? 0 : -1;
  plot.setAttribute('aria-label', (config.label || 'Signal') + '. Wheel to zoom, drag to pan, Home to show all.');
  const overlay = document.createElement('div'); overlay.className = 'fsv-overlay';
  const cursorLine = document.createElement('div'); cursorLine.className = 'fsv-line fsv-cursor';
  const playLine = document.createElement('div'); playLine.className = 'fsv-line fsv-playhead';
  overlay.append(cursorLine, playLine); stage.replaceChildren(plot, overlay, ...(busy ? [busy] : []));
  const nav = $('.fsv-navigation'), navShape = nav?.querySelector('.fsv-overview'), navWindow = nav?.querySelector('.fsv-window');
  const Plot = window.Plotly || window.parent?.Plotly;
  if (!Plot) { status.textContent = 'Field’s Plotly library is not loaded'; root.setAttribute('aria-busy', 'false'); return; }

  // ---- view state ------------------------------------------------------------------------
  const bus = (() => { try { return window.parent && window.parent !== window ? window.parent : window; } catch (_) { return window; } })();
  const group = config.syncGroup || '', offset = Number(config.syncOffset) || 0, instance = Math.random().toString(36).slice(2);
  const views = bus.__fieldSignalViews || (bus.__fieldSignalViews = new Map());
  const saved = config.viewKey && views.get(config.viewKey);
  let domainStart = config.start ?? 0, domainEnd = config.end ?? 0, total = domainEnd - domainStart;
  const minSpanOf = () => source === 'audio' ? Math.min(total, Math.max(1e-3, total / 1e5))
    : source === 'analysis' ? Math.min(total, .1)
    : Math.min(total, Math.max(total / 1e9, Number.EPSILON * Math.max(1, Math.abs(domainStart), Math.abs(domainEnd)) * 16));
  let start = saved?.start ?? config.initialStart ?? domainStart, span = saved?.span ?? config.initialDuration ?? total;
  let model = null, embedded = null, liveExpired = false, overviewModel = null, loadedOnce = false;
  let timer = 0, request = null, generation = 0, disposed = false, applying = 0, initialized = false;
  let rendering = Promise.resolve(), yRanges = {}, pointerTime = null, external = null, externalKind = 'hover', pinned = null;
  const listeners = new AbortController(), clamp = (v, a, b) => Math.max(a, Math.min(b, v));
  const on = (target, name, fn, options = {}) => target && target.addEventListener(name, fn, {...options, signal: listeners.signal});
  const publish = (name, detail) => { if (group) bus.dispatchEvent(new bus.CustomEvent(name, {detail: {group, source: instance, ...detail}})); };
  const num = v => {
    if (!Number.isFinite(v)) return '–';
    const a = Math.abs(v);
    if (a !== 0 && (a >= 1e5 || a < 1e-3)) return v.toExponential(2);
    return String(Number(v.toPrecision(a >= 100 ? 6 : 4)));
  };
  const clock = t => { const m = Math.floor(t / 60), s = t - m * 60; return m + ':' + s.toFixed(s < 10 ? 2 : 1).padStart(s < 10 ? 5 : 4, '0'); };
  const fmtX = v => !Number.isFinite(v) ? '–' : xUnit === 'clock' ? clock(v) : num(v) + (xUnit ? ' ' + xUnit : '');
  const esc = s => String(s).replaceAll('&', '&amp;').replaceAll('<', '&lt;');
  const setBusy = (flag, text) => {
    root.classList.toggle('fsv-loading', flag); if (busy) { busy.setAttribute('aria-hidden', String(!flag)); if (text) busy.lastChild.textContent = text; }
    // aria-busy starts true and clears only after a completed load: "not started" and "done" must differ.
    root.setAttribute('aria-busy', String(flag || !loadedOnce));
  };

  const normalize = () => {
    if (!(total > 0)) { range.textContent = 'loading duration…'; return; }
    span = clamp(span || total, minSpanOf(), total); start = clamp(start, domainStart, domainEnd - span);
    root.dataset.start = String(start); root.dataset.end = String(start + span);
    range.textContent = fmtX(start) + ' — ' + fmtX(start + span) + '  /  ' + fmtX(total);
    if (navWindow) {
      navWindow.style.left = ((start - domainStart) / total * 100) + '%';
      navWindow.style.width = Math.max(.6, span / total * 100) + '%';
      nav.classList.toggle('fsv-whole', span >= total);
    }
    if (config.viewKey) { views.delete(config.viewKey); views.set(config.viewKey, {start, span}); while (views.size > 64) views.delete(views.keys().next().value); }
    placeLines();
  };

  // ---- lane model -------------------------------------------------------------------------
  // lane: {name, minimum, maximum, weight, yZoom, ticks:{vals,text}, traces:[plotly], probes:[{label,unit,x,y | edges,low,high}]}
  const uniformX = (s, e, n, edges) => Array.from({length: edges ? n + 1 : n}, (_, i) => s + (e - s) * i / (edges ? n : Math.max(1, n - 1)));
  const fromWindow = (w, lanesConfig) => ({kind: 'window', mode: w.mode, spacing: w.sampleSpacing, start: w.start, end: w.end,
    lanes: w.lanes.map((lane, index) => {
      const cfg = lanesConfig?.[index] || {}, traces = [], probes = [];
      const primary = cfg.color || colors.primary || '#eeeeee';
      const addTrace = (v, previous) => {
        const n = v.low.length, color = previous ? '#8a8a8a' : primary, name = lane.name + (previous ? ' · ' + (config.comparisonLabel || 'previous') : '');
        if (w.mode === 'waveform') {
          const x = uniformX(w.start, w.end, n, false);
          // Dots mark samples only at extreme zoom (more than ~6 px per sample).
          const dots = cfg.markers && !previous && n * 6 < plotWidthNow();
          traces.push({type: 'scatter', mode: dots ? 'lines+markers' : 'lines', name, x, y: v.low, connectgaps: false, marker: {size: 3.5, color},
            line: {color, width: previous ? 1 : (cfg.width || 1.6), dash: previous ? 'dash' : 'solid', simplify: false}});
          probes.push({label: name, x, y: v.low});
        } else {
          const x = uniformX(w.start, w.end, n, true), last = a => [...a, a[n - 1]];
          // Continuous filled extrema bands avoid a comb of separately rasterized vertical strokes.
          // Audio envelopes are dense enough to read as a contour; function bins stay stepped (hv).
          const shape = cfg.shape || 'hv', ex = shape === 'hv' ? x : x.slice(0, n).map((t, i) => (t + x[i + 1]) / 2), ey = a => shape === 'hv' ? last(a) : a;
          traces.push({type: 'scatter', mode: 'lines', name, x: ex, y: ey(v.low), line: {color, width: .6, shape, dash: previous ? 'dash' : 'solid'}});
          traces.push({type: 'scatter', mode: 'lines', name, x: ex, y: ey(v.high), line: {color, width: .6, shape, dash: previous ? 'dash' : 'solid'},
            fill: 'tonexty', fillcolor: previous ? 'rgba(150,150,150,.12)' : (cfg.fill || 'rgba(235,235,235,.42)')});
          probes.push({label: name, edges: x, low: v.low, high: v.high});
        }
      };
      if (lane.certified) {
        // Opt-in certified bound: every value in the bin lies inside, whether or not a sample landed on it.
        const c = lane.certified, n = c.low.length, x = uniformX(w.start, w.end, n, true), last = a => [...a, a[n - 1]];
        traces.push({type: 'scatter', mode: 'lines', name: lane.name + ' · certified', x, y: last(c.low), line: {color: 'rgba(255,170,40,.9)', width: .6, shape: 'hv'}});
        traces.push({type: 'scatter', mode: 'lines', name: lane.name + ' · certified', x, y: last(c.high), line: {color: 'rgba(255,170,40,.9)', width: .6, shape: 'hv'},
          fill: 'tonexty', fillcolor: 'rgba(255,170,40,.28)'});
        probes.push({label: 'certified', edges: x, low: c.low, high: c.high});
      }
      if (lane.comparison) addTrace(lane.comparison, true);
      addTrace(lane.current, false);
      return {name: lane.name, minimum: lane.minimum, maximum: lane.maximum, yZoom: !!cfg.yZoom, weight: cfg.weight || 1,
        yTitle: cfg.yTitle, zeroLine: cfg.zeroLine, traces, probes: probes.reverse()};
    }),
    certifiedRefusals: w.lanes.filter(l => l.certifiedRefusal).map(l => l.name + ': ' + l.certifiedRefusal)});

  const palette = ['#61c8d9', '#e7b46a', '#8ccf91', '#c390d4', '#d76d78', '#78aee8'];
  const finiteOf = a => a.filter(v => v != null && Number.isFinite(v));
  const boundsOf = (a, fallback) => { let lo = Infinity, hi = -Infinity; for (const x of a) if (x != null && Number.isFinite(x)) { if (x < lo) lo = x; if (x > hi) hi = x; } return lo <= hi ? [lo, hi] : fallback; };
  const padded = ([lo, hi], p = .08) => lo === hi ? [lo - Math.max(1, Math.abs(lo) * .1), hi + Math.max(1, Math.abs(hi) * .1)] : [lo - (hi - lo) * p, hi + (hi - lo) * p];
  const noteName = m => ['C', 'C♯', 'D', 'D♯', 'E', 'F', 'F♯', 'G', 'G♯', 'A', 'A♯', 'B'][((m % 12) + 12) % 12] + (Math.floor(m / 12) - 1);
  const octaveTicks = (lo, hi) => { const vals = []; for (let n = Math.ceil(lo / 12) * 12; n <= hi; n += 12) vals.push(n); return {vals, text: vals.map(noteName)}; };
  const seriesLane = (name, items, opts = {}) => {
    const traces = [], probes = [];
    items.forEach(({s, color, map = v => v, width = 1.35, opacity = 1}) => {
      if (!s) return;
      const y = s.values.map(v => v == null ? null : map(v));
      traces.push({type: 'scatter', mode: 'lines', name: s.label, x: s.times_seconds, y, connectgaps: false, opacity, line: {color, width, simplify: true}});
      probes.push({label: s.label, unit: s.unit, x: s.times_seconds, y: s.values, digits: s.unit === 'bpm' ? 1 : 2});
    });
    const [lo, hi] = opts.range || padded(boundsOf(traces.flatMap(t => t.y), [0, 1]), .06);
    return {name, minimum: lo, maximum: hi, weight: opts.weight || 1, traces, probes, ticks: opts.ticks, autoTicks: true};
  };
  const noteTraces = (notes, colorOf, alphaOf, laneHeightPx, lo, hi) => {
    // One path per colour/alpha bucket: thousands of notes stay a handful of SVG paths, not shapes.
    const buckets = new Map(), width = clamp(laneHeightPx / Math.max(2, hi - lo) * .72, 1.5, 9);
    for (const n of notes) {
      const key = colorOf(n) + '|' + Math.round(alphaOf(n) * 4) / 4;
      let b = buckets.get(key); if (!b) buckets.set(key, b = {x: [], y: []});
      b.x.push(n.start_seconds, Math.max(n.end_seconds, n.start_seconds + 1e-3), null); b.y.push(n.midi, n.midi, null);
    }
    // Plotly splits an SVG line at every null, so N notes are N <path>s: past ~2,000 notes a pan
    // costs 150-340 ms (measured at 20,000). WebGL draws each bucket in one call.
    const type = notes.length > 2000 ? 'scattergl' : 'scatter';
    return [...buckets].map(([key, b]) => { const [color, alpha] = key.split('|');
      return {type, mode: 'lines', name: 'notes', x: b.x, y: b.y, connectgaps: false, opacity: Math.max(.25, Number(alpha) || .25),
        line: {color, width, simplify: false}}; });
  };
  const fromPreview = data => {
    const series = name => data.series.find(s => s.name === name), h = config.plotHeight || 360, lanes = [];
    let legend = '', markers = [], notes = [];
    if (data.operation === 'analysis.beats') {
      const onset = series('onset_strength'), tempos = data.series.filter(s => s.name.includes('_bpm')), conf = data.series.filter(s => s.name.includes('_confidence'));
      const oMax = Math.max(1e-9, ...finiteOf(onset ? onset.values : []));
      lanes.push(seriesLane('onset', [{s: onset, color: palette[0], map: v => v / oMax}], {range: [-.04, 1.08], weight: 1.1}));
      lanes.push(seriesLane('local tempo', tempos.map((s, i) => ({s, color: palette[(i + 1) % palette.length]})), {weight: 1.3}));
      lanes.push(seriesLane('tempo confidence', conf.map((s, i) => ({s, color: palette[(i + 1) % palette.length], opacity: .8})), {range: [-.04, 1.06], weight: .9}));
      markers = data.beat_times_seconds || []; legend = 'cyan onset · coloured tempo scales · gold beats';
    } else if (data.operation === 'analysis.pitch_mono') {
      const f = series('frequency_hz'), voice = series('voiced_probability'), midi = v => v > 0 ? 69 + 12 * Math.log2(v / 440) : null;
      const pb = boundsOf(finiteOf(f ? f.values : []).filter(v => v > 0).map(midi), [48, 72]), lo = Math.floor(pb[0] - 1), hi = Math.ceil(pb[1] + 1);
      const pitch = seriesLane('pYIN pitch', [{s: f, color: palette[0], map: midi, width: 1.8}], {range: [lo, hi], weight: 3, ticks: octaveTicks(lo, hi)});
      if (pitch.probes[0]) pitch.probes[0].unit = 'Hz';
      lanes.push(pitch);
      lanes.push(seriesLane('voiced probability', [{s: voice, color: palette[2]}], {range: [-.04, 1.06]}));
      legend = 'cyan F0 · green voicing';
    } else if (data.operation === 'analysis.pitch_poly' || data.operation === 'analysis.transcribe_music') {
      const music = data.operation === 'analysis.transcribe_music'; notes = data.notes || [];
      const nb = notes.length ? boundsOf(notes.map(n => n.midi), [48, 72]) : [48, 72], lo = nb[0] - 1.5, hi = nb[1] + 1.5;
      const names = [...new Set(notes.map(n => n.instrument || 'unassigned'))].sort();
      const colorOf = music ? n => palette[Math.max(0, names.indexOf(n.instrument || 'unassigned')) % palette.length] : n => palette[n.midi % palette.length];
      const alphaOf = music ? () => .85 : n => .25 + .75 * (n.amplitude ?? 1);
      const rollWeight = 3, weights = rollWeight + 2;
      lanes.push({name: music ? 'MuScriptor instrument piano roll' : 'Basic Pitch piano roll', minimum: lo, maximum: hi, weight: rollWeight,
        ticks: octaveTicks(Math.ceil(lo), Math.floor(hi)), traces: noteTraces(notes, colorOf, alphaOf, h * rollWeight / weights, lo, hi), probes: []});
      if (music) {
        lanes.push(seriesLane('active notes', [{s: series('active_note_count'), color: palette[0], width: 1.6}]));
        lanes.push(seriesLane('onsets', [{s: series('onset_count'), color: palette[1], opacity: .9}]));
        legend = 'instruments · ' + names.join(' · ');
      } else {
        lanes.push(seriesLane('activation', [{s: series('maximum_note_activation'), color: palette[0]}], {range: [-.04, 1.06]}));
        lanes.push(seriesLane('active pitches', [{s: series('active_pitch_count'), color: palette[1], opacity: .9}]));
        legend = 'notes · cyan activation · gold active count';
      }
    } else {
      lanes.push({name: 'unsupported analysis · ' + data.operation, minimum: 0, maximum: 1, traces: [], probes: []});
      legend = data.operation;
    }
    if (markers.length) lanes.forEach(lane => {
      const x = [], y = []; for (const t of markers) x.push(t, t, null), y.push(lane.minimum, lane.maximum, null);
      lane.traces.unshift({type: 'scatter', mode: 'lines', name: 'beats', x, y, line: {color: 'rgba(255,235,175,.42)', width: 1}});
    });
    return {kind: 'preview', lanes, legend, notes, meta: data.operation + ' · ' + num(data.duration_seconds) + ' s'};
  };

  // ---- sources ----------------------------------------------------------------------------
  // One min/max bin per DEVICE pixel: at CSS-pixel bins a 20-sample bin spans a whole chirp cycle
  // and the envelope flattens into a blob (Marc, 2026-09-23 audio review).
  // Measured from the stage now, not from Plotly's last layout, which is stale right after a resize
  // (a first draw while the output was still closed lays out at 200 px).
  const plotWidthNow = () => Math.max(1, stage.clientWidth - margin().l - margin().r);
  const pointsWanted = () => clamp(Math.round(plotWidthNow() * Math.min(3, window.devicePixelRatio || 1)), 16, 4096);
  let decoded = null, decoderPromise = null;
  const decodeAudio = () => decoderPromise || (decoderPromise = (async () => {
    const inline = audio.getAttribute('src') || '', encoded = config.audio?.inlineWav ||
      (inline.startsWith('data:audio/wav;base64,') ? inline.substring(inline.indexOf(',') + 1) : null);
    if (encoded) {
      const bytes = Uint8Array.from(atob(encoded), c => c.charCodeAt(0));
      const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength), text = (at, n) => String.fromCharCode(...bytes.subarray(at, at + n));
      if (text(0, 4) === 'RIFF' && text(8, 4) === 'WAVE') {
        let at = 12, format = 0, channels = 0, rate = 0, bits = 0, dataOffset = -1, dataSize = 0;
        while (at + 8 <= bytes.length) {
          const kind = text(at, 4), size = view.getUint32(at + 4, true), body = at + 8;
          if (kind === 'fmt ' && size >= 16) { format = view.getUint16(body, true); channels = view.getUint16(body + 2, true); rate = view.getUint32(body + 4, true); bits = view.getUint16(body + 14, true); }
          else if (kind === 'data') { dataOffset = body; dataSize = Math.min(size, bytes.length - body); break; }
          at = body + size + (size & 1);
        }
        if (format === 1 && channels > 0 && rate > 0 && bits === 16 && dataOffset >= 0) {
          const length = Math.floor(dataSize / (channels * 2)), planes = Array.from({length: channels}, () => new Float32Array(length));
          for (let i = 0; i < length; i++) for (let ch = 0; ch < channels; ch++) planes[ch][i] = view.getInt16(dataOffset + (i * channels + ch) * 2, true) / 32768;
          return {sampleRate: rate, length, numberOfChannels: channels, duration: length / rate, getChannelData: ch => planes[ch]};
        }
      }
    }
    const Ctx = window.AudioContext || window.webkitAudioContext, context = new Ctx();
    try { return await context.decodeAudioData((await (await fetch(audio.currentSrc || audio.src)).arrayBuffer()).slice(0)); }
    finally { context.close(); }
  })());
  const envelopeWindow = (e, s, d) => ({start: s, end: s + d, sampleSpacing: 1 / (e.sample_rate || 1),
    // A service that answers a close zoom with one sample per point sends min == max: that is the waveform.
    mode: e.minimum.every((lo, ch) => lo.every((v, i) => v === e.maximum[ch][i])) ? 'waveform' : 'envelope',
    lanes: Array.from({length: Math.max(1, e.channels || e.minimum.length)}, (_, ch) => ({name: (e.channels || e.minimum.length) > 1 ? 'ch ' + (ch + 1) : 'audio',
      minimum: -1, maximum: 1, current: {low: (e.minimum[ch] || e.minimum[0]).map(Number), high: (e.maximum[ch] || e.maximum[0]).map(Number)}}))});
  const audioLanes = n => Array.from({length: n}, (_, ch) => ({color: ch % 2 ? '#d6a85f' : (colors.primary || '#63c7dc'), shape: 'linear', width: 1.3, markers: true,
    fill: ch % 2 ? 'rgba(214,168,95,.82)' : (colors.fill || 'rgba(99,199,220,.82)'), zeroLine: true}));
  const fetchAudio = async (s, d, points, signal, overview = false) => {
    if (config.audio?.waveformUrl) {
      const url = new URL(config.audio.waveformUrl, document.baseURI);
      url.searchParams.set('start_seconds', s.toFixed(9)); url.searchParams.set('duration_seconds', d.toFixed(9)); url.searchParams.set('points', String(points));
      const response = await fetch(url, {signal}); if (!response.ok) throw Error((await response.text()) || ('HTTP ' + response.status));
      const e = await response.json(); return fromWindow(envelopeWindow(e, s, d), audioLanes(e.channels || e.minimum.length));
    }
    decoded = decoded || await decodeAudio();
    if (!(total > 0)) { domainEnd = domainStart + decoded.duration; total = decoded.duration; normalize(); s = start; d = span; }
    const rate = decoded.sampleRate, begin = Math.max(0, Math.floor(s * rate)), end = Math.min(decoded.length, Math.ceil((s + d) * rate) + 1);
    const frames = Math.max(1, end - begin), dpr = Math.min(3, window.devicePixelRatio || 1);
    if (!overview) root.dataset.samplesPerPixel = String(frames / Math.max(1, plotWidthNow() * dpr));
    if (frames <= points * 1.5) {
      // Close enough to see samples: draw the waveform itself, a line through every sample.
      const lanes = Array.from({length: decoded.numberOfChannels}, (_, ch) => { const v = Array.from(decoded.getChannelData(ch).subarray(begin, end));
        return {name: decoded.numberOfChannels > 1 ? 'ch ' + (ch + 1) : 'audio', minimum: -1, maximum: 1, current: {low: v, high: v}}; });
      return fromWindow({start: begin / rate, end: (end - 1) / rate, mode: 'waveform', sampleSpacing: 1 / rate, lanes}, audioLanes(decoded.numberOfChannels));
    }
    // A true min/max per bin: bins partition [begin, end) with no gaps, so no sample is skipped.
    const count = points, minimum = [], maximum = [];
    for (let ch = 0; ch < decoded.numberOfChannels; ch++) {
      const data = decoded.getChannelData(ch), lo = new Array(count), hi = new Array(count);
      for (let x = 0; x < count; x++) {
        const a = begin + Math.floor(x * frames / count), b = begin + Math.max(1, Math.floor((x + 1) * frames / count));
        let mn = Infinity, mx = -Infinity; for (let i = a; i < Math.min(end, b); i++) { const v = data[i]; if (v < mn) mn = v; if (v > mx) mx = v; }
        lo[x] = Number.isFinite(mn) ? mn : 0; hi[x] = Number.isFinite(mx) ? mx : 0;
      }
      minimum.push(lo); maximum.push(hi);
    }
    const e = {sample_rate: rate, channels: decoded.numberOfChannels, minimum, maximum};
    return fromWindow(envelopeWindow(e, begin / rate, Math.max(1, end - begin) / rate), audioLanes(decoded.numberOfChannels));
  };
  const fetchRange = async (s, e, points, signal) => {
    const url = new URL(config.url, document.baseURI);
    url.search = new URLSearchParams({start: String(s), end: String(e), points: String(points)});
    const response = await fetch(url, {signal});
    if (!response.ok) { const error = Error((await response.text()).slice(0, 180)); error.status = response.status; throw error; }
    return fromWindow(await response.json(), config.lanes);
  };
  // Does this view need a fetch, or is it an axis change over data already in the page?
  const needsFetch = () => source === 'range' || source === 'audio' ||
    (source === 'embedded' && !!config.url && !liveExpired && span < total * .999);
  const setTotal = t => { domainEnd = domainStart + t; total = t; if (!(span > 0)) span = total; normalize(); };

  // ---- layout -----------------------------------------------------------------------------
  const margin = () => ({l: config.margin?.l ?? 52, r: config.margin?.r ?? 12, t: config.margin?.t ?? 10, b: config.xLabel ? 44 : 26});
  const plotArea = () => { const s = plot._fullLayout?._size, m = margin(); return s ? {l: s.l, t: s.t, w: s.w, h: s.h} : {l: m.l, t: m.t, w: Math.max(1, stage.clientWidth - m.l - m.r), h: 1}; };
  const lanesOf = () => model?.lanes || (config.lanes || [{name: ''}]).map(l => ({name: l.name, minimum: l.minimum ?? 0, maximum: l.maximum ?? 1, traces: [], probes: [], weight: l.weight || 1, yTitle: l.yTitle}));
  const plotHeight = () => config.plotHeight || (lanesOf().length * (config.laneHeight || 130) + margin().t + margin().b);
  // A 2px transparent tick pushes labels off the axis (plotly 2.24 has no ticklabelstandoff).
  const tickGap = {ticks: 'outside', ticklen: 2, tickcolor: 'rgba(0,0,0,0)'};
  const axisFont = {family: '"Iosevka Web", "IBM Plex Mono", ui-monospace, monospace', color: 'rgba(255,255,255,.55)', size: 9};
  const layout = () => {
    const lanes = lanesOf(), count = lanes.length, m = margin(), h = plotHeight(), inner = h - m.t - m.b;
    const gap = count > 1 ? 16 / inner : 0, weights = lanes.reduce((a, l) => a + (l.weight || 1), 0), usable = 1 - gap * (count - 1);
    const l = {width: Math.max(200, stage.clientWidth), height: h, paper_bgcolor: 'rgba(0,0,0,0)', plot_bgcolor: 'rgba(255,255,255,.022)',
      font: axisFont, margin: m, dragmode: interactive ? 'pan' : false, hovermode: false, showlegend: false,
      uirevision: config.viewKey || 'signal', annotations: [], shapes: []};
    let top = 1;
    lanes.forEach((lane, i) => {
      const suffix = i ? i + 1 : '', y0 = Math.max(0, top - usable * (lane.weight || 1) / weights);
      // A labelled lane gets a 17 px band of headroom above its data, so the label sits over empty
      // plot rather than across the curve (Marc, analysis-board review).
      const labelled = count > 1 || config.laneLabels, head = labelled ? Math.min(.45, 17 / Math.max(1, inner * (top - y0))) : 0;
      const [r0, r1] = yRanges[i] || [lane.minimum, lane.maximum], yr = [r0, r1 + (r1 - r0) * head / (1 - head)];
      const secondary = colors.secondary ? colors.secondary : null;
      l['xaxis' + suffix] = {range: [start, start + span], autorange: false, anchor: 'y' + suffix, matches: i ? 'x' : undefined,
        showticklabels: i === count - 1, gridcolor: 'rgba(255,255,255,.075)', linecolor: 'rgba(255,255,255,.14)', ...tickGap, tickfont: axisFont,
        zeroline: false, automargin: false,
        title: i === count - 1 && config.xLabel ? {text: esc(config.xLabel), standoff: 8, font: {...axisFont, color: 'rgba(255,255,255,.72)'}} : undefined};
      l['yaxis' + suffix] = {domain: [y0, top], range: yr, autorange: false, fixedrange: !(interactive && lane.yZoom),
        gridcolor: 'rgba(255,255,255,.075)', linecolor: 'rgba(255,255,255,.14)', ...tickGap, tickfont: axisFont, automargin: false, zeroline: false,
        // Padded analysis ranges get round auto ticks; a signal lane's own bounds are labelled exactly.
        ...(lane.ticks ? {tickvals: lane.ticks.vals, ticktext: lane.ticks.text} : lane.yZoom ? {nticks: 7} : lane.autoTicks ? {nticks: clamp(Math.floor(inner * (top - y0) / 26), 3, 5)} :
          {tickvals: [lane.minimum, (lane.minimum + lane.maximum) / 2, lane.maximum].map(v => Number(v.toPrecision(4)))}),
        title: lane.yTitle ? {text: esc(lane.yTitle), standoff: 10, font: {...axisFont, color: 'rgba(255,255,255,.72)'}} : undefined};
      if (labelled) l.annotations.push({xref: 'paper', yref: 'paper', x: 0, y: top, xanchor: 'left', yanchor: 'top',
        xshift: 6, yshift: -2, text: esc(lane.name), showarrow: false, font: {...axisFont, size: 8, color: 'rgba(255,255,255,.7)'},
        bgcolor: 'rgba(5,5,5,.28)', borderpad: 1});
      top = y0 - gap;
    });
    l.shapes = zeroShapes(lanes);
    return l;
  };
  // Zero axes as shapes: Plotly hides a zeroline that lands on the range edge, and x = 0 at the left
  // edge is exactly the case a function plot starts from. Only lines inside the CURRENT view are
  // emitted: a clipped line far outside still has a bounding box, and box screenshots union the
  // rects of every SVG node (a y = 0 line 2000 px below a 0.55..0.64 plot made a 4192 px capture).
  const zeroShapes = lanes => {
    const shapes = [], orange = colors.secondary || 'rgba(255,136,0,.55)', strict = config.zeroLines === 'inside';
    const inside = (v, lo, hi) => strict ? v > lo && v < hi : v >= lo && v <= hi;
    lanes.forEach((lane, i) => {
      const suffix = i ? i + 1 : '', [ylo, yhi] = yRanges[i] || [lane.minimum, lane.maximum];
      if (config.zeroLines && inside(0, domainStart, domainEnd) && 0 >= start && 0 <= start + span)
        shapes.push({type: 'line', layer: 'below', xref: 'x' + suffix, yref: 'y' + suffix + ' domain', x0: 0, x1: 0, y0: 0, y1: 1, line: {color: orange, width: 1.25}});
      if ((lane.zeroLine || (config.zeroLines && (!strict || inside(0, lane.minimum, lane.maximum)))) && 0 >= ylo && 0 <= yhi)
        shapes.push({type: 'line', layer: 'below', xref: 'x' + suffix + ' domain', yref: 'y' + suffix, x0: 0, x1: 1, y0: 0, y1: 0,
          line: {color: config.zeroLines ? orange : 'rgba(255,255,255,.22)', width: config.zeroLines ? 1.25 : 1}});
    });
    return shapes;
  };
  const traces = () => (model?.lanes || []).flatMap((lane, i) => lane.traces.map(t => ({...t, xaxis: 'x' + (i ? i + 1 : ''), yaxis: 'y' + (i ? i + 1 : ''), hoverinfo: 'skip'})));
  const plotConfig = {displaylogo: false, displayModeBar: false, scrollZoom: false, responsive: false, doubleClick: false, staticPlot: !interactive, showTips: false};
  const draw = token => {
    rendering = rendering.then(async () => {
      if (disposed || token !== generation) return;
      applying++;
      try {
        await Plot.react(plot, traces(), layout(), plotConfig);
        if (!initialized) { initialized = true; bindPlotEvents(); }
        placeLines(); drawOverview();
      } finally { applying--; }
    }); return rendering;
  };
  const relayoutAxes = () => {
    if (!initialized) return;
    rendering = rendering.then(async () => {
      if (disposed) return; applying++;
      try { await Plot.relayout(plot, {...Object.fromEntries(lanesOf().map((_, i) => ['xaxis' + (i ? i + 1 : '') + '.range', [start, start + span]])), shapes: zeroShapes(lanesOf())}); }
      finally { applying--; }
      placeLines();
    });
  };
  const describe = () => {
    if (!model) return;
    root.dataset.mode = model.mode || model.kind;
    if (model.spacing != null) root.dataset.spacing = String(model.spacing);
    if (source === 'embedded') status.textContent = model === embedded
      ? (config.samplesChip || 'embedded samples') + (liveExpired ? ' · source expired' : '') : 'live · Δx ' + fmtX(model.spacing);
    else if (source === 'analysis') status.textContent = model.meta;
    else if (source === 'audio') status.textContent = interactive ? 'click seek · wheel zoom' + (config.audio?.zoomAtPlayhead ? ' at playhead' : '') + ' · drag pan' : '';
    else status.textContent = model.mode + ' · Δt ' + fmtX(model.spacing);
    const notes = [config.label], comparison = ' · ' + (config.comparisonLabel || 'previous');
    if (model.lanes.some(l => l.probes.some(p => p.label.endsWith(comparison)))) notes.push('white: current · dashed grey: ' + (config.comparisonLabel || 'previous'));
    if (model.lanes.some(l => l.probes.some(p => p.label === 'certified'))) notes.push('amber: certified bound');
    if (model.certifiedRefusals?.length) notes.push(...model.certifiedRefusals);
    const legend = $('.fsv-legend'); if (legend && model.legend) legend.textContent = model.legend;
    title.textContent = notes.filter(Boolean).join(' / '); title.title = title.textContent;
  };

  // ---- loading ----------------------------------------------------------------------------
  const schedule = (delay = 65) => {
    clearTimeout(timer); request?.abort(); const token = ++generation;
    if (!needsFetch()) {
      if (source === 'embedded' && model !== embedded) { model = embedded; draw(token).then(describe); }
      else relayoutAxes();
      describe(); return;
    }
    if (!(total > 0) && config.audio?.waveformUrl) return;
    // A closed output has no width: wait for the ResizeObserver rather than fetch 16 bins.
    if (stage.clientWidth < 2) { setBusy(true); return; }
    setBusy(true, source === 'audio' ? 'loading waveform' : 'sampling');
    timer = setTimeout(async () => {
      const controller = new AbortController(); request = controller;
      const deadline = setTimeout(() => controller.abort('timeout'), 15000);
      try {
        let next;
        if (source === 'audio') {
          next = await Promise.race([fetchAudio(start, span, pointsWanted(), controller.signal),
            new Promise((_, reject) => controller.signal.addEventListener('abort', () => reject(Object.assign(Error('aborted'), {name: 'AbortError'}))))]);
        } else next = await fetchRange(start, Math.min(domainEnd, start + span), pointsWanted(), controller.signal);
        if (disposed || token !== generation) return;
        model = next; loadedOnce = true; await draw(token); if (disposed || token !== generation) return;
        describe(); if (!overviewModel) loadOverview();
      } catch (error) {
        if (disposed || token !== generation) return;
        if (source === 'embedded') { liveExpired = true; model = embedded; loadedOnce = true; await draw(token); describe(); }
        else if (error.name !== 'AbortError' || controller.signal.reason === 'timeout')
          status.textContent = (source === 'audio' ? 'waveform: ' : 'Signal: ') + (controller.signal.reason === 'timeout' ? 'request timed out after 15 seconds' : error.message);
      } finally {
        clearTimeout(deadline); if (request === controller) request = null;
        if (token === generation && !disposed) setBusy(false);
      }
    }, delay);
  };
  const changed = (broadcast = true, native = false) => {
    normalize(); if (!native) relayoutAxes(); else if (initialized) rendering = rendering.then(async () => {
      if (disposed) return; applying++; try { await Plot.relayout(plot, {shapes: zeroShapes(lanesOf())}); } finally { applying--; } });
    schedule();
    if (broadcast) publish('field-time-window', {start: start + offset, duration: span});
  };

  // ---- overview strip ---------------------------------------------------------------------
  let overviewRequested = false;
  // One coarse whole-domain query, only when the first view was already zoomed in.
  const loadOverview = async () => {
    if (overviewRequested || !navShape || !(total > 0)) return; overviewRequested = true;
    try {
      overviewModel = source === 'audio' ? await fetchAudio(domainStart, total, 400, undefined, true) : await fetchRange(domainStart, domainEnd, 400);
      if (!disposed) drawOverview();
    } catch (_) { /* the strip stays an outline; the main view reports its own errors */ }
  };
  function drawOverview() {
    if (!navShape || !model) return;
    if (!overviewModel && (span >= total * .999 || model.kind === 'preview' || model === embedded)) overviewModel = model;
    const m = overviewModel; if (!m) return;
    const lane = m.lanes.find(l => l.probes.length) || m.lanes[0], p = lane?.probes[lane.probes.length - 1];
    const lo = lane?.minimum ?? 0, hi = lane?.maximum ?? 1, W = 1000, H = 24;
    const xs = t => ((t - domainStart) / (total || 1) * W).toFixed(1), ys = v => (H - (clamp(v, lo, hi) - lo) / ((hi - lo) || 1) * H).toFixed(1);
    let d = '';
    if (p?.edges) {
      const up = p.high.map((v, i) => xs(p.edges[i]) + ',' + ys(v)), down = p.low.map((v, i) => xs(p.edges[i]) + ',' + ys(v)).reverse();
      d = 'M' + up.join('L') + 'L' + down.join('L') + 'Z';
    } else if (p?.x) {
      // Draw what the lane plots (e.g. F0 in MIDI), not the probe's raw readout units (Hz).
      const t = lane.traces.find(t => t.name !== 'beats' && t.name !== 'notes') || {x: p.x, y: p.y};
      const step = Math.max(1, Math.floor(t.x.length / 800)); let open = false;
      for (let i = 0; i < t.x.length; i += step) { const v = t.y[i]; if (v == null || !Number.isFinite(v)) { open = false; continue; }
        d += (open ? 'L' : 'M') + xs(t.x[i]) + ',' + ys(v); open = true; }
    } else if (m.notes?.length) {
      const [a, b] = boundsOf(m.notes.map(n => n.midi), [0, 1]);
      for (const n of m.notes.slice(0, 4000)) d += 'M' + xs(n.start_seconds) + ',' + (H - 1 - (n.midi - a) / ((b - a) || 1) * (H - 2)).toFixed(1) + 'H' + xs(n.end_seconds);
    }
    navShape.setAttribute('d', d); navShape.setAttribute('class', 'fsv-overview' + (p?.edges ? ' fsv-filled' : ''));
  }

  // ---- cursors, playhead, readout ---------------------------------------------------------
  const xPixel = t => { const a = plotArea(); return a.l + (t - start) / span * a.w; };
  const placeLine = (line, t, kind) => {
    const visible = initialized && t != null && Number.isFinite(t) && t >= start && t <= start + span;
    line.style.display = visible ? 'block' : 'none'; if (!visible) return;
    const a = plotArea(); line.style.transform = 'translateX(' + xPixel(t).toFixed(1) + 'px)'; line.style.top = a.t + 'px'; line.style.height = a.h + 'px';
    if (kind) line.dataset.kind = kind;
  };
  function placeLines() {
    placeLine(cursorLine, pointerTime ?? external, pointerTime != null ? 'pointer' : externalKind);
    placeLine(playLine, audio ? (config.audio?.showPlayhead === false ? null : audio.currentTime) : pinned, 'playhead');
  }
  const nearest = (xs, t) => { let a = 0, b = xs.length - 1; if (b < 0) return -1;
    while (b - a > 1) { const m = (a + b) >> 1; if (xs[m] == null || xs[m] < t) a = m; else b = m; }
    return Math.abs((xs[a] ?? Infinity) - t) <= Math.abs((xs[b] ?? Infinity) - t) ? a : b; };
  const probeText = (p, t) => {
    const fmt = v => v == null ? '–' : (p.digits != null && Number.isFinite(v) ? v.toFixed(p.digits) : num(v)) + (p.unit ? ' ' + p.unit : '');
    if (p.edges) { const n = p.low.length, i = clamp(Math.floor((t - p.edges[0]) / (p.edges[n] - p.edges[0]) * n), 0, n - 1);
      return p.low[i] === p.high[i] ? fmt(p.low[i]) : fmt(p.low[i]) + ' … ' + fmt(p.high[i]); }
    const i = nearest(p.x, t); return i < 0 ? null : fmt(p.y[i]);
  };
  const updateReadout = t => {
    if (t == null || !model) { readout.textContent = config.readoutIdle || ''; return; }
    const parts = [];
    for (const lane of model.lanes) for (const p of lane.probes) { const v = probeText(p, t); if (v != null) parts.push((config.yName || p.label) + ' ' + v); }
    if (model.notes?.length) { const sounding = model.notes.filter(n => n.start_seconds <= t && n.end_seconds >= t).slice(0, 6).map(n => noteName(n.midi) + (n.instrument ? ' ' + n.instrument : ''));
      if (sounding.length) parts.push('notes ' + sounding.join(' ')); }
    readout.textContent = (config.xName ? config.xName + ' ' : '') + fmtX(t) + (parts.length ? '   ' + parts.join('  ·  ') : '');
  };
  const timeAt = clientX => { const r = plot.getBoundingClientRect(), a = plotArea(); return start + (clientX - r.left - a.l) / a.w * span; };
  const inside = event => { const r = plot.getBoundingClientRect(), a = plotArea(), x = event.clientX - r.left, y = event.clientY - r.top; return x >= a.l && x <= a.l + a.w && y >= a.t && y <= a.t + a.h; };

  // ---- interaction ------------------------------------------------------------------------
  const zoomAnchorTime = fallback => {
    const p = audio?.currentTime;
    return config.audio?.zoomAtPlayhead && Number.isFinite(p) && p >= start && p <= start + span ? p : fallback;
  };
  const zoom = (factor, at = start + span / 2) => { if (!interactive || !(total > 0)) return; at = zoomAnchorTime(at); const anchor = (at - start) / span;
    span = clamp(span * factor, minSpanOf(), total); start = at - span * anchor; changed(); };
  const reset = () => { start = domainStart; span = total; const hadY = Object.keys(yRanges).length; yRanges = {}; if (hadY && initialized) draw(generation); changed(); };
  const fitY = () => {
    if (!model) return;
    model.lanes.forEach((lane, i) => {
      let lo = Infinity, hi = -Infinity;
      for (const t of lane.traces) { if (t.name === 'beats' || t.name === 'notes') continue;
        for (let k = 0; k < t.x.length; k++) { const x = t.x[k], y = t.y[k]; if (x != null && y != null && Number.isFinite(y) && x >= start && x <= start + span) { if (y < lo) lo = y; if (y > hi) hi = y; } } }
      if (lo <= hi) yRanges[i] = padded([lo, hi]);
    });
    draw(generation);
  };
  const seek = t => {
    t = clamp(t, domainStart, domainEnd);
    if (audio) audio.currentTime = t; else pinned = t;
    publish('field-time-seek', {time: t + offset}); publish('field-time-cursor', {time: t + offset, kind: 'playhead', active: true});
    placeLines(); updateReadout(t);
  };
  function bindPlotEvents() {
    plot.on('plotly_relayout', event => {
      if (applying || disposed) return;
      const key = Object.keys(event).find(k => /^xaxis\d*\.range\[0\]$/.test(k)), whole = Object.keys(event).find(k => /^xaxis\d*\.range$/.test(k));
      for (const k of Object.keys(event)) { const m = /^yaxis(\d*)\.range\[0\]$/.exec(k); if (m) yRanges[m[1] ? Number(m[1]) - 1 : 0] = [event[k], event[k.replace('[0]', '[1]')]]; }
      const next = key ? [event[key], event[key.replace('[0]', '[1]')]] : whole ? event[whole] : null;
      if (!next || !next.every(Number.isFinite) || next[1] <= next[0]) return;
      start = next[0]; span = next[1] - next[0]; changed(true, true);
    });
  }
  // Plotly handles the target first; keep Field's enclosing box click/editor handlers out.
  for (const target of [plot, nav]) for (const name of ['pointerdown', 'mousedown', 'click', 'touchstart']) on(target, name, event => event.stopPropagation());
  let press = null;
  on(plot, 'pointerdown', event => { press = {x: event.clientX, y: event.clientY, inside: inside(event)}; }, {capture: true});
  on(plot, 'pointerup', event => {
    const p = press; press = null;
    if (!interactive || !p || !p.inside || Math.hypot(event.clientX - p.x, event.clientY - p.y) > 3) return;
    if (audio || group) seek(timeAt(event.clientX));
  }, {capture: true});
  on(plot, 'pointermove', event => {
    pointerTime = inside(event) ? timeAt(event.clientX) : null; placeLines(); updateReadout(pointerTime ?? external);
    publish('field-time-cursor', pointerTime == null ? {time: null, kind: 'hover', active: false} : {time: pointerTime + offset, kind: 'hover', active: true,
      ...(audio ? {playing: !audio.paused && !audio.ended} : {})});
  });
  on(plot, 'pointerleave', () => { pointerTime = null; placeLines(); updateReadout(external); publish('field-time-cursor', {time: null, kind: 'hover', active: false}); });
  on(plot, 'wheel', event => {
    if (!interactive) return;
    event.preventDefault(); event.stopPropagation(); const rect = plot.getBoundingClientRect(), a = plotArea();
    const unit = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? a.w : 1;
    if (event.shiftKey || Math.abs(event.deltaX) > Math.abs(event.deltaY)) { start += (event.deltaX || event.deltaY) * unit / a.w * span; changed(); return; }
    if (event.altKey && model) {
      const y = event.clientY - rect.top, index = model.lanes.findIndex((lane, i) => { const ax = plot._fullLayout?.['yaxis' + (i ? i + 1 : '')]; return lane.yZoom && ax && y >= ax._offset && y <= ax._offset + ax._length; });
      if (index >= 0) { const ax = plot._fullLayout['yaxis' + (index ? index + 1 : '')], [lo, hi] = yRanges[index] || [model.lanes[index].minimum, model.lanes[index].maximum];
        const at = ax.p2l(y - ax._offset), f = Math.exp(clamp(event.deltaY * unit * .002, -2, 2)); yRanges[index] = [at - (at - lo) * f, at + (hi - at) * f]; draw(generation); return; }
    }
    zoom(Math.exp(clamp(event.deltaY * unit * .002, -2, 2)), start + clamp((event.clientX - rect.left - a.l) / a.w, 0, 1) * span);
  }, {passive: false, capture: true});
  on(plot, 'dblclick', event => { event.preventDefault(); if (interactive) reset(); });
  on(plot, 'keydown', event => {
    if (!interactive) return;
    if (event.key === '+' || event.key === '=') zoom(.5); else if (event.key === '-') zoom(2); else if (event.key === 'Home' || event.key === '0') reset();
    else if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') { start += (event.key === 'ArrowLeft' ? -1 : 1) * span * .1; changed(); }
    else if (event.key === 'f') fitY(); else if (event.key === ' ' && audio) { audio.paused ? audio.play() : audio.pause(); } else return;
    event.preventDefault(); event.stopPropagation();
  });
  if (nav) {
    let navDrag = null;
    const navTo = event => { const r = nav.getBoundingClientRect(), t = domainStart + clamp((event.clientX - r.left) / r.width, 0, 1) * total;
      start = t - span * (navDrag?.grab ?? .5); changed(); };
    on(nav, 'pointerdown', event => { if (!interactive || span >= total) return; const r = nav.getBoundingClientRect(), t = domainStart + (event.clientX - r.left) / r.width * total;
      navDrag = {grab: t >= start && t <= start + span ? (t - start) / span : .5}; try { nav.setPointerCapture(event.pointerId); } catch (_) {} navTo(event); });
    on(nav, 'pointermove', event => { if (navDrag) navTo(event); });
    on(nav, 'pointerup', () => { navDrag = null; });
    on(nav, 'keydown', event => { if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') { start += (event.key === 'ArrowLeft' ? -1 : 1) * span * .25; changed(); event.preventDefault(); } });
  }
  for (const [action, fn] of [['in', () => zoom(.5)], ['out', () => zoom(2)], ['all', reset], ['fit', fitY]]) on($('[data-action="' + action + '"]'), 'click', fn);
  on(bus, 'field-time-window', event => { const d = event.detail || {}; if (!group || d.group !== group || d.source === instance || !Number.isFinite(d.start) || !Number.isFinite(d.duration) || d.duration <= 0) return;
    start = d.start - offset; span = d.duration; changed(false); });
  on(bus, 'field-time-cursor', event => { const d = event.detail || {}; if (!group || d.group !== group || d.source === instance) return;
    external = d.active === false || d.time == null ? null : Number(d.time) - offset; externalKind = d.kind || 'hover';
    if (!audio && externalKind === 'playhead' && external != null) pinned = null;
    placeLines(); if (pointerTime == null) updateReadout(external); });
  if (audio) {
    on(bus, 'field-time-seek', event => { const d = event.detail || {}; if (!group || d.group !== group || d.source === instance || !Number.isFinite(Number(d.time))) return;
      audio.currentTime = clamp(Number(d.time) - offset, 0, total || Infinity); placeLines(); });
    let raf = 0;
    const follow = () => {
      if (config.audio?.follow === false || !(total > 0)) return; const p = audio.currentTime; if (!Number.isFinite(p)) return;
      let next = null; if (p > start + span) next = p - span * .08; else if (p < start) next = p - span * .92;
      if (next !== null) { start = next; changed(); }
    };
    const publishPlayhead = () => publish('field-time-cursor', {time: audio.currentTime + offset, kind: 'playhead', active: true, playing: !audio.paused && !audio.ended});
    const animate = () => { follow(); placeLines(); publishPlayhead(); if (!audio.paused && !audio.ended) raf = requestAnimationFrame(animate); };
    on(audio, 'play', () => { cancelAnimationFrame(raf); publish('field-time-transport', {state: 'play', time: audio.currentTime + offset}); animate(); });
    on(audio, 'pause', () => { placeLines(); publish('field-time-transport', {state: 'pause', time: audio.currentTime + offset}); publishPlayhead(); });
    on(audio, 'seeked', () => { placeLines(); publishPlayhead(); });
    on(audio, 'loadedmetadata', () => {
      const known = total > 0; if (Number.isFinite(audio.duration) && audio.duration > 0) { domainEnd = domainStart + audio.duration; total = audio.duration; }
      if (Number.isFinite(config.audio?.initialPosition)) audio.currentTime = clamp(config.audio.initialPosition, 0, total);
      if (!(span > 0)) span = total; normalize(); if (!known || !loadedOnce) schedule(0);
      publish('field-time-window', {start: start + offset, duration: span}); publishPlayhead();
    });
  }

  // ---- lifecycle --------------------------------------------------------------------------
  let lastWidth = 0;
  const resize = new ResizeObserver(() => { const w = stage.clientWidth; if (Math.abs(w - lastWidth) < 1) return; lastWidth = w;
    if (needsFetch()) schedule(80); else if (model) draw(++generation); });
  const dispose = () => { if (disposed) return; disposed = true; listeners.abort(); request?.abort(); clearTimeout(timer); resize.disconnect(); removal.disconnect(); rendering.finally(() => Plot.purge(plot)); };
  const removal = new MutationObserver(() => { if (!root.isConnected) dispose(); }); removal.observe(document, {childList: true, subtree: true});
  on(window, 'pagehide', dispose);
  readout.textContent = config.readoutIdle || '';
  const boot = async () => {
    if (source === 'embedded') {
      embedded = fromWindow(config.window, config.lanes); model = embedded; loadedOnce = true; normalize();
      await draw(generation); describe(); setBusy(false); if (needsFetch()) schedule(0);
    } else if (source === 'analysis') {
      setBusy(true, 'loading preview');
      try {
        const inline = root.querySelector('.fsv-preview');
        const data = inline ? JSON.parse(inline.textContent) : await (async () => { const r = await fetch(config.analysisUrl, {cache: 'no-store'}); if (!r.ok) throw Error(r.status + ' ' + r.statusText); return r.json(); })();
        domainStart = 0; total = 0; span = saved?.span ?? config.initialDuration ?? 0; setTotal(Math.max(.1, data.duration_seconds));
        model = fromPreview(data); loadedOnce = true; normalize();
        await draw(generation); describe();
      } catch (error) { loadedOnce = true; status.textContent = 'unable to load analysis: ' + error.message; status.classList.add('fsv-error'); }
      finally { setBusy(false); }
    } else if (total > 0 || source === 'range' || !config.audio?.waveformUrl) schedule(0);
  };
  normalize(); lastWidth = stage.clientWidth; resize.observe(stage); boot();
})();
