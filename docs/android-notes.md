# Android build & background notes

## Toolchain

Verified working on Linux, 2026-08-04:

| | |
|---|---|
| JDK | Temurin 21 |
| Gradle | 9.6.1 (wrapper committed) |
| AGP | 9.3.1 |
| Kotlin | 2.4.10 |
| Ktor | 3.5.2 |
| compileSdk | 37 (`platforms;android-37.1`) |
| minSdk | 26 |

Three toolchain traps, all hit and resolved:

1. **AGP 9 registers the Kotlin extension itself.** Applying
   `org.jetbrains.kotlin.android` alongside `com.android.application` fails with
   "Cannot add extension with name 'kotlin'". The app module applies only AGP
   plus the Compose compiler plugin.
2. **AGP 9 dropped `com.android.library` + KMP.** A multiplatform module with an
   Android target must use `com.android.kotlin.multiplatform.library` and the
   `androidLibrary { }` block inside `kotlin { }` — not `androidTarget()` plus a
   top-level `android { }`. That DSL has no `compileSdkMinor`.
3. **Ktor 3.2.0 cannot be dexed below DEX 040.** It contains a field literally
   named `use streaming syntax`, and D8 rejects spaces in names unless minSdk is
   36+. Fixed in later Ktor; we are on 3.5.2. If Ktor is ever pinned back, this
   returns as `Space characters in SimpleName ... are not allowed`.

Note the SDK package naming changed: platforms are now minor-versioned
(`android-37.1`), and the cmdline-tools shipped in `/opt/android-sdk` were too
old to parse the current repository XML (v4). A newer cmdline-tools was needed
just to *see* the package.

## Foreground service type

Declared as **`specialUse`**, deliberately:

- `dataSync` is capped at roughly 6 hours per day from Android 15. A chat
  connection silently dying mid-conversation is exactly the failure we are
  building this app to avoid.
- `remoteMessaging` is scoped to relaying messages between a user's own devices,
  which is not what this does.
- `specialUse` is the honest declaration, at the cost of a written justification
  if this is ever distributed through Play. The justification string is in
  `AndroidManifest.xml`.

For sideload/F-Droid distribution this costs nothing. If Play distribution is
ever wanted, this is the item most likely to need negotiation — worth deciding
before it constrains anything else.

## Why the service owns everything

`SessionManager` — sockets, tokens, and the in-memory scrollback — lives in
`HcService`, not in the Activity. The Activity binds, reads state, and sends
intents.

This is not architectural taste; it follows from the protocol. hack.chat keeps
no server-side history, so a reconnect cannot recover what was said, and every
reconnect is visible to the entire channel as an `onlineRemove`/`onlineAdd`
pair. An Activity-owned connection would reconnect on every rotation. With the
service owning it, the Activity can be destroyed and recreated freely.

`START_STICKY` plus the session token means even an OOM kill restores identity
(same nick, trip, userid) rather than performing a fresh join.

## Battery optimisation

The manifest requests `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. On Xiaomi,
Samsung and Oppo the OEM battery manager will kill the service regardless of its
foreground status without an exemption, and — because a dropped socket cannot do
a make-before-break handoff — every one of those kills is peer-visible channel
noise. The prompt is not yet wired into onboarding.

## Emulator

AVD `hcultra` (`system-images;android-36;google_apis;x86_64`, pixel_6). KVM on
this machine is world-accessible, so no group changes were needed.

```sh
/opt/android-sdk/emulator/emulator -avd hcultra -no-window -no-audio \
    -no-boot-anim -gpu swiftshader_indirect -no-snapshot-save &
adb wait-for-device
```

Two gotchas: `avdmanager` needs `ANDROID_SDK_ROOT` set explicitly, and the
*newer* cmdline-tools failed to resolve system images where the SDK-bundled one
succeeded — so create AVDs with `/opt/android-sdk/cmdline-tools/latest/bin/avdmanager`.

### Watching it, on Wayland

Dropping `-no-window` to actually watch the app needs `QT_QPA_PLATFORM=xcb`:

```sh
QT_QPA_PLATFORM=xcb /opt/android-sdk/emulator/emulator -avd hcultra -no-audio \
    -no-boot-anim -gpu swiftshader_indirect -no-snapshot-save &
