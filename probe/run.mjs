#!/usr/bin/env node
/**
 * Protocol spike scenarios against a live hack.chat server.
 *
 *   node probe/run.mjs help [--all]
 *   node probe/run.mjs dialect
 *   node probe/run.mjs resume [--delay=ms]
 *   node probe/run.mjs features
 *   node probe/run.mjs all
 *
 * Every scenario uses a random channel so we never disturb a real room, and all
 * sends route through the local mirror of the server's rate limiter.
 */
import {
  Client, COST, governor, sleep, log, resetClock, randChannel,
} from './hcclient.mjs';

const args = process.argv.slice(2);
const scenario = args[0] ?? 'help';
const flag = (name, def = null) => {
  const hit = args.find((a) => a.startsWith(`--${name}`));
  if (!hit) return def;
  const [, v] = hit.split('=');
  return v ?? true;
};
const URL = flag('url', 'wss://hack.chat/chat-ws');

const heading = (s) => console.log(`\n${'='.repeat(72)}\n  ${s}\n${'='.repeat(72)}`);

/* ------------------------------------------------------------------ *
 * help: what does the LIVE server actually run?
 * ------------------------------------------------------------------ */
async function scenarioHelp() {
  heading('SURFACE — live command list & per-command source hashes');
  const c = new Client('probe', { url: URL, protocol: 2 });
  await c.connect();
  await c.handshake({ channel: randChannel(), nick: `probe${rand()}` });

  await c.send({ cmd: 'help' }, { cost: 2 });
  const all = await c.waitFor((f) => f.cmd === 'info' && /All commands/.test(f.text ?? ''), { label: 'help listing' });
  console.log(`\n--- live server help output ---\n${all.text}\n`);

  const names = [...(all.text.matchAll(/^\|[^|]+:\|(.+)\|$/gm))]
    .flatMap((m) => m[1].split(',').map((s) => s.trim()))
    .filter(Boolean);
  console.log(`parsed ${names.length} commands: ${names.join(', ')}\n`);

  // srcHash per command tells us precisely where live diverges from ./hc.
  // Each help call costs 2, so a full sweep is opt-in and slow by design.
  const probeList = flag('all') ? names : ['session', 'join', 'chat', 'updateMessage', 'whisper', 'invite'];
  const hashes = {};
  for (const name of probeList) {
    await c.send({ cmd: 'help', command: name }, { cost: 2 });
    try {
      const info = await c.waitFor((f) => f.cmd === 'info' && (f.text ?? '').includes(`# ${name} command`), { label: `help ${name}` });
      const hash = /\*\*Hash:\*\*\|([^|]*)\|/.exec(info.text)?.[1]?.trim() ?? '?';
      const usage = /\*\*Usage:\*\* ([\s\S]*)$/.exec(info.text)?.[1]?.trim() ?? '';
      hashes[name] = { hash, usage };
      log('probe', `${name.padEnd(16)} srcHash=${hash}`);
    } catch (e) {
      log('probe', `${name}: ${e.message}`);
    }
  }
  console.log('\n--- srcHash table (compare across deploys / to ./hc) ---');
  console.table(hashes);
  c.ws.close();
}

/* ------------------------------------------------------------------ *
 * dialect: v1 vs v2, observed side by side
 * ------------------------------------------------------------------ */
