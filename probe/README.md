# probe

Tools for exploring and testing the hack.chat protocol.

## `run.mjs` — live protocol scenarios

Explores the real server. See `FINDINGS.md` for what it established.

```sh
node run.mjs help          # live command list + per-command srcHash
node run.mjs dialect       # hcProtocol 1 vs 2, side by side
node run.mjs resume        # what a peer sees across a drop
node run.mjs overlap       # make-before-break handoff
node run.mjs multichannel  # is a second join per socket accepted?
node run.mjs features      # payload shapes
```

Every scenario joins a random channel so it never disturbs a real room, and all
sends route through a local mirror of the server's rate limiter — which
persists its score to `.governor.json`, since the server's score for your
address outlives the process.

## `fakeserver.mjs` — a deliberately misbehaving server

For client paths the real server will not produce on demand. Needs `ws`:

```sh
npm install ws --no-save
node fakeserver.mjs --port 6060 --mode silent
```

| mode | behaviour |
|---|---|
| `normal` | echoes chat back, like the real server |
| `silent` | accepts chat and never echoes it — exercises `Delivery.Unconfirmed` |
| `drop` | completes the handshake, then closes the socket after `--drop-after` seconds |

It implements only enough protocol to join, but keeps the real server's
handshake *ordering* — session reply, onlineSet, MOTD, then the trailing token —
so a client that mis-orders it fails here too.

From the Android emulator the host is `10.0.2.2`, so point the app at
`ws://10.0.2.2:6060` (the explicit `ws://` matters: a bare host is assumed
secure and will not be silently downgraded).
