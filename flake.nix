{
  description = "HCUltra — a hack.chat client: Kotlin Multiplatform core, Android app";

  # Unstable, deliberately: compileSdk 37 needs `platforms;android-37.1`, which
  # only reached androidenv's repo.json after the last stable branch-off.
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs =
    { nixpkgs, ... }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
        "aarch64-darwin"
      ];

      # The SDK is unfree, and its licences must be accepted before androidenv
      # will build the composition at all. Both are set here rather than asked
      # of the user's own nixpkgs config: a devShell that only works if you
      # already edited a global config is not a devShell.
      forEachSystem =
        f:
        nixpkgs.lib.genAttrs systems (
          system:
          f (
            import nixpkgs {
              inherit system;
              config = {
                allowUnfree = true;
                android_sdk.accept_license = true;
              };
            }
          )
        );

      # This must be the build-tools version *AGP itself* defaults to, not the
      # newest available: AGP resolves its own, and on a store-read-only SDK it
      # cannot fall back to downloading one ("The SDK directory is not
      # writable"). Bumping AGP means checking this number again.
      buildToolsVersion = "36.0.0";
      # Two, and both are needed: the app module pins `compileSdkMinor = 1` and
      # so resolves `android-37.1`, while :core is an AGP KMP library whose DSL
      # has no compileSdkMinor at all and resolves `android-37.0`.
      platformVersions = [
        "37.0"
        "37.1"
      ];
    in
    {
      devShells = forEachSystem (
        pkgs:
        let
          sdkFor =
            extra:
            pkgs.androidenv.composeAndroidPackages (
              {
                inherit platformVersions;
                buildToolsVersions = [ buildToolsVersion ];
                # CMake and the NDK are on by default on x86_64; nothing here is
                # native, and they are a gigabyte of nothing.
                includeCmake = false;
                includeNDK = false;
              }
              // extra
            );

          shellFor =
            sdk:
            pkgs.mkShell {
              packages = [
                pkgs.jdk21
                sdk.androidsdk
                # probe/ is plain Node: fakeserver.mjs and the live prober.
                pkgs.nodejs_22
              ];

              # Gradle itself is not in `packages` on purpose — the wrapper is
              # committed and pins 9.6.1, and a shell-provided `gradle` on the
              # PATH is exactly how people end up building with the wrong one.
              shellHook = ''
                export ANDROID_HOME="${sdk.androidsdk}/libexec/android-sdk"
                # avdmanager reads this one specifically, and ignores ANDROID_HOME.
                export ANDROID_SDK_ROOT="$ANDROID_HOME"
                export JAVA_HOME="${pkgs.jdk21}"

                # AGP pulls a prebuilt aapt2 from Maven, dynamically linked
                # against an FHS loader that does not exist here, so every
                # Android build fails with a bare ENOENT until it is pointed at
                # the SDK's own patched copy.
                export GRADLE_OPTS="''${GRADLE_OPTS:+$GRADLE_OPTS }-Dorg.gradle.project.android.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/${buildToolsVersion}/aapt2"

                # Stated rather than left to the default, because the SDK it
                # would otherwise sit beside is in the store and read-only:
                # AVDs and emulator state have to live somewhere writable.
                export ANDROID_USER_HOME="''${ANDROID_USER_HOME:-$HOME/.android}"
              '';
            };
        in
        {
          default = shellFor (sdkFor { });

          # Split out because it costs one ~2 GB system image per platform
          # version above, and only x86_64 hosts run it at any useful speed.
          # See docs/android-notes.md for the AVD and the Wayland flag it needs.
          emulator = shellFor (sdkFor {
            includeEmulator = true;
            includeSystemImages = true;
            systemImageTypes = [ "google_apis" ];
            abiVersions = [ "x86_64" ];
          });
        }
      );
    };
}