async function scenarioDialect() {
  heading('DIALECT — hcProtocol 1 vs 2 seen from both sides');
  const channel = randChannel('dia');
  const nickV2 = `modern${rand()}`;
  const nickV1 = `legacy${rand()}`;

  const v2 = new Client('v2', { url: URL, protocol: 2 });
  await v2.connect();
  await v2.handshake({ channel, nick: nickV2 });

  const v1 = new Client('v1', { url: URL, protocol: 1 });
  await v1.connect();
  const v1join = await v1.handshake({ channel, nick: nickV1 });

  // Does a v1 socket ever get a session token? (join.js replies with one.)
  log('v1', `token after legacy join: ${v1.token ? 'YES' : 'no'}`);

  // v2 targets users by userid and must name the channel explicitly;
  // findUsers() matches on payload.channel, which v1 gets auto-filled but v2
  // does not. Pull the ids out of the peer list.
  const idOf = (nick) => v1join.onlineSet.users.find((u) => u.nick === nick)?.userid
    ?? v2.frames.flatMap((f) => f.users ?? []).find((u) => u.nick === nick)?.userid
    ?? v2.frames.find((f) => f.cmd === 'onlineAdd' && f.nick === nick)?.userid;
  const idV1 = idOf(nickV1);
  const idV2 = idOf(nickV2);
  log('probe', `resolved userids: ${nickV1}=${idV1} ${nickV2}=${idV2}`);

  const cases = [
    ['whisper v2->v1', v2, { cmd: 'whisper', channel, userid: idV1, text: 'ping from v2' }],
    ['whisper v1->v2', v1, { cmd: 'whisper', nick: nickV2, text: 'ping from v1' }],
    ['invite v2->v1', v2, { cmd: 'invite', channel, userid: idV1 }],
    ['invite v1->v2', v1, { cmd: 'invite', nick: nickV2, channel }],
    ['emote v2', v2, { cmd: 'emote', text: 'waves in v2' }],
    ['emote v1', v1, { cmd: 'emote', text: 'waves in v1' }],
  ];

  const report = [];
  for (const [label, sender, payload] of cases) {
    const m1 = v1.mark(); const m2 = v2.mark();
    await sender.send(payload);
    await sleep(1200);
    const fmt = (fs) => fs.map((f) => {
      const kind = `${f.cmd}${f.type ? `/${f.type}` : ''}`;
      return f.cmd === 'warn' ? `${kind}(${f.id}: ${(f.text ?? '').split('\n')[0]})` : kind;
    }).join(' ') || '—';
    report.push({ case: label, 'v1 saw': fmt(v1.since(m1)), 'v2 saw': fmt(v2.since(m2)) });
  }
  console.log('\n--- how each dialect receives the same events ---');
  console.table(report);

  // changenick is explicitly branched on hcProtocol in changenick.js:121
  const m1 = v1.mark(); const m2 = v2.mark();
  await v2.send({ cmd: 'changenick', nick: `${nickV2}x` });
  await sleep(1200);
  console.log('\nchangenick by v2 —');
  console.log('  v1 saw:', JSON.stringify(v1.since(m1)));
  console.log('  v2 saw:', JSON.stringify(v2.since(m2)));

  v1.ws.close(); v2.ws.close();
}

/* ------------------------------------------------------------------ *
 * resume: is reconnect actually silent to peers?
 * ------------------------------------------------------------------ */
async function scenarioResume() {
  heading('RESUME — what a peer sees when we drop and restore');
  const delay = Number(flag('delay', 3000));
  const channel = randChannel('res');
  const nick = `actor${rand()}`;

  // Observer joins first and never moves: it is our ground truth for peer view.
  const obs = new Client('observer', { url: URL, protocol: 2 });
  await obs.connect();
  await obs.handshake({ channel, nick: `watcher${rand()}` });

  const actor = new Client('actor', { url: URL, protocol: 2 });
  await actor.connect();
  await actor.handshake({ channel, nick });
  const token = actor.token;
  if (!token) throw new Error('no session token issued on join');

  await actor.send({ cmd: 'chat', text: 'before the drop' }, { cost: COST.chat });
  await sleep(800);

  const mark = obs.mark();
  actor.kill();
  log('probe', `waiting ${delay}ms before restoring (ghost-reap race)`);
  await sleep(delay);

  const actor2 = new Client('actor2', { url: URL, protocol: 2 });
  await actor2.connect();
  const res = await actor2.handshake({ channel, nick, token });
  await sleep(1500);

  const seen = obs.since(mark);
  console.log(`\n--- observer's view across a ${delay}ms outage ---`);
  for (const f of seen) console.log(`  +${(f.at / 1000).toFixed(2)}s  ${f.cmd}${f.online !== undefined ? ` online=${f.online}` : ''}  ${f.nick ?? ''}`);

  const noisy = seen.filter((f) => f.cmd === 'onlineRemove' || f.cmd === 'onlineAdd');
  console.log(`\n  restored:        ${res.restored}`);
  console.log(`  peer-visible:    ${noisy.length ? noisy.map((f) => f.cmd).join(', ') : 'NONE — silent resume'}`);
  console.log(`  updateUser only: ${seen.some((f) => f.cmd === 'updateUser') && !noisy.length}`);

  // Prove identity continuity: same userid means messages stitch together.
  const before = obs.frames.find((f) => f.cmd === 'chat' && f.text === 'before the drop');
  await actor2.send({ cmd: 'chat', text: 'after the restore' }, { cost: COST.chat });
  const after = await obs.waitFor((f) => f.cmd === 'chat' && f.text === 'after the restore', { label: 'post-restore chat' });
  console.log(`  userid before:   ${before?.userid}`);
  console.log(`  userid after:    ${after.userid}  ${before?.userid === after.userid ? '(continuous)' : '(CHANGED)'}`);
  console.log(`  trip continuous: ${before?.trip === after.trip}`);

  obs.ws.close(); actor2.ws.close();
}

