# UI backlog

Found in an emulator session on 2026-08-04, joining live hack.chat. Bugs first,
then presentation work. Nothing here blocks the send-on-reconnect outbox, which
is still the larger open item (`README.md`, "Known gap").

## Bugs

All four fixed on 2026-08-04; the notes are kept because each explains why the
code is now shaped the way it is.

### 1. The join form does not remember the nick

**Observed:** the form is empty on every cold start, yet the channel is rejoined
under the previous nick anyway.

**Cause:** `MainActivity.kt` (`AppScreen` → `JoinSheet`) derives the prefill from
a *live* channel's roster:

```kotlin
initialNick = ordered.firstOrNull()?.roster?.firstOrNull { it.isme }?.nick.orEmpty()
```

On cold start `ordered` is empty, so there is nothing to read. The name that
appears afterwards comes from `KeystoreTokenStore` — an entirely separate path.
The prefill only ever worked for a *second* channel joined in the same session,
which is why it looks like the app half-remembers.

**Fix:** persist the credentials rather than deriving them. hack.chat's own
client keeps nick and password in `localStorage` and re-logs-in from it, so
storing both is parity, not overreach — but the password is a real secret and
belongs in `KeystoreTokenStore` alongside the tokens, not in a `SharedPreferences`
sibling of `ThemePrefs`. Key by server, as tokens already are: a trip is
server-salted, so the same password gives a different trip elsewhere.

**Fixed:** `data/CredentialStore.kt`, on its own Keystore alias, with the AES-GCM
code lifted out of `KeystoreTokenStore` into `data/KeystoreCrypto.kt` so both
share one implementation. Prefilled in the field's own `nick#password` form.
Stored credentials deliberately outrank the live roster: the roster knows the
nick but not the password, so preferring it would join a second channel *without*
the trip.

Verified on-device: joined as `mubot2#hunter2`, `am force-stop`, relaunched — the
field came back prefilled.

### 2. Dark flash before a light scheme

**Observed:** a dark box on launch, then the app settles into the light scheme.

**Cause:** two separate hardcoded values that cannot see `ThemePrefs`.
`res/values/themes.xml` fixes the window at

```xml
<style name="Theme.HcUltra" parent="android:Theme.Material.Light.NoActionBar" />
```

and `res/values/colors.xml` sets `ic_launcher_background` to `#1B1E23`, which is
what the Android 12+ splash paints behind the icon. Compose only learns the real
scheme once `onCreate` reads `ThemePrefs`, by which point the wrong background
has already been on screen for a frame or more.

Note this is *not* the `prefers-color-scheme` question — the app deliberately
follows the chosen hack.chat scheme rather than the system setting
(`android-notes.md`, "Theming"). The window background simply has to follow the
same choice.

**Fix:** read `ThemePrefs` before `setContent` and set the window background from
the resolved scheme, so the pre-Compose frame is already the right colour.

**Fixed:** `MainActivity.applyWindowBackground` paints the window from the scheme
before `setContent`, and again on a runtime scheme change so a later rotation
never repaints from a stale colour. `themes.xml` now points both
`windowBackground` and `windowSplashScreenBackground` at
`@color/ic_launcher_background`, which is what removes the *box*: the icon plate
and the splash behind it become one colour instead of a dark rectangle on a light
field.

Verified on-device only as far as the settled frame — switching to Android White
and relaunching comes up light. The remaining transition is inherent: the splash
is a static resource and cannot know which of the 44 schemes is in force, so a
light-scheme user still goes dark-splash → light-app. Removing that entirely
would mean no splash colour at all, or a scheme-coloured splash Android has no
mechanism for.

### 3. A chat message can appear above "Reconnected"

**Observed:** on reconnect, someone's message rendered *before* the "Reconnected."
notice.

**Cause:** ours, not the server's. `ChannelSession.awaitFrame` dispatches every
non-matching frame while it waits:

```kotlin
for (frame in frames) {
    if (predicate(frame)) return@withTimeout frame
    dispatch(frame)
}
```

That is the right behaviour — dropping them would lose messages — but
`SessionEvent.Resumed` is not emitted until `supervise` runs, *after*
`connectAndHandshake` returns. So anything arriving during the restore handshake
is correctly kept and incorrectly ordered.

There are two such windows: waiting for the restored `onlineSet`, and the
post-join token wait guarded by `tokenGraceMillis`.

