#!/usr/bin/env node
/**
 * phantom — a local copy of the real upstream server, wired for testing.
 *
 * Unlike fakeserver.mjs, nothing here is reimplemented: it runs hack-chat/main
 * itself, exported from ../../hc at a chosen ref, with a known admin password
 * so tests can drive the commands a real moderator would — lockroom,
 * enablecaptcha, setpassword, kick — instead of waiting to meet them live.
 *
 * One process serves three ports:
 *
 *   client  (default 6070)  what the app / tests connect to. A TCP proxy in
 *                           front of the server, so a connection can be
 *                           *frozen*: silenced without closing, which is how a
 *                           phone that drops off a network looks to the server.
 *   control (default 6079)  a small HTTP API: admin actions, freeze, reset.
 *   server  (default 6071)  the upstream server itself; nothing should need it.
 *
 * Usage:
 *   node phantom.mjs setup [--ref origin/master]   export + npm ci + keys
 *   node phantom.mjs serve [--strict] [ports…]      run until killed
 *   node phantom.mjs admin <channel> <cmd> [json]   one admin action via control
 *
 * See README.md for the control API.
 */

import { spawn, execFileSync } from 'node:child_process';
import { createHash, randomBytes } from 'node:crypto';
import { existsSync, mkdirSync, rmSync, writeFileSync, readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import http from 'node:http';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '../..');
const UPSTREAM = path.join(ROOT, 'hc');
const SRV = path.join(HERE, 'srv');

/**
 * Fixed, public test passwords. Their trips are computed against the salt
 * written at setup, so they are only meaningful to this server.
 */
export const ADMIN_PASS = 'phantom-admin';
export const MOD_PASS = 'phantom-mod';

// --- args ---------------------------------------------------------------

function parseArgs(argv) {
  const out = { _: [] };
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a.startsWith('--')) {
      const key = a.slice(2);
      const next = argv[i + 1];
      if (next === undefined || next.startsWith('--')) out[key] = true;
      else { out[key] = next; i += 1; }
    } else out._.push(a);
  }
  return out;
}

const log = (...a) => console.error('[phantom]', ...a);

// --- setup --------------------------------------------------------------

function trip(pass, salt) {
  // _UAC.js getUserPerms: sha256(pass + salt), base64, first 6.
  return createHash('sha256').update(pass + salt, 'utf8').digest('base64').slice(0, 6);
}

/**
 * Dependencies upstream declares but cannot be installed, and that no command
 * imports. `uwuify` was unpublished from npm on 2026-09-03, so `npm ci` 404s on
 * upstream's own lockfile. Removed from the exported copy only.
 */
const PRUNE = ['uwuify'];

function pruneDependencies() {
  const pkgPath = path.join(SRV, 'package.json');
  const lockPath = path.join(SRV, 'package-lock.json');
  const pkg = JSON.parse(readFileSync(pkgPath, 'utf8'));
  const lock = JSON.parse(readFileSync(lockPath, 'utf8'));
  for (const name of PRUNE) {
    delete pkg.dependencies?.[name];
    delete lock.packages?.['']?.dependencies?.[name];
    delete lock.packages?.[`node_modules/${name}`];
  }
  writeFileSync(pkgPath, `${JSON.stringify(pkg, null, 2)}\n`);
  writeFileSync(lockPath, `${JSON.stringify(lock, null, 2)}\n`);
}

function setup({ ref = 'origin/master' }) {
  if (!existsSync(path.join(UPSTREAM, '.git'))) {
    throw new Error(`no upstream clone at ${UPSTREAM}; see README.md`);
  }
  const sha = execFileSync('git', ['-C', UPSTREAM, 'rev-parse', ref], { encoding: 'utf8' }).trim();
  log(`exporting ${ref} (${sha.slice(0, 7)}) to ${path.relative(ROOT, SRV)}`);

  // Keep node_modules across re-exports when the lockfile is unchanged: the
  // install is the slow part (the Solana dependencies are large).
  const modules = path.join(SRV, 'node_modules');
  const lockPath = path.join(SRV, 'package-lock.json');
  const oldLock = existsSync(lockPath) ? readFileSync(lockPath, 'utf8') : null;
  const keepModules = path.join(HERE, '.node_modules.keep');
  if (existsSync(modules)) execFileSync('mv', [modules, keepModules]);
  rmSync(SRV, { recursive: true, force: true });
  mkdirSync(SRV, { recursive: true });
  execFileSync('sh', ['-c', `git -C "${UPSTREAM}" archive "${sha}" | tar -x -C "${SRV}"`]);
  pruneDependencies();
  const newLock = readFileSync(lockPath, 'utf8');
  if (existsSync(keepModules)) {
    if (oldLock === newLock) execFileSync('mv', [keepModules, modules]);
    else rmSync(keepModules, { recursive: true, force: true });
  }

  if (!existsSync(modules)) {
    log('npm ci (first run, or the lockfile changed)…');
    // --ignore-scripts: postinstall runs the interactive config wizard.
    execFileSync('npm', ['ci', '--ignore-scripts', '--no-audit', '--no-fund'], { cwd: SRV, stdio: 'inherit' });
  }

  const salt = randomBytes(16).toString('hex');
  writeFileSync(path.join(SRV, 'salt.key'), salt);
  writeFileSync(path.join(SRV, 'session.key'), randomBytes(4096));
  writeFileSync(path.join(SRV, 'config.json'), JSON.stringify({
    adminTrip: trip(ADMIN_PASS, salt),
    globalMods: [{ trip: trip(MOD_PASS, salt) }],
    publicChannels: [],
    permissions: [],
  }));
  writeFileSync(path.join(SRV, '.phantom-ref'), `${ref} ${sha}\n`);
  log(`ready: admin pass "${ADMIN_PASS}", global mod pass "${MOD_PASS}"`);
}