/* ------------------------------------------------------------------ *
 * overlap: make-before-break. Does restoring while the old socket still
 * lives avoid the onlineRemove/onlineAdd pair? And what happens to peers
 * when the now-redundant old socket finally closes?
 * ------------------------------------------------------------------ */
async function scenarioOverlap() {
  heading('OVERLAP — make-before-break reconnect');
  const channel = randChannel('ovl');
  const nick = `actor${rand()}`;

  const obs = new Client('observer', { url: URL, protocol: 2 });
  await obs.connect();
  await obs.handshake({ channel, nick: `watcher${rand()}` });

  const oldSock = new Client('old', { url: URL, protocol: 2 });
  await oldSock.connect();
  await oldSock.handshake({ channel, nick });
  const token = oldSock.token;
  await sleep(800);

  // Phase 1: bring up the replacement while the old socket is still open.
  const m1 = obs.mark();
  const newSock = new Client('new', { url: URL, protocol: 2 });
  await newSock.connect();
  await newSock.handshake({ channel, nick, token });
  await sleep(1500);
  const p1 = obs.since(m1);
  console.log('\n  phase 1 — restore while old socket alive:');
  for (const f of p1) console.log(`    ${f.cmd}${f.online !== undefined ? ` online=${f.online}` : ''} ${f.nick ?? ''}`);
  console.log(`    => ${p1.some((f) => f.cmd === 'onlineAdd') ? 'onlineAdd (NOISY)' : 'no join notice'}`);

  // Phase 2: now retire the old socket. Does the server tell peers we left,
  // even though we are still present on the new socket?
  const m2 = obs.mark();
  oldSock.kill();
  await sleep(2500);
  const p2 = obs.since(m2);
  console.log('\n  phase 2 — old socket closes (we are still connected):');
  for (const f of p2) console.log(`    ${f.cmd}${f.online !== undefined ? ` online=${f.online}` : ''} ${f.nick ?? ''}`);
  const falseLeave = p2.some((f) => f.cmd === 'onlineRemove');
  console.log(`    => ${falseLeave ? 'onlineRemove — peers think we LEFT while still online' : 'no leave notice'}`);

  // Phase 3: are we actually still functional on the new socket?
  const m3 = obs.mark();
  await newSock.send({ cmd: 'chat', text: 'still here after overlap' }, { cost: COST.chat });
  const got = await obs.waitFor((f) => f.cmd === 'chat' && f.text === 'still here after overlap', { label: 'post-overlap chat' })
    .catch(() => null);
  console.log(`\n  phase 3 — still able to speak: ${got ? 'YES' : 'NO'}`);
  if (got && falseLeave) console.log('    (peer user-list is now wrong: we are absent from it but talking)');
  void m3;

  obs.ws.close(); newSock.ws.close();
}

/* ------------------------------------------------------------------ *
 * multichannel: public join.js rejects a second channel per socket, but
 * live `join` diverges from source. Settle it — this decides whether the
 * connection layer is one socket or N.
 * ------------------------------------------------------------------ */
