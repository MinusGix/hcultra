#!/bin/bash
# SessionStart hook for Claude Code cloud sessions: get the tests runnable.
#
# The full build needs Google's Maven and the Android SDK, both served from
# dl.google.com, which the default cloud network policy blocks. When that host
# is reachable this installs the SDK the README asks for; when it is not, it
# warms the JVM-only build in jvm-gradle.sh instead. Either way it clones the
# upstream server into hc/ for the phantom and legacy tests.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

REPO="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"
cd "$REPO"
log() { echo "session-start: $*" >&2; }

# hc/ is gitignored and needs full history: legacy.mjs exports a 2019 commit.
if [ ! -d hc/.git ]; then
  git clone -q https://github.com/hack-chat/main hc || log "could not clone hack-chat/main; phantomTest and legacyTest will fail"
fi

# Maven Central answers the shared cloud egress with HTTP 429 on a cold
# resolve, and Gradle gives up on those, so read it from Google's mirror. The
# plugin portal redirects to Maven Central too, so it moves to the back.
mkdir -p "$HOME/.gradle/init.d"
cat > "$HOME/.gradle/init.d/maven-central-mirror.gradle.kts" <<'EOF'
// Written by hcultra's .claude/hooks/session-start.sh; edits are overwritten.
val mirror = uri("https://maven-central.storage-download.googleapis.com/maven2/")
fun RepositoryHandler.useMirror() {
    withType<MavenArtifactRepository>().configureEach {
        if (url.host == "repo.maven.apache.org") setUrl(mirror)
    }
    findByName("Gradle Central Plugin Repository")?.let { remove(it); add(it) }
}
settingsEvaluated {
    pluginManagement.repositories.useMirror()
    dependencyResolutionManagement.repositories.useMirror()
}
allprojects {
    buildscript.repositories.useMirror()
    repositories.useMirror()
}
EOF

SDK="$HOME/android-sdk"
if curl -fsS -o /dev/null --max-time 15 https://dl.google.com/android/repository/repository2-3.xml 2>/dev/null; then
  if [ ! -d "$SDK/platforms/android-37.1" ]; then
    log "installing the Android SDK into $SDK"
    tmp="$(mktemp -d)"
    curl -fsSL -o "$tmp/tools.zip" https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
    mkdir -p "$SDK/cmdline-tools"
    rm -rf "$SDK/cmdline-tools/latest"
    unzip -q "$tmp/tools.zip" -d "$tmp"
    mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
    rm -rf "$tmp"
    yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null || true
    # Same packages as CI, plus the 37.0 platform :core resolves (android-notes.md § Nix).
    "$SDK/cmdline-tools/latest/bin/sdkmanager" --install \
      "platforms;android-37.0" "platforms;android-37.1" "build-tools;37.0.0" > /dev/null
  fi
  [ -n "${CLAUDE_ENV_FILE:-}" ] && echo "export ANDROID_HOME=\"$SDK\" ANDROID_SDK_ROOT=\"$SDK\"" >> "$CLAUDE_ENV_FILE"
  echo "sdk.dir=$SDK" > local.properties
  ANDROID_HOME="$SDK" ./gradlew :core:jvmTestClasses --no-daemon -q > /dev/null
  echo "Full Android build available: ./gradlew build, ./gradlew :core:jvmTest, :core:phantomTest, :core:legacyTest."
else
  .claude/hooks/jvm-gradle.sh :core:jvmTestClasses --no-daemon -q > /dev/null
  echo "dl.google.com is blocked here, so the Android build cannot configure. Run core tests with" \
       ".claude/hooks/jvm-gradle.sh :core:jvmTest (also :core:phantomTest, :core:legacyTest);" \
       "reports are under ~/.cache/hcultra-jvm/core/build/."
fi