// --- server process -----------------------------------------------------

function writeServerConfig({ serverPort, strict }) {
  writeFileSync(path.join(SRV, '.hcserver.json'), JSON.stringify({
    modulesPath: './commands',
    websocketPort: String(serverPort),
    // The real limits, or effectively none. Relaxed is the default because
    // every test connects from one address and would otherwise share — and
    // exhaust — a single 25-point budget.
    rateLimit: { halflife: '30000', threshold: strict ? '25' : '100000' },
    pulseSpeed: '16000',
  }));
}

function startServer() {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, ['main.mjs'], { cwd: SRV, stdio: ['ignore', 'pipe', 'pipe'] });
    let ready = false;
    const onData = (buf) => {
      const text = String(buf);
      for (const line of text.split('\n').filter(Boolean)) log('server:', line);
      if (!ready && text.includes('Websocket server ready')) { ready = true; resolve(child); }
    };
    child.stdout.on('data', onData);
    child.stderr.on('data', onData);
    child.on('exit', (code) => { if (!ready) reject(new Error(`server exited (${code}) before ready`)); });
  });
}

// --- proxy --------------------------------------------------------------

/**
 * Every connection through the proxy comes from 127.0.0.1, and the server keys
 * both the rate limiter and the user `hash` (what muzzle and ban act on) by
 * address. So one test user muzzled would be all of them. A client connecting
 * to `/?as=<name>` is given a stable address of its own instead, via the
 * X-Forwarded-For the server trusts; the same name always maps to the same
 * address, as a phone reconnecting would.
 */
function withAddress(head) {
  const text = head.toString('latin1');
  const firstLine = text.slice(0, text.indexOf('\r\n'));
  const target = firstLine.split(' ')[1] ?? '/';
  const name = new URL(target, 'http://x').searchParams.get('as');
  if (!name) return head;
  const b = createHash('sha256').update(name).digest();
  const address = `10.${b[0]}.${b[1]}.${b[2]}`;
  const at = firstLine.length + 2;
  return Buffer.concat([
    Buffer.from(text.slice(0, at), 'latin1'),
    Buffer.from(`X-Forwarded-For: ${address}\r\n`, 'latin1'),
    head.subarray(Buffer.byteLength(text.slice(0, at), 'latin1')),
  ]);
}

/**
 * Plain TCP relay. Each client connection is tracked so it can be frozen:
 * both directions stop relaying but neither socket is closed, leaving the
 * server holding an open, silent connection — a ghost.
 */
function startProxy({ clientPort, serverPort }) {
  const links = new Set();
  const server = net.createServer((client) => {
    const upstream = net.connect(serverPort, '127.0.0.1');
    const link = { client, upstream, frozen: false, since: Date.now(), head: Buffer.alloc(0), headDone: false };
    links.add(link);
    client.on('data', (d) => {
      if (link.frozen) return;
      if (link.headDone) { upstream.write(d); return; }
      // Hold the HTTP upgrade request until it is complete, then rewrite it.
      link.head = Buffer.concat([link.head, d]);
      const end = link.head.indexOf('\r\n\r\n');
      if (end < 0) return;
      link.headDone = true;
      upstream.write(withAddress(link.head));
    });
    upstream.on('data', (d) => { if (!link.frozen) client.write(d); });
    const end = () => {
      links.delete(link);
      client.destroy();
      upstream.destroy();
    };
    client.on('close', () => {
      // A frozen link outlives its client: that is the point of freezing.
      if (link.frozen) client.destroy();
      else end();
    });
    upstream.on('close', end);
    client.on('error', () => {});
    upstream.on('error', () => {});
  });
  server.listen(clientPort);
  return {
    links,
    /** Freeze every live link (or the newest [n]); returns how many. */
    freeze(n) {
      const live = [...links].filter((l) => !l.frozen).sort((a, b) => a.since - b.since);
      const pick = n ? live.slice(-n) : live;
      for (const l of pick) {
        l.frozen = true;
        // The client side goes away as a dropped phone would; the server side stays.
        l.client.destroy();
      }
      return pick.length;
    },
    /** Close frozen links, so the server finally sees them go. */
    release() {
      const frozen = [...links].filter((l) => l.frozen);
      for (const l of frozen) { links.delete(l); l.upstream.destroy(); }
      return frozen.length;
    },
    close() {
      for (const l of links) { l.client.destroy(); l.upstream.destroy(); }
      links.clear();
      server.close();
    },
  };
}

