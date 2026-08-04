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
const dropAfter = Number(arg('drop-after', 15)) * 1000;

const wss = new WebSocketServer({ port });
let nextUserid = 1000;

console.log(`fake hack.chat on ws://0.0.0.0:${port}  mode=${mode}`);
console.log(`  emulator: use 10.0.2.2:${port}`);

wss.on('connection', (ws) => {
  const state = { userid: nextUserid++, nick: null, channel: null };
  const send = (obj) => ws.send(JSON.stringify({ ...obj, time: Date.now() }));
  const log = (m) => console.log(`  [${state.nick ?? '?'}] ${m}`);

  ws.on('message', (raw) => {
    let p;
    try { p = JSON.parse(raw.toString()); } catch { return; }
    log(`>> ${JSON.stringify(p).slice(0, 120)}`);

    if (p.cmd === 'session') {
      // Never restore: this server issues fresh identities only.
      send({ cmd: 'session', restored: false, token: '', channels: [] });
      return;
    }

    if (p.cmd === 'join') {
      state.nick = p.nick;
      state.channel = p.channel;
      send({
        cmd: 'onlineSet',
        nicks: [p.nick],
        users: [{ isme: true, nick: p.nick, userid: state.userid, trip: '', uType: 'user', level: 100, color: '5e89ed', channel: p.channel }],
        channel: p.channel,
      });
      send({ cmd: 'info', text: `fake server, mode=${mode}`, id: 1304, channel: p.channel });
      // The real server sends the token *after* onlineSet and the MOTD; keep
      // that ordering so clients that get it wrong fail here too.
      send({ cmd: 'session', restored: false, token: 'fake-token', channels: [p.channel] });

      if (mode === 'drop') {
        setTimeout(() => { log('closing socket'); ws.close(); }, dropAfter);
      }
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
        level: 100,
        customId: p.customId,
        id: Math.floor(Math.random() * 999999),
      });
    }
  });

  ws.on('close', () => log('disconnected'));
});
