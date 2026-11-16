// SPDX-License-Identifier: GPL-3.0-only
// FM1 Emulator: Web MIDI (navigator.requestMIDIAccess) over the app's bridge (obsMidi, MidiBridge.kt): the phone's MIDI
// devices as MIDIInput and MIDIOutput, their state changes as statechange.
(() => {
  // (Android's WebView may have a requestMIDIAccess of its own that sees no device: this one replaces it)
  if (!window.obsMidi) return;
  const bridge = window.obsMidi;
  const inputs = new Map(), outputs = new Map();
  let access = null, waiting = [];

  class Port extends EventTarget {
    constructor(type, d) {
      super();
      Object.assign(this, { id: d.id, name: d.name, manufacturer: d.manufacturer || '', version: '', type,
                            state: 'connected', connection: 'closed' });
      this._on = { midimessage: null, statechange: null };
    }
    get onstatechange() { return this._on.statechange; }
    set onstatechange(f) { this._set('statechange', f); }
    _set(k, f) {
      if (this._on[k]) this.removeEventListener(k, this._on[k]);
      this._on[k] = typeof f === 'function' ? f : null;
      if (this._on[k]) this.addEventListener(k, this._on[k]);
    }
    open() { this.connection = 'open'; return Promise.resolve(this); }
    close() { this.connection = 'closed'; return Promise.resolve(this); }
  }
  class Input extends Port {
    constructor(d) { super('input', d); }
    get onmidimessage() { return this._on.midimessage; }
    set onmidimessage(f) { this._set('midimessage', f); if (f) this._listen(); }
    addEventListener(k, f, o) { super.addEventListener(k, f, o); if (k === 'midimessage') this._listen(); }
    _listen() { if (this.connection !== 'open') { this.connection = 'open'; bridge.postMessage(JSON.stringify({ t: 'open', id: this.id })); } }
  }
  class Output extends Port {
    constructor(d) { super('output', d); }
    send(data, ts) {
      const arr = Array.from(data, (x) => x & 0xFF);
      const go = () => bridge.postMessage(JSON.stringify({ t: 'send', id: this.id, data: arr }));
      const wait = ts ? ts - performance.now() : 0;
      if (wait > 1) setTimeout(go, wait); else go();
      this.connection = 'open';
    }
    clear() {}
  }
  class Access extends EventTarget {
    constructor() { super(); this.inputs = inputs; this.outputs = outputs; this.sysexEnabled = true; this._f = null; }
    get onstatechange() { return this._f; }
    set onstatechange(f) { if (this._f) this.removeEventListener('statechange', this._f); this._f = f; if (f) this.addEventListener('statechange', f); }
  }

  function update(map, list, Make) {
    const seen = new Set();
    for (const d of list) {
      seen.add(d.id);
      if (!map.has(d.id)) { const p = new Make(d); map.set(d.id, p); changed(p); }
    }
    for (const [id, p] of [...map]) if (!seen.has(id)) { p.state = 'disconnected'; map.delete(id); changed(p); }
  }
  function changed(p) {
    if (!access) return;
    const e = new Event('statechange'); e.port = p;
    p.dispatchEvent(e);
    const e2 = new Event('statechange'); e2.port = p;
    access.dispatchEvent(e2);
  }

  bridge.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    if (m.t === 'list') {
      const first = !access;
      if (first) access = new Access();
      update(inputs, m.inputs, Input);
      update(outputs, m.outputs, Output);
      if (first) { const w = waiting; waiting = []; w.forEach((r) => r(access)); }
    } else if (m.t === 'msg') {
      const p = inputs.get(m.id);
      if (!p) return;
      const e = new Event('midimessage');
      e.data = new Uint8Array(m.data);
      e.receivedTime = performance.now();
      p.dispatchEvent(e);
    }
  };

  navigator.requestMIDIAccess = () => new Promise((resolve) => {
    if (access) return resolve(access);
    waiting.push(resolve);
    bridge.postMessage(JSON.stringify({ t: 'list' }));
  });
})();
