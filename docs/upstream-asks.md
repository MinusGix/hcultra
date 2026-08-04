# Upstream asks for hack.chat

Two items for the hack.chat server, found while building the mobile client.
Neither stores message content and neither weakens the no-logging design — see
"Why this doesn't weaken no-logging" under each.

Evidence and reproduction for both: `../probe/FINDINGS.md`, scenarios in
`../probe/run.mjs` (`resume`, `overlap`). Measured against live 2.2.3b.

Status: **not filed yet.** The client is being designed to work correctly
*without* either change; see "If this never lands" under each.

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

## 3. Smaller items (not worth a PR on their own)

- **`help` usage strings are stale for v2.** `help` advertises
  `invite` as `{ cmd: 'invite', nick: '<target nickname>' }`, but the v2 path
  requires a numeric `userid` and ignores `nick`; a nick-only invite from a v2
  socket is silently dropped (`return true`, no reply). Meanwhile `whisper`
  declares `requiredData: ['nick','text']` and *rejects* a userid-only payload
  with `warn id 14`. The two commands disagree about how to name a target, and
  the built-in docs match neither.
- **Silent failures.** `invite` returns `true` without any reply when payload
  validation fails, so a client gets no feedback at all. A `warn` would make
  client development considerably easier.