// --- admin sockets ------------------------------------------------------

const require = createRequire(path.join(SRV, 'package.json'));

/**
 * One admin connection per channel, kept open: most mod commands require the
 * sender to be in the channel, and the lock/captcha/password state they set is
 * per channel. It connects to the server directly, not through the proxy, so
 * freezing never touches it, and under its own X-Forwarded-For so it never
 * spends the tests' rate budget.
 */
class Admins {
  constructor(serverPort) {
    this.serverPort = serverPort;
    this.byChannel = new Map();
  }

  async get(channel, pass = ADMIN_PASS, nick = 'phantomAdmin') {
    const key = `${channel}\u0000${pass}`;
    const have = this.byChannel.get(key);
    if (have && have.ws.readyState === 1) return have;
    const WebSocket = require('ws');
    const ws = new WebSocket(`ws://127.0.0.1:${this.serverPort}`, {
      headers: { 'X-Forwarded-For': `10.255.0.${this.byChannel.size + 1}` },
    });
    const conn = { ws, frames: [] };
    ws.on('message', (d) => { try { conn.frames.push(JSON.parse(d)); } catch { /* ignore */ } });
    await new Promise((res, rej) => { ws.once('open', res); ws.once('error', rej); });
    // v2 handshake: session first, then join.
    ws.send(JSON.stringify({ cmd: 'session' }));
    ws.send(JSON.stringify({ cmd: 'join', channel, nick, pass }));
    await waitFor(conn, (f) => f.cmd === 'onlineSet', 3000, `admin join ?${channel}`);
    this.byChannel.set(key, conn);
    return conn;
  }

  /** Sends [frame] (channel filled in) and returns what came back within [settle] ms. */
  async send(channel, frame, { settle = 400, pass } = {}) {
    const conn = await this.get(channel, pass);
    const mark = conn.frames.length;
    conn.ws.send(JSON.stringify({ channel, ...frame }));
    await sleep(settle);
    return conn.frames.slice(mark);
  }

  closeAll() {
    for (const c of this.byChannel.values()) c.ws.terminate();
    this.byChannel.clear();
  }
}