```

The emulator's bundled Qt ships only `vnc`, `linuxfb`, `offscreen`, `minimal`
and `xcb` plugins — no `wayland` — so under a Wayland session it aborts with
*"no Qt platform plugin could be initialized"*. Forcing xcb routes it through
XWayland. Headless runs never initialise Qt at all, which is why this only
appears the first time someone wants to look at the screen.

`wait-for-device` returns while the guest is still booting; wait for the
property before installing:

```sh
adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done'
adb install -r app-android/build/outputs/apk/debug/app-android-debug.apk
adb shell monkey -p chat.hc.ultra -c android.intent.category.LAUNCHER 1
```

The service is not exported, so `adb shell am start-foreground-service` is
rejected ("Requires permission not exported"). Drive the real UI instead:
`adb shell input tap/text` plus `adb shell uiautomator dump` to read state.

## Verified on the emulator

Against live hack.chat, with an independent Node observer
(`probe/`-style script) joined to the same channel as ground truth:

- join from the UI — observer saw `JOIN emubot`
- foreground service running with `types=0x40000000` (specialUse) and an
  ONGOING/SILENT notification carrying 2 actions
- **inbound** messages render (chat, join/leave markers)
- **outbound** messages reach the server and are reconciled from pending to sent
  via `customId`
- **backgrounding to the launcher: no `onlineRemove`** — the connection
  survives, the service stays foreground, the process stays alive
- returning to the app restores state **from the service**, with no reconnect
  and no peer-visible noise
- `KeystoreTokenStore` round-trips: after a full `force-stop`, rejoining a
  channel restored by token (state reported `resumed`, i.e. `restored=true`)
- network loss → `Reconnecting (attempt 1)` → restore-by-token on recovery, and
  the app logged "Reconnected." (only emitted when `restored=true`)

That last one also confirmed the core finding from the wrong side of the glass:
the observer saw **`LEAVE` then `JOIN`** across the outage. Exactly the noise
the grace-period ask in `upstream-asks.md` exists to remove.

## Bugs this found

1. **`customId` exceeded `MAX_MESSAGE_ID_LENGTH` (6).** The generator emitted up
   to 13 chars. `chat.js` discards such a message **with no reply at all** and
   charges `frisk(socket, 13)` of a 25 threshold — so messages silently vanished
   while pushing the user toward a rate-limit they could not diagnose. Fixed in
   the generator, plus a client-side guard (`InvalidFrameException`) so an
   oversized id fails loudly instead of invisibly, plus regression tests.
2. **Composer sat underneath the soft keyboard.** `windowSoftInputMode=adjustResize`
   does not inset Compose content; the input row and Send button were literally
   untappable with the keyboard open. Fixed with `Modifier.imePadding()`.
3. **"Disconnected" repeated once per retry**, filling the transcript during an
   outage. Now collapsed to one notice per outage.

## Message renderer

`app-android/src/main/assets/renderer/` hosts the site's own pipeline —
Remarkable + remarkable-katex + highlight.js 9.12 + KaTeX — vendored from
`hc/client/vendor/`, with `app.js` reproducing hack.chat's markdown options
exactly (`html:false`, `breaks`, `linkify`, `typographer`, the image whitelist,
the `?channel` linkifier). Parity with the site is the entire reason this is a
WebView; if the site changes its options, change `app.js` to match.

Only woff2 KaTeX fonts are shipped (360K of the original 1.5M — Android WebView
is Chrome-based, so woff2 always resolves first). With all 44 schemes and 11
highlight themes, assets total 1.1M; debug APK ~14MB.

Verified rendering on-device against live: bold/italic/strike, inline code,
inline **and** display KaTeX with fonts, Kotlin syntax highlighting, links,
`?channel` refs, blockquotes and lists.

Hardening, because every message is untrusted input from a public channel:

- `html:false` plus the escaping `text` rule means message text is never
  interpreted as markup.
- The payload crosses via `RendererBridge.renderCall()` in core as a JSON
  *string literal* parsed inside the page — never interpolated as JavaScript.
- The WebView has file access, content access, DOM storage and **all network
  loads** disabled. It only ever loads bundled assets.
- Links never navigate the WebView; taps are handed to native, which opens
  http/https only.

`RendererBridge` lives in `core/` rather than the app module so iOS can reuse
the same payload and the same asset bundle, with only the WKWebView host
differing.

## Theming

hack.chat's own 44 colour schemes and 11 highlight.js themes ship in
`assets/renderer/`, and both are switchable at runtime.

The trick is that the renderer emits **the site's own markup** — `.message`,
`.nick`, `.trip`, `.text`, with `.admin` / `.mod` / `.me` / `.info` / `.warn`
modifiers — which is exactly what the scheme stylesheets target. So all 44
apply unmodified, and a new upstream scheme is a file copy. `app.css` is
therefore layout-only; it loads *before* the scheme so it can never win a
colour argument with it.

The native chrome follows the same scheme. `tools/gen-schemes.mjs` parses the
stylesheets at build time into `assets/renderer/schemes.json` (background,
foreground, nick, link, warn, plus a WCAG-luminance `dark` flag), which
`Scheme.toColorScheme()` maps onto a Material colour scheme. Extracting from
the same CSS the WebView loads is what stops the two halves drifting apart.
Re-run the generator after copying new schemes in.

Highlight themes are paired automatically: `gen-schemes.mjs` reads each
highlight.js theme's own `.hljs` background and assigns each scheme its nearest
match, so code blocks do not punch a light hole in a dark theme. The user can
still pin one explicitly; `ThemePrefs.highlightOverride` is null-for-auto, so
changing scheme keeps moving the code colours along with it until pinned.

Choice persists in `ThemePrefs`; the picker previews each scheme in its own
colours, since names like "atelier-heath" mean nothing otherwise.

Verified on-device: default (dark) and android-white (light) both restyle the
transcript *and* the native chrome together, and the choice survives a restart.

Note this supersedes `prefers-color-scheme`: the app follows the user's chosen
hack.chat scheme rather than the system light/dark setting. Following the
system as a *default* for first run is still open.

## Multi-channel

One socket per channel, so a tab really is a distinct connection — and it can be
reconnecting while the tab you are reading is fine. Each tab therefore shows a
status dot alongside its unread count.

Verified on-device: two channels joined at once, unread badge accrues on the
background tab and clears on selection, per-channel transcripts and drafts stay
separate, and closing a tab leaves that channel via the service.

Two races worth remembering, both found here:

- Selecting a just-joined channel must not be validated against the channel map
  until the service has created it, or the selection snaps back to the first tab
  the moment you join. `awaitingJoin` holds the selection across that gap.
- `activeChannel` is cleared in `onStop`, so a backgrounded app counts unread for
  every channel, and restored in `onResume`.

## Slash commands

hack.chat parses `/` commands **server-side**, via `in`/`chat` hooks that each
module registers (`/me`, `/w`, `/nick`, `/shrug`, …), with
`chat.js#finalCmdCheck` rejecting anything unrecognised and `//` escaping to a
literal. So this client deliberately does **not** parse commands: sending raw
text gives exact parity, and a command added upstream works without a client
change.

