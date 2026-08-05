# Releasing

Tagging is the whole process: `.github/workflows/release.yml` builds the APK,
signs it, attaches a provenance attestation, and publishes a GitHub Release.

```sh
git tag v0.2.0
git push origin v0.2.0
```

The tag is the single source of truth for the version. `v1.2.3` becomes
`versionName 1.2.3` and `versionCode 10203` — derived from the numbers rather
than a run counter, because `versionCode` must increase monotonically forever or
Android refuses the update, and a run counter resets if the repo ever moves.

## One-time setup

The workflow needs a signing key. **Android identifies an app by its signature**:
an update signed with a different key is refused outright, and the only way out
is uninstall-and-lose-your-data. So this key is generated once and kept for the
life of the app — losing it means everyone has to uninstall and reinstall.

Generate it:

```sh
keytool -genkeypair -v \
  -keystore release.jks \
  -alias hcultra \
  -keyalg RSA -keysize 4096 \
  -validity 10000 \
  -storetype pkcs12
```

Back `release.jks` up somewhere that is not this repository — a password manager
or an encrypted archive. It is deliberately gitignored.

Then add four repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `KEYSTORE_PASSWORD` | the store password you chose |
| `KEY_ALIAS` | `hcultra` |
| `KEY_PASSWORD` | the key password (the same one, unless you set it apart) |

The release job checks `KEYSTORE_BASE64` is present before doing any work, and
verifies the decoded keystore actually opens, so a mis-pasted secret fails in a
step that says so rather than somewhere inside Gradle.

## What a user can verify

The point of building in CI is that nobody has to take our word for what is in
the APK. Every release carries:

- a **provenance attestation** — GitHub signs a statement that this exact file
  was produced by this workflow, from this repository, at a named commit:
  `gh attestation verify hcultra-1.2.3.apk --repo <owner>/hcultra`
- a **SHA-256** of the file, in the notes and as a `.sha256` alongside it
- the **signing certificate fingerprint**, which is the same for every release —
  so an APK from somewhere else cannot masquerade as an update

## Building a signed APK locally

Same four variables the workflow uses:

```sh
HCULTRA_KEYSTORE=$PWD/release.jks \
HCULTRA_KEYSTORE_PASSWORD=… \
HCULTRA_KEY_ALIAS=hcultra \
HCULTRA_KEY_PASSWORD=… \
./gradlew :app-android:assembleRelease
```

With none of them set, the release signing config is not created at all — so a
clean checkout still builds and tests without a key. That also means
`assembleRelease` on its own produces an **unsigned** APK, which Android will
not install; that is intentional, not a bug to work around.