async function scenarioMultichannel() {
  heading('MULTICHANNEL — can one socket hold two channels on live?');
  const chA = randChannel('mc-a');
  const chB = randChannel('mc-b');
  const nick = `multi${rand()}`;

  const c = new Client('multi', { url: URL, protocol: 2 });
  await c.connect();
  await c.handshake({ channel: chA, nick });
  log('probe', `joined ${chA}; now attempting ${chB} on the SAME socket`);

  const m = c.mark();
  await c.send({ cmd: 'join', channel: chB, nick }, { cost: COST.join });
  await sleep(2500);
  const got = c.since(m);
  for (const f of got) console.log(`    ${JSON.stringify(f).slice(0, 220)}`);

  const warned = got.find((f) => f.cmd === 'warn');
  const joined = got.find((f) => f.cmd === 'onlineSet' && f.channel === chB);
  console.log(`\n  second join accepted: ${joined ? 'YES — live supports multichannel' : 'NO'}`);
  if (warned) console.log(`  rejected with: id=${warned.id} "${warned.text}"`);

  // Whatever happened, the token's channel list is the source of truth for
  // what a restore will bring back.
  const tok = c.frames.filter((f) => f.cmd === 'session' && f.token).pop();
  if (tok) console.log(`  token channels: ${JSON.stringify(tok.channels)}`);

  // Can we still speak in the original channel either way?
  const m2 = c.mark();
  await c.send({ cmd: 'chat', text: 'still in channel A?' }, { cost: COST.chat });
  await sleep(1200);
  const echo = c.since(m2).find((f) => f.cmd === 'chat');
  console.log(`  still usable in ${chA}: ${echo ? `YES (echo channel=${echo.channel})` : 'NO'}`);

  c.ws.close();
}

/* ------------------------------------------------------------------ *
 * features: parity surface we must reproduce in the app
 * ------------------------------------------------------------------ */
async function scenarioFeatures() {
  heading('FEATURES — payload shapes for the renderer & state model');
  const channel = randChannel('feat');
  const obs = new Client('observer', { url: URL, protocol: 2 });
  await obs.connect();
  await obs.handshake({ channel, nick: `watcher${rand()}` });

  const me = new Client('actor', { url: URL, protocol: 2 });
  await me.connect();
  await me.handshake({ channel, nick: `actor${rand()}`, pass: `probe-pass-${rand()}` });

  const customId = `probe-${rand()}`;
  const steps = [
    ['plain chat', { cmd: 'chat', text: 'hello **markdown** and $x^2$' }],
    ['chat w/ customId', { cmd: 'chat', text: 'streaming: ', customId }],
    ['updateMessage append', { cmd: 'updateMessage', mode: 'append', text: 'one', customId }],
    ['updateMessage append', { cmd: 'updateMessage', mode: 'append', text: ' two', customId }],
    ['updateMessage complete', { cmd: 'updateMessage', mode: 'complete', text: ' done', customId }],
    ['emote', { cmd: 'emote', text: 'tests an emote' }],
    ['changecolor', { cmd: 'changecolor', color: 'ff8800' }],
    ['katex block', { cmd: 'chat', text: '$$\\int_0^\\infty e^{-x^2}dx = \\frac{\\sqrt{\\pi}}{2}$$' }],
    ['code fence', { cmd: 'chat', text: '```kotlin\nval x = 1\n```' }],
  ];

  for (const [label, payload] of steps) {
    const m = obs.mark();
    await me.send(payload, { cost: payload.cmd === 'chat' ? COST.chat : COST.default });
    await sleep(1100);
    const got = obs.since(m);
    console.log(`\n  ${label}:`);
    for (const f of got) console.log(`    ${JSON.stringify(f)}`);
  }
  obs.ws.close(); me.ws.close();
}

const rand = () => Math.floor(Math.random() * 9999);

const table = {
  help: scenarioHelp,
  dialect: scenarioDialect,
  resume: scenarioResume,
  overlap: scenarioOverlap,
  multichannel: scenarioMultichannel,
  features: scenarioFeatures,
};

async function main() {
  resetClock();
  log('probe', `target ${URL}`);
  const list = scenario === 'all' ? Object.keys(table) : [scenario];
  for (const s of list) {
    if (!table[s]) throw new Error(`unknown scenario: ${s} (have: ${Object.keys(table).join(', ')}, all)`);
    await table[s]();
    if (list.length > 1) { log('probe', 'cooling down between scenarios'); await sleep(8000); }
  }
  log('probe', `done (rate score ~${governor.projected().toFixed(1)}/25)`);
  process.exit(0);
}

main().catch((e) => { console.error('\nFAILED:', e); process.exit(1); });