The one thing the client must know is whether to expect its own message back,
since that decides the optimistic bubble. Probed against live:

| input | server sends | echoes our customId |
|---|---|---|
| `/me waves` | `emote` | no |
| `/shrug hi` | `chat`, text rewritten | **yes** |
| `//literal` | `chat`, text `/literal` | **yes** |
| `/notacommand` | `warn` id 16 | no |
| `/myhash` | `info` id 1302 | no |
| `"  /me waves"` | `chat` verbatim | **yes** |
| `/me` (no argument) | `warn` id 16 | no |

Since `/shrug` echoes and `/me` does not, "starts with a slash" cannot predict
the outcome — so `Composer` never tries. Slash commands are sent with no
customId and no optimistic bubble; whatever the server returns stands on its
own. Note the matching is **not** trimmed: the hooks test raw text, so
`"  /me waves"` really is an ordinary message and trimming here would
misclassify it.

Verified on-device: `/me waves at everyone` renders as an emote with no stuck
"sending…" bubble, an unknown command surfaces its warn, and ordinary messages
still reconcile optimistically.

## Whispers

The server sends the **same** frame to both parties —
`{cmd:'whisper', channel, from, to, text}` — so the only way to know which
direction a whisper went is to compare `from` against our own userid. Both ends
are userids, never nicks, so they are resolved against the roster **on receipt**:
the other party may leave before the view redraws, and a stale id renders as
`user <id>` rather than a blank nick.

