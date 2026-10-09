#!/usr/bin/env node
/**
 * legacy — hack.chat as it was in December 2019 (2.1.91), run locally.
 *
 * Some instances never updated past this: no `session`, no userids, whispers
 * and invites as prose. This is what the client's v1 support is tested
 * against (`./gradlew :core:legacyTest`), exported from ../../hc at the last
 * commit of 2019 and run unmodified, through boot.cjs, on today's node.
 *
 * Usage:
 *   node legacy.mjs setup [--ref 11ceb7c]   export + npm ci + config
 *   node legacy.mjs serve [--port 6080] [--strict]   run until killed
 *
 * `serve` prints one JSON line on stdout once the port is listening. The rate
 * limit is relaxed unless `--strict`: 2.1.91 scores every socket from one
 * address together, and tests from one machine would throttle each other.
 */

import { spawn, execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  existsSync, mkdirSync, readFileSync, rmSync, writeFileSync,
} from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '../..');
const UPSTREAM = path.join(ROOT, 'hc');
const SRV = path.join(HERE, 'srv');
const SERVER = path.join(SRV, 'server');

/** "Package updates", 2019-12-01: the last of 2.1.91 before 2020's protocol changes. */
const DEFAULT_REF = '11ceb7c';

/** Fixed, public test passwords; their trips depend on the salt below. */
export const ADMIN_NICK = 'legacyadmin';
export const ADMIN_PASS = 'legacy-admin';
export const MOD_PASS = 'legacy-mod';
const SALT = 'legacy-salt';

const log = (...a) => console.error('[legacy]', ...a);

function parseArgs(argv) {
  const out = { _: [] };
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a.startsWith('--')) {
      const next = argv[i + 1];
      if (next === undefined || next.startsWith('--')) out[a.slice(2)] = true;
      else { out[a.slice(2)] = next; i += 1; }
    } else out._.push(a);
  }
  return out;
}

/** join.js's hash: sha256(password + salt), base64, first six. */
const trip = (pass) => createHash('sha256').update(pass + SALT).digest('base64').substr(0, 6);

function setup({ ref = DEFAULT_REF }) {
  if (!existsSync(UPSTREAM)) throw new Error(`no ${UPSTREAM}; clone hack-chat/main there (see README.md)`);
  rmSync(SRV, { recursive: true, force: true });
  mkdirSync(SRV, { recursive: true });
  log(`exporting ${ref}`);
  const archive = execFileSync('git', ['-C', UPSTREAM, 'archive', ref, 'server'], { maxBuffer: 64 << 20 });
  execFileSync('tar', ['-x', '-C', SRV], { input: archive });
  log('npm ci');
  execFileSync('npm', ['ci', '--ignore-scripts', '--no-audit', '--no-fund'], { cwd: SERVER, stdio: 'inherit' });
  writeConfig(6080);
}

function writeConfig(port) {
  mkdirSync(path.join(SERVER, 'config'), { recursive: true });
  writeFileSync(path.join(SERVER, 'config/config.json'), JSON.stringify({
    tripSalt: SALT,
    adminName: ADMIN_NICK,
    adminTrip: trip(ADMIN_PASS),
    websocketPort: port,
    mods: [{ trip: trip(MOD_PASS) }],
  }, null, 2));
}

function waitForPort(port, child, ms = 15_000) {
  const deadline = Date.now() + ms;
  return new Promise((resolve, reject) => {
    const attempt = () => {
      if (child.exitCode !== null) return reject(new Error(`server exited with ${child.exitCode}`));
      const s = net.connect(port, '127.0.0.1');
      s.once('connect', () => { s.destroy(); resolve(); });
      s.once('error', () => {
        s.destroy();
        if (Date.now() > deadline) reject(new Error(`port ${port} never opened`));
        else setTimeout(attempt, 100);
      });
    };
    attempt();
  });
}

/** The real threshold is 25 (utility/Constants.js); relaxed, it never trips. */
function setThreshold(strict) {
  const file = path.join(SERVER, 'src/utility/Constants.js');
  const source = readFileSync(file, 'utf8');
  writeFileSync(file, source.replace(/threshold: \d+/, `threshold: ${strict ? 25 : 100000}`));
}

async function serve({ port = '6080', strict = false }) {
  if (!existsSync(path.join(SERVER, 'node_modules'))) throw new Error('not set up; run: node legacy.mjs setup');
  const p = Number(port);
  writeConfig(p);
  setThreshold(strict === true);
  const child = spawn(process.execPath, [
    // Every source file is an ES module in a package that does not say so;
    // node notices, and says so once per file, which is the whole log.
    '--disable-warning=MODULE_TYPELESS_PACKAGE_JSON',
    '--require', path.join(HERE, 'boot.cjs'),
    'main.js',
  ], {
    cwd: SERVER,
    stdio: ['ignore', 'inherit', 'inherit'],
  });
  const stop = () => { child.kill(); process.exit(0); };
  process.on('SIGINT', stop);
  process.on('SIGTERM', stop);
  child.on('exit', (code) => process.exit(code ?? 1));
  await waitForPort(p, child);
  process.stdout.write(`${JSON.stringify({ ready: true, url: `ws://127.0.0.1:${p}` })}\n`);
}

const [command] = process.argv.slice(2);
const opts = parseArgs(process.argv.slice(3));
try {
  if (command === 'setup') setup(opts);
  else if (command === 'serve') await serve(opts);
  else {
    console.error('usage: legacy.mjs setup [--ref <git ref>] | serve [--port 6080] [--strict]');
    process.exit(2);
  }
} catch (e) {
  log(e.message);
  process.exit(1);
}
