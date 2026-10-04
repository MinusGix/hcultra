# phantom

The real upstream server, run locally, with an admin we control.

`fakeserver.mjs` reimplements just enough protocol to misbehave on purpose;
phantom reimplements nothing. It exports hack-chat/main from `../../hc` at a
chosen ref, installs it, and sets a known admin and global-mod password, so the
states a client meets only by bad luck on hack.chat — a locked room and
`?purgatory`, a captcha, a channel password, a muzzle, a forced flair, a socket
the server still thinks is alive — can be produced on demand.

Needs node and the `hc/` clone (see the top-level README).

## Use

```sh
node phantom.mjs setup                 # export origin/master, npm ci, keys
node phantom.mjs setup --ref HEAD~3    # or any ref in ../../hc
node phantom.mjs serve                 # until Ctrl-C
node phantom.mjs serve --strict        # the real 25-point rate limit
```

`setup` re-exports from scratch but keeps `node_modules` while the lockfile is
unchanged. It removes `uwuify` from the exported copy's dependencies: npm
unpublished it on 2026-09-03, nothing imports it, and upstream's lockfile still
pins it, so `npm ci` would fail on it otherwise.

| port | what |
|---|---|
| 6070 | **connect clients here** — a TCP proxy in front of the server |
| 6079 | control API |
| 6071 | the upstream server itself |

From the Android emulator, point the app at `ws://10.0.2.2:6070`. Sign in with
`phantom-mod` as the password to be a global moderator, or `phantom-admin` for
admin.

### Addresses

The server keys the rate limiter and every user's `hash` — what muzzle and ban
act on — by address, and through the proxy everyone is 127.0.0.1. Connect to
`ws://…:6070/?as=<name>` to be given a stable address of your own instead (via
the `X-Forwarded-For` the server trusts). The same name always maps to the same
address, so reconnects look like the same phone.

### Rate limit

Relaxed by default (threshold 100 000): tests from one machine would otherwise
share and exhaust one budget. `--strict`, or `POST /reset {"strict":true}`,
restores the real one.

## Control API

All `POST` with a JSON body.

| path | body | effect |
|---|---|---|
| `/admin` | `{channel, verb, …args}` | an admin action in `channel` (the admin joins it and stays) |
| `/admin` | `{channel, frame}` | any raw frame from the admin; `channel` is filled in |
| `/freeze` | `{n?}` | silence the newest `n` client connections (all, if omitted) without closing them server-side |
| `/release` | | close frozen connections, so the server finally sees them go |
| `/reset` | `{strict?}` | restart the server: every channel, lock, ban and score forgotten |
| `GET /health` | | addresses, ref, live connection count |

`/admin` verbs, mapped to the real commands:

| verb | args | command |
|---|---|---|
| `lock` | `level?` (number or label, e.g. `"channelOwner"`) | `lockroom` — without a level the admin locks out everyone below admin, mods included |
| `unlock` | | `unlockroom` |
| `captcha` / `nocaptcha` | | `enablecaptcha` / `disablecaptcha` |
| `password` / `nopassword` | `password` | `setpassword` / `clearpassword` |
| `kick` / `ban` / `mute` | `nick` or `userid` | `kick` / `ban` / `dumb` |
| `unmute` | `hash` | `speak` |
| `flair` | `nick`, `flair` | `forceflair` |
| `color` | `nick`, `color` | `forcecolor` |
| `say` | `text` | `chat` |

From a shell:

```sh
node phantom.mjs admin lounge lock '{"level":"channelOwner"}'
node phantom.mjs admin lounge password '{"password":"hunter2"}'
curl -XPOST localhost:6079/freeze -d '{"n":1}'
```

The reply carries the frames the admin received in the next 400 ms, so a
refused command shows its `warn`.

### Freezing

A phone that drops off a network does not close its socket; the server goes on
holding it, and its nick, until TCP gives up. hackchat-server pings every 16 s
but never acts on a missing pong, so on hack.chat that can take many minutes.
`/freeze` reproduces it: the client side is closed, as the phone's would be, but
the proxy keeps the server side open and silent. A frozen connection never
times out on its own here — `/release` ends it.

## Kotlin tests

`./gradlew :core:phantomTest` starts phantom on 6170/6171/6179 (so a phantom
you are running by hand is left alone), runs `core/src/jvmTest/…/phantom/`
against it, and stops it when the build ends. It runs `setup` first if `srv/`
is missing. Plain `jvmTest` skips these tests.

Each session logs every frame in and out on stdout, which Gradle keeps per test
in `core/build/test-results/phantomTest/`, so a failure comes with the
conversation that led to it. The server's own log is `core/build/phantom/phantom.log`.