`WhisperResolver` isolates that logic and is unit-tested, including the case
where our own userid is not yet known — guessing "outgoing" there would label
someone else's private message as ours.

Verified on live with two sockets: `/w`, `/reply`, and the API form all produce
the same shape; an unknown target gives `warn id 12`. Verified on-device in both
directions, with the peer confirming receipt.

In the UI, tapping a roster entry inserts an `@mention` and long-pressing starts
a `/w` — the server's `/w` strips a leading `@`, so the two compose cleanly.

## Delivery states

A sent message is only *known* delivered once the server echoes it back with our
customId. Four states, and the fourth exists because three were not enough:

- `Sending` — written to the socket, awaiting the echo.
- `Sent` — echoed; it definitely reached the channel.
- `Failed` — the socket rejected it; it definitely did not send.
- `Unconfirmed` — sent, never echoed. **Deliberately not "failed":** we cannot
  tell whether it was delivered, and calling it failed would invite a duplicate
  resend of a message that did go out.

A message reaches `Unconfirmed` two ways: the connection drops with it still
pending (`markAllPendingUnconfirmed`), or it ages past `echoTimeoutMillis`
(10s — a normal echo returns in well under a second). The timeout is the
general safety net: the socket can stay open while the server discards a
message without replying, which is exactly what an oversized customId or a
rate-limit penalty does.

A late echo still reconciles an `Unconfirmed` message rather than appending a
duplicate — a slow round trip can outlive the timeout, and resolving beats
double-posting what the user just typed.

### Test coverage

The transitions are covered deterministically by `ChannelBufferTest` (including
"already echoed must not be downgraded" and "late echo must not duplicate").

The timeout is also **verified on-device**, using `probe/fakeserver.mjs` in
`silent` mode — a server that completes the handshake and then swallows chat
without echoing. The message went `sending…` → `unconfirmed` exactly as
designed. This was previously unreachable: killing the network disables the
composer, and emulator shaping does not affect an established socket.

## Server setting

The endpoint is configurable (Settings → Server), defaulting to
`wss://hack.chat/chat-ws`. Only one server at a time — the UI has no notion of
several — but **tokens are keyed by server**, which is the part that would be
painful to retrofit. Trying a local server therefore costs nothing: the
hack.chat tokens survive untouched, and a cold rejoin there would be visible to
the whole channel as a leave/join pair.

`Servers.normalize` accepts what a person would actually type (`localhost:6060`,
`hack.chat`, `https://…`) and appends `/chat-ws` when no path is given. A bare
host is assumed **secure**: it never silently downgrades to plaintext, so a
local plaintext server needs an explicit `ws://`. The UI warns when the
resulting endpoint is unencrypted.

Changing server tears down every channel — the channels, nicks and tokens all
belong to the old endpoint.

### Local test server

`probe/fakeserver.mjs` implements just enough protocol to join (v2 handshake,
onlineSet, MOTD, and the token that trails them, in that order — so a client
that mis-orders the handshake fails against it too).

