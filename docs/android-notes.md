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

## Still unverified

- direct-reply from the notification (`ReplyReceiver`) — hard to trigger from adb
- `KeystoreTokenStore` round-trip (the emulator runs got fresh installs, so the
  restore path exercised was in-memory within one service lifetime)
- doze / screen-off survival over a long period
- OEM battery-killer behaviour — only observable on a real phone
