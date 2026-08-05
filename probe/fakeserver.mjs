#!/usr/bin/env node
/**
 * A deliberately misbehaving hack.chat server, for testing client paths that
 * the real server will not produce on demand.
 *
 *   node probe/fakeserver.mjs --port 6060 --mode silent
 *
 * Modes:
 *   normal  — echo chat back like the real server (sanity check)
 *   silent  — accept chat and never echo it (exercises Unconfirmed)
 *   drop    — accept the handshake, then close the socket after N seconds
 *   restore — honour session tokens, reinstating the identity they were issued
 *             for. This is the one the real server does and the fake one could
 *             not: a restore *overrides* the join that would have followed, so
 *             it is the only way to reproduce a client coming back as whoever
 *             it was here last time rather than as the nick the user typed.
 *
 * `--level N` sets the level this server claims you have, so client-side
 * permission gating can be exercised without needing real moderator rights
 * (999999 = moderator, 9999 = channel moderator). A second user is always
 * present in the roster to act as a target.
 *
 * Point the app at it via Settings → Server, e.g. `10.0.2.2:6060` from the
 * Android emulator (10.0.2.2 is the emulator's route to the host).
 *
 * It implements only enough of the protocol for a client to join: the v2
 * handshake, onlineSet, the MOTD, and the session token that trails them.
 */
import { WebSocketServer } from 'ws';

const arg = (name, fallback) => {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 ? process.argv[i + 1] : fallback;
};

const port = Number(arg('port', 6060));
const mode = arg('mode', 'silent');
const level = Number(arg('level', 100));
const dropAfter = Number(arg('drop-after', 15)) * 1000;

/*
 * The flair table from hc/commands/utility/_UAC.js#levelAppearance. An
 * undecorated level gets `flair: false` — not null, not absent — which is
 * exactly the shape a client is most likely to mishandle, so it is reproduced
 * verbatim rather than simplified.
 */
const flairFor = (lvl) => {
  if (lvl >= 9999999) return String.fromCodePoint(127775);  // admin      🌟
  if (lvl >= 999999) return String.fromCodePoint(11088);    // moderator  ⭐
  if (lvl >= 99999) return String.fromCodePoint(128081);    // owner      👑
  if (lvl >= 9999) return String.fromCodePoint(128171);     // ch-mod     💫
  if (lvl === 99) return String.fromCodePoint(129302);      // bot        🤖
  return false;
};

/**
 * Not hack.chat's algorithm — it salts server-side and we have no salt. Only
 * the property the client depends on is reproduced: stable per password.
 */
const fakeTrip = (pass) => {
  let h = 0;
  for (const c of pass) h = (h * 31 + c.codePointAt(0)) >>> 0;
  return h.toString(36).padStart(6, '0').slice(0, 6);
};

/** The roster, identical whether we got here by join or by restore. */
const onlineSet = (state, channel) => ({
  cmd: 'onlineSet',
  nicks: [state.nick, 'victim', 'starlord'],
  users: [
    { isme: true, nick: state.nick, userid: state.userid, trip: state.trip ?? '', uType: 'user', level, hash: 'selfhash', color: '5e89ed', flair: flairFor(level), channel },
    // A target to moderate; the real server would have sent one too.
    { isme: false, nick: 'victim', userid: 4242, trip: '', uType: 'user', level: 100, hash: 'victimhash', color: 'ed5e5e', flair: false, channel },
    // An admin, so a decorated flair is on screen even at --level 100.
    { isme: false, nick: 'starlord', userid: 4243, trip: 'aBc12', uType: 'user', level: 9999999, hash: 'adminhash', color: 'd73737', flair: flairFor(9999999), channel },
  ],
  channel,
});

const wss = new WebSocketServer({ port });
let nextUserid = 1000;

/**
 * Trips earned per identity, so a restore reinstates the *whole* identity the
 * way the real server does. Leaving this out was not a harmless simplification:
 * the restored roster reported an empty trip, and a client that believed it
 * dropped the trip it had already learned.
 */