```sh
node probe/fakeserver.mjs --port 6060 --mode silent   # accept chat, never echo
node probe/fakeserver.mjs --mode drop --drop-after 15 # close the socket after 15s
node probe/fakeserver.mjs --mode normal               # echo like the real server
node probe/fakeserver.mjs --mode restore              # honour session tokens
```

`restore` is the one that exercises identity. It issues legible
`restore:<nick>:<channel>` tokens and reinstates the identity inside them,
which is what lets a client be caught coming back as whoever it was here last
time instead of as the nick the user typed. It also derives a trip from `pass`,
so trip-carrying identities are observable without real credentials.

From the emulator the host is `10.0.2.2`, so the address is
`ws://10.0.2.2:6060` — note the explicit `ws://`. A bare `10.0.2.2:6060` is
normalised to `wss://` and will fail against a plaintext server.

Cleartext needs permitting, which `app-android/src/debug/AndroidManifest.xml`
does for debug builds only — it is off by default from targetSdk 28. Without it
the connection is blocked in a way that looks exactly like an ordinary network
failure: the client reconnects and backs off, and nothing reaches the server log.

Verified on-device: switching to it, joining, observing `unconfirmed`, then
resetting to hack.chat and rejoining a channel that still reported `resumed`.

## Moderation

Mod commands are **API-only**: unlike `/me` or `/w`, most register no text
hook, so `/ban someone` just returns "Unknown command". On the website they are
reached from the browser console. Native UI is therefore a genuine capability
gain rather than a convenience.

Two things make the permission model worth modelling rather than eyeballing:

- **The gates differ per command.** `kick` (and `lockroom`, `unlockroom`, `hack`)
  need only `isChannelModerator` — level 9 999 — while `ban`, `dumb`, `speak`,
  `unban`, `forcecolor`, `forceflair` and the captcha commands need
  `isModerator`, level 999 999. Assuming one "mod" threshold would be wrong in
  both directions.
- **Failing a gate is expensive.** A rejected command costs `frisk(socket, 10)`
  of a 25 threshold and the server replies *nothing*, so an over-permissive UI
  would rate-limit the user after two taps with no explanation. `SessionManager`
  re-checks the level before sending, so a stale button cannot spend the budget.

Wire shapes (v2): `kick`/`ban`/`dumb` take a numeric `userid` plus an explicit
`channel`; `speak` and `unban` key on the target's **hash** instead, because a
ban outlives their presence in the channel. `unban` is not offered from the
roster for that reason — by the time you want it, they are gone and their hash
with them.

The roster offers Kick / Ban / Muzzle / Unmuzzle, filtered by level, never
against yourself. Destructive ones confirm first and spell out what they do
("their messages are silently dropped — they are not told"), since the
distinction between kick, ban and muzzle is not obvious from the verb alone.

Verified on-device against `probe/fakeserver.mjs --level N`: an ordinary user
sees no actions, a channel moderator sees only Kick, a global moderator sees
all four, and the emitted frames were
`{"cmd":"kick","channel":"…","userid":4242}` and
`{"cmd":"speak","hash":"victimhash"}` — hash-keyed with no userid, as required.

## Known gap

The composer is disabled whenever the session is not `Live`, so you cannot type
a message while reconnecting — it has to be retyped after the connection comes
back. A send-on-reconnect outbox would fix it, but that needs care: the server
keeps no history, so a queued message replayed after a long gap can land far out
of context.

## Still unverified

- direct-reply from the notification (`ReplyReceiver`) — hard to trigger from adb
- mention and whisper alerts on-device: that they buzz at all (the `VIBRATE`
  permission and the channels' own vibration patterns), that they stay silent
  while the app is foregrounded, and that opening the channel clears them.
  Everything except the buzz is drivable from `probe/fakeserver.mjs`; the
  vibration itself needs a real phone, since the emulator has no motor
- moderation against the *real* server: the level gating and wire shapes were
  exercised against the fake server, since we hold no moderator rights on live
- doze / screen-off survival over a long period
- OEM battery-killer behaviour — only observable on a real phone