function waitFor(conn, pred, ms, label) {
  return new Promise((res, rej) => {
    const start = Date.now();
    const tick = () => {
      const hit = conn.frames.find(pred);
      if (hit) return res(hit);
      const warn = conn.frames.find((f) => f.cmd === 'warn');
      if (warn) return rej(new Error(`${label}: ${warn.text}`));
      if (Date.now() - start > ms) return rej(new Error(`${label}: timed out`));
      setTimeout(tick, 25);
    };
    tick();
  });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// --- control API --------------------------------------------------------

/**
 * Shorthand verbs for the common admin actions, mapped to real frames. Anything
 * else goes through `frame` verbatim.
 */
const VERBS = {
  // Without a level, lockroom locks at the sender's own — for the admin, everyone.
  // Pass a number or a label from _UAC.js levels, e.g. "channelOwner".
  lock: (a) => ({ cmd: 'lockroom', ...(a.level !== undefined ? { level: a.level } : {}) }),
  unlock: () => ({ cmd: 'unlockroom' }),
  captcha: () => ({ cmd: 'enablecaptcha' }),
  nocaptcha: () => ({ cmd: 'disablecaptcha' }),
  password: (a) => ({ cmd: 'setpassword', channelPassword: a.password }),
  nopassword: () => ({ cmd: 'clearpassword' }),
  kick: (a) => ({ cmd: 'kick', ...target(a) }),
  ban: (a) => ({ cmd: 'ban', ...target(a) }),
  mute: (a) => ({ cmd: 'dumb', ...target(a) }),
  unmute: (a) => ({ cmd: 'speak', hash: a.hash }),
  flair: (a) => ({ cmd: 'forceflair', nick: a.nick, flair: a.flair }),
  color: (a) => ({ cmd: 'forcecolor', nick: a.nick, color: a.color }),
  say: (a) => ({ cmd: 'chat', text: a.text }),
};

function target(a) {
  return a.userid !== undefined ? { userid: Number(a.userid) } : { nick: a.nick };
}

function readBody(req) {
  return new Promise((res) => {
    let s = '';
    req.on('data', (d) => { s += d; });
    req.on('end', () => { try { res(s ? JSON.parse(s) : {}); } catch { res({}); } });
  });
}

function startControl({ controlPort, proxy, admins, restart, info }) {
  const server = http.createServer(async (req, res) => {
    const reply = (code, body) => {
      res.writeHead(code, { 'content-type': 'application/json' });
      res.end(JSON.stringify(body));
    };
    try {
      const url = new URL(req.url, 'http://x');
      const body = req.method === 'POST' ? await readBody(req) : {};
      switch (`${req.method} ${url.pathname}`) {
        case 'GET /health':
          return reply(200, { ...info(), links: proxy.links.size });
        case 'POST /admin': {
          const { channel, verb, frame, pass, settle, ...args } = body;
          if (!channel) return reply(400, { error: 'channel required' });
          const out = frame ?? VERBS[verb]?.(args);
          if (!out) return reply(400, { error: `unknown verb ${verb}`, verbs: Object.keys(VERBS) });
          const frames = await admins.send(channel, out, { settle, pass });
          return reply(200, { sent: out, frames });
        }
        case 'POST /freeze':
          return reply(200, { frozen: proxy.freeze(body.n) });
        case 'POST /release':
          return reply(200, { released: proxy.release() });
        case 'POST /reset':
          await restart(body);
          return reply(200, info());
        default:
          return reply(404, { error: 'not found' });
      }
    } catch (e) {
      return reply(500, { error: String(e.message ?? e) });
    }
  });
  server.listen(controlPort);
  return server;
}

// --- serve --------------------------------------------------------------

async function serve(opts) {
  if (!existsSync(path.join(SRV, 'config.json'))) throw new Error('not set up; run: node phantom.mjs setup');
  const clientPort = Number(opts.port ?? 6070);
  const serverPort = Number(opts['server-port'] ?? 6071);
  const controlPort = Number(opts['control-port'] ?? 6079);
  let strict = Boolean(opts.strict);

  let child;
  const admins = new Admins(serverPort);
  const boot = async () => {
    writeServerConfig({ serverPort, strict });
    child = await startServer();
  };
  await boot();
  const proxy = startProxy({ clientPort, serverPort });

  const ref = readFileSync(path.join(SRV, '.phantom-ref'), 'utf8').trim();
  const info = () => ({
    url: `ws://127.0.0.1:${clientPort}`, control: `http://127.0.0.1:${controlPort}`, ref, strict,
  });
  /** A fresh server: all channels, locks, bans and rate scores forgotten. */
  const restart = async (body = {}) => {
    if (body.strict !== undefined) strict = Boolean(body.strict);
    admins.closeAll();
    proxy.close();
    const exited = new Promise((r) => child.once('exit', r));
    child.kill();
    await exited;
    await boot();
    Object.assign(proxy, startProxy({ clientPort, serverPort }));
  };
  startControl({ controlPort, proxy, admins, restart, info });

  // One machine-readable line on stdout, for whatever launched us.
  console.log(JSON.stringify({ ready: true, ...info() }));

  const shutdown = () => { admins.closeAll(); proxy.close(); child.kill(); process.exit(0); };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}

// --- admin CLI ----------------------------------------------------------

async function adminCli(opts) {
  const [channel, verb, json] = opts._;
  if (!channel || !verb) throw new Error(`usage: admin <channel> <verb> [json]; verbs: ${Object.keys(VERBS).join(' ')}`);
  const controlPort = Number(opts['control-port'] ?? 6079);
  const body = JSON.stringify({ channel, verb, ...(json ? JSON.parse(json) : {}) });
  const res = await fetch(`http://127.0.0.1:${controlPort}/admin`, { method: 'POST', body });
  console.log(JSON.stringify(await res.json(), null, 2));
}

// --- main ---------------------------------------------------------------

const [command, ...rest] = process.argv.slice(2);
const opts = parseArgs(rest);
try {
  if (command === 'setup') setup(opts);
  else if (command === 'serve') await serve(opts);
  else if (command === 'admin') await adminCli(opts);
  else {
    console.error('usage: phantom.mjs setup [--ref <git ref>] | serve [--strict] [--port 6070] | admin <channel> <verb> [json]');
    process.exit(2);
  }
} catch (e) {
  log('error:', e.message);
  process.exit(1);
}
