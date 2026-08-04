/**
 * hack.chat protocol probe client.
 *
 * Deliberately dumb: it does not model the protocol, it records it. Every frame
 * in and out is timestamped and kept so scenarios can assert on what a *peer*
 * observed, not just what the actor sent.
 */

import { readFileSync, writeFileSync } from 'node:fs';

const DEFAULT_URL = 'wss://hack.chat/chat-ws';

/**
 * Mirrors the server's RateLimiter (hackchat-server/src/serverLib/RateLimiter.js).
 * Scores are per remote address, so every socket we open from this machine
 * shares one budget. We simulate it locally and stall until spending is safe.
 */
class Governor {
  constructor({ halflife = 30_000, threshold = 25, ceiling = 12 } = {}) {
    this.halflife = halflife;
    this.threshold = threshold;
    // Stay well under the real threshold; we are guessing at costs we don't model.
    this.ceiling = ceiling;
    this.score = 0;
    this.time = Date.now();
  }

  projected(at = Date.now()) {
    return this.score * Math.pow(2, -(at - this.time) / this.halflife);
  }

  /** ms to wait until `cost` can be spent without crossing the ceiling. */
  waitFor(cost) {
    const headroom = this.ceiling - cost;
    if (headroom <= 0) throw new Error(`cost ${cost} exceeds ceiling ${this.ceiling}`);
    const now = Date.now();
    const current = this.projected(now);
    if (current <= headroom) return 0;
    // current * 2^(-t/halflife) = headroom
    return Math.ceil(this.halflife * Math.log2(current / headroom));
  }

  async spend(cost, label = '') {
    const wait = this.waitFor(cost);
    if (wait > 0) {
      log('gov', `holding ${(wait / 1000).toFixed(1)}s before ${label} (score ${this.projected().toFixed(1)})`);
      await sleep(wait);
    }
    const now = Date.now();
    this.score = this.projected(now) + cost;
    this.time = now;
  }
}

/** Known server-side penalties, from the command modules. */
export const COST = {
  join: 3,
  session: 0, // see session.js frisk() bug: never actually scores
  chat: (text) => text.length / 83 / 4,
  malformed: 13, // parseText failure or oversized customId
  default: 1,
};

export const governor = new Governor();

/**
 * The server's score for our address survives our process exiting, so persist
 * ours too — otherwise back-to-back runs each start from zero and we quietly
 * blow through the real threshold.
 */
const STATE = new URL('./.governor.json', import.meta.url);
try {
  const prev = JSON.parse(readFileSync(STATE, 'utf8'));
  governor.score = prev.score;
  governor.time = prev.time;
  const carried = governor.projected();
  if (carried > 0.5) log('gov', `carrying ${carried.toFixed(1)}/25 from a previous run`);
} catch { /* no prior state */ }

process.on('exit', () => {
  try {
    writeFileSync(STATE, JSON.stringify({ score: governor.score, time: governor.time }));
  } catch { /* best effort */ }
});

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let t0 = Date.now();
export function resetClock() { t0 = Date.now(); }

export function log(tag, msg) {
  const t = ((Date.now() - t0) / 1000).toFixed(2).padStart(7);
  console.log(`[${t}s] ${tag.padEnd(9)} ${msg}`);
}

export class Client {
  /**
   * @param {string} name label for logs
   * @param {object} opts
   * @param {1|2} opts.protocol which dialect to declare. v2 sends `{cmd:'session'}`
   *   as the first frame (session.js sets hcProtocol=2 before checking the token);
   *   v1 sends `join` first, which routes through upgradeLegacyJoin().
   */
  constructor(name, { url = DEFAULT_URL, protocol = 2, verbose = true } = {}) {
    this.name = name;
    this.url = url;
    this.protocol = protocol;
    this.verbose = verbose;
    this.frames = [];   // every inbound frame, in order
    this.sent = [];
    this.ws = null;
    this.token = null;
    this.closed = false;
    this._waiters = [];
  }

