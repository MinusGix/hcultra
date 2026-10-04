# Upstream asks for hack.chat

Items for the hack.chat server, found while building the mobile client. None
stores message content and none weakens the no-logging design — see "Why this
doesn't weaken no-logging" under each.

Evidence and reproduction for 1 and 2: `../probe/FINDINGS.md`, scenarios in
`../probe/run.mjs` (`resume`, `overlap`), measured against live 2.2.3b. Item 3
was found against hack-chat/main `450aeba` (2026-10-03, hackchat-server 2.3.4)
and reproduces on `../probe/phantom`.

Status: **not filed yet.** The client is being designed to work correctly
*without* any of these; see "If this never lands" under each.

---

## 1. Bug: `session` is effectively unrate-limited

`commands/core/session.js`:

```js
if (server.police.frisk(socket.address)) {
  return notifyFailure(server, socket);
}
```

against `RateLimiter.frisk(socket, deltaScore)`. Two problems:

1. **Wrong argument type.** It passes the address *string* where a socket is
   expected. `frisk` then does `this.search(socket.address)` — reading
   `.address` off a string — which is `undefined`. So it scores a junk record
   keyed `undefined` rather than the caller's real record.
2. **Missing `deltaScore`.** `record.score += undefined` → `NaN`. Every
   subsequent `record.score >= this.threshold` comparison against `NaN` is
   `false`.

Net effect: the `session` command never rate-limits, and the junk record it
maintains is permanently `NaN`.

This looks like a survival from when `frisk` took an address directly rather
than a socket; the call site was never updated when the signature changed.

**Suggested fix:** `server.police.frisk(socket, 1)`.

**Impact:** low severity today — `session` only validates a signed JWT and does
not broadcast — but it is an unmetered entry point, and `join` (weight 3) is
reachable through a restore.

**Note for us:** our client currently benefits from this. Do not build a
reconnect policy that depends on `session` being free; assume it will cost 1.

---

## 2. Feature: brief disconnect grace period before `onlineRemove`

### The problem

Mobile browsers and mobile OSes aggressively kill background WebSockets. Every
kill produces a visible `onlineRemove`, and every resume produces a visible
`onlineAdd`. A phone that backgrounds and foregrounds a few times spams a
channel with join/leave notices for a user who never actually left.

Measured on live: `onlineRemove` is broadcast **~70ms** after socket close.
There is no grace period.

### The mechanism already exists

`disconnect.js` and `join.js#restoreJoin` are already symmetric, and both
already suppress the notice when a socket with the same `userid` is present:

```js
// disconnect.js
const isDuplicate = socketInChannel(server, socket.channel, socket);
if (isDuplicate === false) {
  server.broadcast({ cmd: 'onlineRemove', ... });
}

// join.js#restoreJoin
const isDuplicate = socketInChannel(server, channel, socket);
if (isDuplicate) { /* updateUser */ } else { /* onlineAdd */ }
```

So a *make-before-break* handoff — open the new socket, `session`-restore on it,
then close the old one — is already completely silent to peers. We verified
this: peers see only `updateUser {online:true}`, and retiring the old socket
broadcasts nothing.

The gap is that this requires the old socket to still be open. A socket the OS
killed offers nothing to overlap with, which is exactly the mobile case.

### The ask

On disconnect, hold the departing socket's channel presence for a short window
(a few seconds — 10s would cover the common case) instead of broadcasting
`onlineRemove` immediately. If a `session` restore carrying the same `userid`
arrives within the window, cancel the pending removal and suppress the matching
`onlineAdd`, emitting `updateUser {online:true}` exactly as the overlap path
does today. If the window expires, broadcast `onlineRemove` as now.

This is the same suppression logic already in both files, with a timer instead
of a liveness check.

### Why this doesn't weaken no-logging

The retained state is the socket's existing in-memory presence record —
`nick`, `trip`, `userid`, `channel` — held for seconds. It is the same data the
socket registry already holds for every connected user, for a shorter time. No
message content is retained, no history is created, nothing is written to disk,
and nothing new becomes queryable by any client. A user who does not reconnect
is removed exactly as they are today, just a few seconds later.

Worth deciding explicitly: should a restore inside the window deliver messages
sent during the gap? **We are not asking for that** — that would require
buffering message content server-side, which is a real change in character. The
ask is presence-only: no join/leave spam. Missing the messages sent while
disconnected is acceptable.

### If this never lands

The client works without it:

