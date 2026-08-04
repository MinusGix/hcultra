# hcultra

A native mobile client for [hack.chat](https://hack.chat).

Mobile browsers aggressively kill background WebSockets, which makes hack.chat
painful on a phone: switch apps and you drop out of the conversation. Because
the server keeps no history, a dropped connection loses what was said — and
every reconnect is visible to the whole channel as a leave/join pair. This app
holds the connection in an Android foreground service so that stops happening.

Android first; iOS shares the core, with the caveat that iOS cannot hold a
background socket at all (see `docs/`).

## Layout

| | |
|---|---|
| `core/` | Kotlin Multiplatform protocol core — frames, session state machine, rate governor, ephemeral buffers. JVM + Android targets. |
| `app-android/` | Android app: foreground service, notification with direct reply, Compose UI. |
| `probe/` | Node harness that explores the live protocol. How the findings below were established. |
| `docs/` | Design notes and the upstream asks for hack.chat. |
| `hc/` | *(gitignored)* upstream server source, kept as a protocol reference. |

Start with **`probe/FINDINGS.md`** — the protocol has sharp edges that the code
is shaped around, and it is the reference for why.

## Getting the server source

`hc/` is a separate clone, ignored by this repo:

```sh
git clone https://github.com/hack-chat/main hc
```

Findings here were established against upstream `7435d0a` and live hack.chat
**2.2.3b**. Live diverges from the public source; `probe/run.mjs help` prints a
per-command `srcHash` so you can diff exactly which commands differ.

## Build

Needs JDK 21 and an Android SDK with platform 37 (`platforms;android-37.1`).

```sh
./gradlew :core:jvmTest              # protocol core unit tests (no network)
./gradlew :core:liveSmoke            # end-to-end against live, joins a random channel
./gradlew :app-android:assembleDebug
```

`docs/android-notes.md` covers the emulator setup and the toolchain traps
(AGP 9 + KMP, the Ktor dexing failure, minor-versioned SDK platforms).

## Protocol notes that bite

Details and reproductions in `probe/FINDINGS.md`:

- **The first frame you send picks the protocol dialect for that socket's whole
  life.** `session` first selects v2; `join` first permanently downgrades you to
  the 2015-era v1, where whispers and invites arrive as prose `info` frames.
  Always send `{cmd:'session'}` first, with or without a token.
- **The post-join session token arrives *after* `onlineSet`.** Treat `onlineSet`
  as "handshake complete" and you never capture a token, losing silent resume.
- **Reconnect is only invisible to peers if the old socket is still open**
  (make-before-break). A socket the OS killed cannot do this.
- **`customId` is capped at 6 characters.** Longer, and the server discards the
  message with *no reply* and charges 13 rate-limit points of 25.
- **One channel per socket.** A second `join` is refused with `warn id 33`.
- Rate limiting is scored **per address**, shared across every socket on the
  device, so one governor is shared by all sessions.

## Status

Working: protocol core (unit tested + verified against live), Android
foreground service surviving backgrounding, send/receive, reconnect with token
restore. Verified on an emulator against live hack.chat.

Next: WebView message renderer reusing the site's own markdown + KaTeX +
highlight.js pipeline, for exact rendering parity.