  async connect() {
    this.closed = false;
    this.ws = new WebSocket(this.url);
    this.ws.onmessage = (e) => this._onFrame(e.data);
    this.ws.onclose = () => {
      this.closed = true;
      if (this.verbose) log(this.name, 'socket closed');
    };
    this.ws.onerror = () => {};
    await new Promise((res, rej) => {
      this.ws.onopen = res;
      setTimeout(() => rej(new Error(`${this.name}: connect timeout`)), 10_000);
    });
    if (this.verbose) log(this.name, `connected (declaring v${this.protocol})`);
    return this;
  }

  _onFrame(raw) {
    let payload;
    try {
      payload = JSON.parse(raw);
    } catch {
      payload = { _unparseable: String(raw) };
    }
    const rec = { at: Date.now() - t0, ...payload };
    this.frames.push(rec);
    if (payload.cmd === 'session' && payload.token) this.token = payload.token;
    if (this.verbose) log(this.name, `<< ${preview(payload)}`);
    this._waiters = this._waiters.filter((w) => {
      if (w.pred(rec)) { w.resolve(rec); return false; }
      return true;
    });
  }

  send(payload, { cost = COST.default } = {}) {
    return governor.spend(typeof cost === 'function' ? cost(payload.text ?? '') : cost, `${this.name} ${payload.cmd}`)
      .then(() => {
        this.sent.push({ at: Date.now() - t0, ...payload });
        if (this.verbose) log(this.name, `>> ${preview(payload)}`);
        this.ws.send(JSON.stringify(payload));
      });
  }

  /** Resolves on the next inbound frame matching `pred`. Rejects on timeout. */
  waitFor(pred, { timeout = 6000, label = 'frame' } = {}) {
    const fn = typeof pred === 'string' ? (f) => f.cmd === pred : pred;
    return new Promise((resolve, reject) => {
      const w = { pred: fn, resolve };
      this._waiters.push(w);
      setTimeout(() => {
        this._waiters = this._waiters.filter((x) => x !== w);
        reject(new Error(`${this.name}: timed out waiting for ${label}`));
      }, timeout);
    });
  }

  /** Frames received since a marker index. Use `mark()` to get one. */
  mark() { return this.frames.length; }
  since(m) { return this.frames.slice(m); }

  /**
   * Perform the dialect-declaring handshake, then join.
   * v2: session (restores if we hold a token) then join only if not restored.
   */
  async handshake({ channel, nick, pass, token = null } = {}) {
    if (this.protocol === 2) {
      await this.send({ cmd: 'session', ...(token ? { token } : {}) }, { cost: COST.session });
      const s = await this.waitFor('session', { label: 'session reply' });
      if (s.restored) {
        log(this.name, `restored session -> channels=${JSON.stringify(s.channels)}`);
        return { restored: true, session: s };
      }
    }
    const payload = { cmd: 'join', channel, nick };
    if (pass) payload.pass = pass;
    await this.send(payload, { cost: COST.join });
    const set = await this.waitFor('onlineSet', { label: 'onlineSet' });
    // The post-join session token trails onlineSet (and the MOTD info frame),
    // so a client must not consider the handshake finished at onlineSet.
    await this.waitFor((f) => f.cmd === 'session' && f.token, { timeout: 4000, label: 'post-join token' })
      .catch(() => log(this.name, 'WARNING: no session token followed join'));
    return { restored: false, onlineSet: set };
  }

  /** Abrupt close, simulating a mobile browser/OS killing the socket. */
  kill() {
    log(this.name, 'killing socket (simulated background kill)');
    this.ws.close();
    this.closed = true;
  }
}

function preview(p) {
  const clone = { ...p };
  delete clone.at;
  if (typeof clone.token === 'string' && clone.token.length > 24) {
    clone.token = `${clone.token.slice(0, 12)}…(${clone.token.length})`;
  }
  const s = JSON.stringify(clone);
  return s.length > 260 ? `${s.slice(0, 260)}…` : s;
}

export function randChannel(tag = 'probe') {
  return `${tag}-${Math.random().toString(36).slice(2, 10)}`;
}