**Fix:** buffer frames dispatched during the handshake and flush them after the
`Resumed` notice, so the notice keeps its place at the seam.

**Fixed:** `awaitFrame` now collects into a `deferred` list that
`LiveConnection` carries, and both resume paths — `supervise` and `handoff` —
call `flushDeferred` immediately after emitting `Resumed`. The `onlineSet` itself
is still dispatched inline, because the roster and our own userid have to be
current before any held-back chat is replayed through them.

Deferring must not become a way to *lose* frames, so a handshake that throws
dispatches whatever it collected before rethrowing — a join refusal is usually
preceded by the info explaining it.

Covered by `ChannelSessionTest.midHandshakeChatIsOrderedAfterTheResumeNotice`
and `handshakeFailureStillDeliversWhatArrived`.

### 4. No "Users online" line on join

**Observed:** the roster is only reachable through the `n online` button, so
there is no moment where you simply see who is here.

**Cause:** not implemented. Upstream does it in `client.js#onlineSet`:

```js
pushMessage({ nick: '*', text: "Users online: " + nicks.join(", ") })
```

**Fix:** emit the same `info`-kind message on `onlineSet`. It renders through the
existing `*` / `.info` path with no renderer change, and it makes the roster
sheet a secondary affordance rather than the only way to answer "who is here".

**Fixed:** in `SessionManager`'s `Resumed` handler rather than on the `onlineSet`
frame — which is the whole point. Emitting it at the frame would put it *above*
"Reconnected.", reintroducing bug 3 by hand. By the time `Resumed` fires the
handshake has completed, so the roster is already current.

Skipped on a silent handoff: nothing observable changed, so repeating the roster
would be pure noise.

Verified on-device: joining live rendered `Users online: mubot2` above the MOTD.

## Presentation

Items 5, 6 and 8 are done. Item 7 is built as all three options, with the
default still open — that is the one decision left here.

### 5. Flair is parsed but never rendered

Upstream decorates a nick with an emoji by level, from `getAppearance(level)` in
`commands/utility/_UAC.js`:

| level | flair | colour |
|---|---|---|
| admin | 🌟 | `d73737` |
| moderator | ⭐ | `1fad83` |
| channelOwner | 👑 | `dd8800` |
| channelModerator | 💫 | `2fa1ee` |
| bot | 🤖 | `2fa1ee` |

Everyone else gets `flair: false` and a random colour. `forceflair` lets a
moderator set an arbitrary one, capped at **2 characters** — so it is a short
string, not an enum, and must be rendered as text rather than mapped to an icon.

It arrives on `chat`, `onlineSet` and `onlineAdd`, and `Frames.kt` already
carries it (`User.flair`, and on each frame). The gap is downstream:
`RendererBridge.WireMessage` omits it, so `app.js` never sees it. Upstream
renders it inside the `.trip` span, before the trip:

```js
if (args.flair && args.trip) tripEl.textContent = args.flair + " " + args.trip + " ";
else if (args.flair)         tripEl.textContent = args.flair + " ";
```

Matching that placement keeps the scheme stylesheets working unmodified, for the
same reason the rest of the markup mirrors the site.

The roster (`UserList.kt`) should show it too — it is the fastest read on who can
actually moderate, and it pairs with the level gating already there.

**Fixed:** `flair` added to `ChatMessage`, carried through
`RendererBridge.WireMessage`, and rendered by `app.js` inside the `.trip` span
in the site's order. Also shown in the roster.

**This turned up a real bug the moment it rendered.** `flair` is not
`String | null` on the wire — it is **`String | false`**. `getAppearance()`
returns `flair: false` for anyone undecorated, and our reader is lenient (it has
to be: the server also sends `channel: false`), so the boolean decoded as the
*string* `"false"` and rendered as a badge reading "false" beside every ordinary
nick. Normalised in `FlairSerializer` at the decode boundary rather than at each
consumer — the roster and the renderer both read it, and a third caller would
forget. Covered by `FrameCodecTest.decodesChat` / `decodesFlairString` /
`decodesFlairOnRoster`.

Verified on-device against `probe/fakeserver.mjs`, which now reproduces the
`levelAppearance` table including the `false`: a 🌟 admin renders flair, trip
and nick in that order, and an ordinary user renders none.

### 6. The status/roster/theme row