const trips = new Map();
const tripKey = (nick, channel) => `${nick}:${channel}`;

console.log(`fake hack.chat on ws://0.0.0.0:${port}  mode=${mode}  level=${level}`);
console.log(`  emulator: use 10.0.2.2:${port}`);

wss.on('connection', (ws) => {
  const state = { userid: nextUserid++, nick: null, channel: null, trip: '' };
  const send = (obj) => ws.send(JSON.stringify({ ...obj, time: Date.now() }));
  const log = (m) => console.log(`  [${state.nick ?? '?'}] ${m}`);

  ws.on('message', (raw) => {
    let p;
    try { p = JSON.parse(raw.toString()); } catch { return; }
    log(`>> ${JSON.stringify(p).slice(0, 120)}`);

    if (p.cmd === 'session') {
      // Tokens are `restore:<nick>:<channel>` so the identity inside one is
      // legible in the logs. The real server's are opaque JWTs; what matters
      // here is only that a token *carries* an identity and outranks `join`.
      const held = mode === 'restore' && typeof p.token === 'string'
        ? /^restore:([^:]+):(.+)$/.exec(p.token)
        : null;
      if (held) {
        const [, nick, channel] = held;
        state.nick = nick;
        state.channel = channel;
        state.trip = trips.get(tripKey(nick, channel)) ?? '';
        log(`restoring as ${nick}${state.trip ? `#${state.trip}` : ''} in ${channel} — the join, if any, is ignored`);
        send({ cmd: 'session', restored: true, token: p.token, channels: [channel] });
        send(onlineSet(state, channel));
        return;
      }
      send({ cmd: 'session', restored: false, token: '', channels: [] });
      return;
    }

    if (p.cmd === 'join') {
      state.nick = p.nick;
      state.channel = p.channel;
      // A trip password produces a trip, as it would on the real server — the
      // derivation is not the real one, only its observable consequence: the
      // same password gives the same trip, a different one gives a different
      // trip, and no password gives none.
      state.trip = p.pass ? fakeTrip(p.pass) : '';
      trips.set(tripKey(p.nick, p.channel), state.trip);
      send(onlineSet(state, p.channel));
      send({ cmd: 'info', text: `fake server, mode=${mode}`, id: 1304, channel: p.channel });
      // The real server sends the token *after* onlineSet and the MOTD; keep
      // that ordering so clients that get it wrong fail here too.
      send({ cmd: 'session', restored: false, token: `restore:${p.nick}:${p.channel}`, channels: [p.channel] });

      // A decorated peer talking, so flair rendering is observable without a
      // second client and without holding real moderator rights on live.
      setTimeout(() => {
        send({
          cmd: 'chat',
          nick: 'starlord',
          userid: 4243,
          text: 'flair should render before the trip',
          channel: p.channel,
          level: 9999999,
          trip: 'aBc12',
          color: 'd73737',
          flair: flairFor(9999999),
          id: 1,
        });
      }, 400);

      if (mode === 'drop') {
        setTimeout(() => { log('closing socket'); ws.close(); }, dropAfter);
      }
      return;
    }

    if (['kick', 'ban', 'dumb', 'speak'].includes(p.cmd)) {
      log(`MOD COMMAND ${p.cmd}: ${JSON.stringify(p)}`);
      send({ cmd: 'info', text: `${p.cmd} accepted`, id: 1, channel: state.channel });
      return;
    }

    if (p.cmd === 'chat') {
      if (mode === 'silent') {
        log('swallowing chat (no echo) — client should mark it Unconfirmed');
        return;
      }
      send({
        cmd: 'chat',
        nick: state.nick,
        userid: state.userid,
        text: p.text,
        channel: state.channel,
        level,
        trip: state.trip,
        flair: flairFor(level),
        customId: p.customId,
        id: Math.floor(Math.random() * 999999),
      });
    }
  });

  ws.on('close', () => log('disconnected'));
});
