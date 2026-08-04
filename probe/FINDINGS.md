# Protocol spike findings

Measured against live `wss://hack.chat/chat-ws` (**HackChat 2.2.3b**) on 2026-08-04,
cross-referenced with the public source in `../hc` (2.2.1).

Reproduce: `node probe/run.mjs <help|dialect|resume|overlap|features|all>`

## 1. There are two dialects, and your first frame picks one

`socket.hcProtocol` is decided by the **first frame the client ever sends**, and is
fixed for that socket's lifetime:

- first frame `join` → **v1** (`join.js:57` → `upgradeLegacyJoin()`, sets `hcProtocol = 1`)
- first frame `session` → **v2** (`session.js:101` sets `hcProtocol = 2` *before*
  validating the token, so a bare `{cmd:'session'}` with no token is a pure
  "I speak v2" declaration)

**The app must always send `{cmd:'session'}` first**, with a token if we hold one,
otherwise bare. Measured differences:

| event | v1 socket receives | v2 socket receives |
|---|---|---|
| whisper | `cmd:'info', type:'whisper'`, prose `text` | `cmd:'whisper'`, structured `from`/`to`/`text` |
| invite | `cmd:'info', type:'invite'`, prose `text` | `cmd:'invite'`, `from`/`to`/`inviteChannel` |
| emote | `cmd:'emote'` (identical) | `cmd:'emote'` (identical) |

v1 also diverges in `changenick` (`changenick.js:121`), `ban`, `kick`, `hack`, and
the captcha commands.

## 2. v2 targeting is explicit — and inconsistent between commands

`findUsers()` (`_Channels.js:227`) matches on `payload.channel`. A v1 socket gets
`payload.channel` auto-filled from `socket.channel`; **a v2 socket does not**, so
every targeted command must carry `channel` explicitly. Beyond that the two
commands disagree:

- `whisper` — `requiredData: ['nick','text']`. A userid-only payload is rejected
  with `warn id 14: missing required nick`. Send `{cmd, channel, nick, text}`.
- `invite` — requires **numeric `userid`** and string `channel`; a nick-only
  payload is silently dropped (`invite.js` `return true`, no reply at all).

`help`'s own usage strings are **stale for v2** (it advertises `invite` as taking a
nickname). Do not treat `help` output as API documentation.

## 3. Reconnect is NOT silent — unless you overlap

`disconnect.js` and `join.js#restoreJoin` are symmetric: both call
`socketInChannel()` and suppress their broadcast if a socket with the same
`userid` is already in the channel.

- **Cold resume** (socket died, then restore): `onlineRemove` fires ~70ms after
  the close — there is **no ghost grace period** — then the restore emits a full
  `onlineAdd`. Peers see a leave/join pair every time.
- **Make-before-break** (open new socket, `session`-restore, *then* close old):
  peers see only `updateUser {online:true}`. Closing the old socket afterward
  emits **no `onlineRemove`**. Fully silent, and verified still able to speak.

Identity is continuous either way: `userid`, `trip`, and `color` survive the
restore, so message attribution stitches correctly across a drop.

Implications:
- Android foreground service should keep the socket alive so drops are rare, and
  use make-before-break for network changes (wifi↔cellular) and token refresh.
- A socket the OS killed leaves nothing to overlap with, so iOS backgrounding
  will produce visible leave/join noise. This is the strongest argument for the
  upstream ask in §6.
- On our own socket, a make-before-break `onlineSet` transiently lists us
  **twice**. The client must dedupe the user list by `userid`.

## 4. Handshake ordering

The post-join `session` token arrives **after** `onlineSet` and after the MOTD
`info` frame. A client must not treat `onlineSet` as "handshake complete" or it
will never capture a token. Tokens are JWT, `expiresIn: '7 days'`, and a fresh
one is issued on **both** join and restore — so reconnecting at least weekly
rolls the window forward indefinitely.

## 5. Rate limiting bounds our reconnect policy

`RateLimiter`: keyed by **remote address** (shared across all our sockets),
`threshold: 25`, `halflife: 30s`. Costs: `join` = 3, `help` = 2, `invite` = 2,
`chat` = `len/83/4`, malformed text or oversized `customId` = **13** (two of
those within ~30s = hard limit). A rejoin storm is genuinely expensive; prefer
restore over join.

`probe/hcclient.mjs` mirrors this limiter locally and persists the score to
`.governor.json` between runs, since the server's score outlives our process.

### Bug worth reporting upstream

`session.js:105` calls `server.police.frisk(socket.address)` against a signature
of `frisk(socket, deltaScore)`:

1. it passes the address *string* where a socket is expected, so
   `search(socket.address)` reads `.address` off a string → `undefined`, scoring
   a junk record instead of the caller's;
2. `deltaScore` is missing, so `record.score += undefined` → `NaN`, and
   `NaN >= threshold` is always false.

Net effect: **`session` is effectively unrate-limited.** Fix is `frisk(socket, 1)`.
Convenient for us today, but we should not depend on it.

## 6. Proposed upstream ask (no impact on the no-logging ideal)

A short **grace period on disconnect**: hold the departing socket's channel
record for N seconds instead of broadcasting `onlineRemove` immediately. If a
`session` restore with the same `userid` arrives inside the window, suppress the
remove/add pair entirely (emit `updateUser` as the overlap path already does).

This is purely ephemeral in-memory state, seconds long, of exactly the same kind
the socket registry already holds — it stores no message content and no history.
It would make mobile backgrounding invisible to a channel, which is the single
biggest UX problem for the app.

## 7. Live vs public source divergence

`help` returns the live command list plus `CodebaseVersion`, and
`{cmd:'help', command:'x'}` returns that command's `srcHash` — which
`CommandManager.js:136` computes as plain `sha256` of the command file's bytes.
So we can diff local against live exactly:

| command | local (`./hc`) | live | |
|---|---|---|---|
| `session` | `89d0b4…` | `89d0b4…` | identical |
| `updateMessage` | `1bc6d4…` | `1bc6d4…` | identical |
| `whisper` | `1856e2…` | `1856e2…` | identical |
| `invite` | `1bd10e…` | `1bd10e…` | identical |
| `join` | `47c7fb…` | `f14d10…` | **differs** |
| `chat` | `01758b…` | `149baa…` | **differs** |

Live also has commands absent from the public source: `bomb` (uncategorized) and
`uwuify` (mod); `getchannels` has moved from Channels into Core.

The resume machinery we depend on is byte-identical. `join` and `chat` diverge,
but the divergence does **not** include multichannel — see §8.

## 8. Multichannel: not supported on live either

Despite `join` diverging from the public source, live still rejects a second
`join` on an established socket:

```
warn id=33 "Joining more than one channel is not currently supported"
```

The socket remains fully usable in its original channel, and the session token's
`channels` array stays at one entry. So:

**One socket per channel.** Model the connection layer as N independent
`ChannelSession`s (each with its own socket, its own token, its own state
machine) behind one manager. The token format already carries a `channels`
array and `session.js` restores all of them via `restoreJoin`, so when upstream
finishes multichannel we collapse N sockets into 1 without the UI noticing.

## 9. Untested / open

- `changenick` — hit a per-socket "changing nicknames too fast" cooldown on both
  attempts; its dialect difference is unverified.
- Captcha flow (`enablecaptcha`), `updateMessage` streaming shapes, `bomb`,
  `uwuify`, and the wallet commands.