`describe(state)` currently occupies a full-width row with a word that is noise
almost all the time: "resumed" and "connected" differ in a way only this codebase
cares about, and the interesting states — reconnecting, failed — are already
carried per-tab as status dots by `ChannelTabs`.

Intended shape:

- Fold the online count into each channel tab as a small number, next to the
  unread badge.
- Replace the row with a settings icon on the tab bar itself.
- Move Theme under settings; scheme is not a per-session choice.
- Drop the connected/resumed text. A non-live state should be an icon, or the
  existing per-tab dot, not a word.

That removes a whole row from a screen where vertical space is the scarce thing.

**Fixed:** the count sits on each tab and is the way into the roster, now that
there is no status row to hang it off; settings is a ⚙ at the end of the strip
(a glyph, not an icon — the module has no `material-icons` dependency and the
file already uses `+` and `×` the same way).

`describe()` now returns `String?` and gives `null` for `Live` and `Idle`, so
the line only appears when something is wrong. `Failed` deliberately keeps its
reason: a red dot cannot say *why*, and "nick taken" is not guessable.

### 7. Nick and trip layout — three modes, then pick a default

Currently nick and trip sit inline ahead of the text. Three candidates, to be
built as a setting and chosen from afterwards:

1. **Left column** (closest to the site): nick and trip in a fixed-width gutter,
   text in a right-hand column. Aligned nicks are the main win.
2. **Stacked**: `trip nick` on its own line, text beneath.
3. **Inline**: what exists now.

The renderer emits the site's own markup on purpose, and the scheme stylesheets
target it — so these should be a class on the container that the layout CSS keys
off, not three different DOM shapes. `app.css` is layout-only and loads before
the scheme, so it can carry all three without ever winning a colour argument.

**Built, all three, awaiting a decision on the default.** `NickLayout` in core,
persisted in `ThemePrefs`, applied as one class on `<body>` via `HC.setLayout`.
Currently defaults to `Inline` — i.e. unchanged — so picking a different default
is a one-line change to `NickLayout.DEFAULT`.

One markup change was needed: everything preceding the text is now wrapped in a
`.head` span. Without it the gutter layout would have to cope with `.nick` being
absent (info, join, leave) and sometimes preceded by a `.wtag` (whispers), which
is three special cases in CSS rather than one element to size. The schemes are
unaffected — they select on `.nick` / `.trip` / `.message` with no child
combinators, so an extra wrapper is invisible to them.

Verified on-device: all three render, and the gutter really does align nicks.

### 8. Composer should sit flush to the bottom

`OutlinedTextField` plus a separate `Button` is the most Material-default and
least form-fitting arrangement available. Target the site's shape: the input
flush to the bottom edge, send integrated into the field rather than beside it.

Keep `imePadding()` — without it the composer sits under the soft keyboard, which
was already fixed once (`android-notes.md`, "Bugs this found").

Worth checking `hc/client/style.css` for the real proportions, though the goal is
a phone-appropriate composer rather than a literal copy of the site's.

**Fixed:** one filled `TextField` flush to the bottom edge, send as a trailing
icon inside it, indicator colours cleared (the field *is* the bottom edge, so an
underline only draws a second one), `maxLines = 5` so a long message grows
rather than scrolling a single line. The outer padding moved off the `Column`
and onto the individual children, since a full-bleed composer cannot sit inside
a horizontally padded parent. `imePadding()` is untouched.

## Found while verifying the above

Neither was on the list; both are fixed.

### Moderation was offered against seniors and peers

The roster offered Kick / Ban / Muzzle against an **admin** while we held only
moderator. Upstream refuses exactly that — `kick.js` replies "Cannot kick other
users with the same level, how rude" and `dumb.js` tests the same thing — so it
was an affordance the server would always reject. `Moderation.available` now
drops every action when `target.level >= me.level`, and `SessionManager.moderate`
re-checks against the same function rather than the bare level gate, so a stale
button still cannot spend rate budget.

Note the comparison is `>=`, not `>`: a moderator cannot act on another
moderator, only on someone strictly below.

### Status bar icons vanished on a light scheme

Only visible once a light scheme was in use. The window follows the scheme now
(bug 2), but the status bar draws over it with its own icons, which stayed white.
`applyWindowBackground` sets `isAppearanceLightStatusBars` from `scheme.dark`.