- **Android** holds the connection in a foreground service, so drops are rare in
  the first place, and uses make-before-break (which is already silent) for
  network changes and token refresh.
- **iOS** cannot hold a background socket at all, so resumes there will produce
  visible join/leave noise. Unavoidable client-side.

The reconnect layer is written so that if the grace period lands, it is a pure
tuning change — reconnect sooner, expect `updateUser` instead of an
`onlineAdd`/`onlineRemove` pair — with no restructuring.

---

## 3. Bug: a rejoin collides with its own dead socket

### The problem

Since `450aeba`, a token restore is refused into any channel with a password or
a captcha (`join.js` `restoreJoin`, lines 371–393: warns 221 / 22). That is
deliberate — a restore must not skip the challenge — and the client then does
what the warn invites: a cold `join` on the same socket, answers the challenge,
and is admitted.

Except when it is reconnecting after a drop. The cold join's nick-collision
check (`run`, line 183) excludes only the joining socket itself:

```js
const userExists = server.findSockets((remoteSocket) => remoteSocket !== socket
  && Array.isArray(remoteSocket.channels)
  && remoteSocket.channels.includes(channel)
  && /* same nick */);
```

whereas `restoreJoin`'s (line 400) also excludes the same user:

```js
  && remoteSocket.userid !== socket.userid
```

A phone that drops off a network does not close its socket, so the server still
holds it — in the channel, under that nick, with that `userid`. The rejoin gets
`warn 33` "Nickname taken" from its own ghost, and keeps getting it until the
server notices the old socket is gone.

### Why that takes so long

`MainServer.beatHeart` sends a WebSocket ping every 16 s, but nothing records
the pong or acts on its absence. So a vanished client is only noticed when the
server's TCP gives up retransmitting the pings: about **15½ minutes** at Linux's
default `tcp_retries2`, as hack.chat is nginx on a single host with nothing in
front to time the connection out sooner. For that long, a user whose phone
switched networks cannot get back into a password- or captcha-protected
channel under their own name.

Reproduce with `../probe/phantom`: join a password channel, `POST /freeze` the
connection, rejoin with the token — `221`, then `passwordreq`, then `33` — and
`POST /release` to see the same rejoin succeed.

### The ask

Either of these fixes it; both are worth having.

1. **Exclude the same `userid` in the cold join's collision check**, as
   `restoreJoin` already does — one line. The `userid` reaching `join` here was
   restored from a token the server signed, so it is the same user, not a
   lookalike; a socket without a token gets a fresh random `userid` and is
   checked as now. Two sockets of one user sharing a nick in a channel is
   already the normal make-before-break state that item 2 describes, and
   `disconnect.js` already handles it.
2. **Terminate sockets that miss a pong.** The usual `ws` pattern: mark each
   socket alive on `pong`, and in `beatHeart` terminate any socket still
   unmarked from the previous beat before pinging again. Dead sockets would
   then be gone within ~32 s instead of ~15 min — which also makes
   `onlineRemove` arrive for users who really have gone, rather than a quarter
   of an hour later.

### Why this doesn't weaken no-logging

Neither change retains anything. (1) changes which already-connected sockets a
comparison skips. (2) drops dead connections *sooner*, so the server holds
less presence data, for less time.

### If this never lands

The client treats `33` on a rejoin — when it has been admitted to the channel
before — as its own ghost and keeps backing off for up to 16 minutes before
giving up (`ChannelSession`, `GHOST_WINDOW_MILLIS`). It works, but the user
watches "Reconnecting" for the whole of that time.

---

## 4. Smaller items (not worth a PR on their own)

- **`help` usage strings are stale for v2.** `help` advertises
  `invite` as `{ cmd: 'invite', nick: '<target nickname>' }`. Since `450aeba`
  the v2 path accepts either `nick` or a numeric `userid`, which is an
  improvement, but `whisper` still declares `requiredData: ['nick','text']` and
  *rejects* a userid-only payload with `warn id 14`. The two commands still
  disagree about how to name a target.
- **Silent failures.** `invite` still returns `true` without any reply when
  the payload names no target, and since `450aeba` most channel-scoped
  commands (`speak`, `unban`, `changenick`, …) silently frisk a payload that
  omits `channel` — 10 points for a mod command, which a client discovers only
  by being rate-limited two taps later. A `warn` would make client development
  considerably easier.
- **The rate-limited warn has no named id.** `socketreply.js` sends
  `987654323` for "You are being rate-limited or blocked.", which is not in
  `_Constants.js`, unlike every other id a client has to branch on.
